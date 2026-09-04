//! Core Bitcoin custody logic: multisig descriptors, BIP-48 key derivation,
//! PSBT construction/validation, and the policy engine.
//!
//! Every other crate in this workspace depends on this one — see
//! `docs/01-architecture.md` in the repo root for why this is the critical path.
//!
//! Everything here is a **simplified skeleton**: string/struct stand-ins for
//! real Bitcoin types, no actual script or signature handling. This is the
//! intended starting point for term-1 work (`docs/00-capstone-brief.md` §4)
//! — swap the mock internals for `rust-bitcoin`/`miniscript` once the team
//! has been through `.claude/skills/bitcoin-fundamentals/SKILL.md`, without
//! needing to change these public signatures much.

pub mod error;

pub mod descriptor {
    //! Builds the P2WSH 2-of-3 multisig output descriptor from the borrower,
    //! platform, and lender pubkeys. See `docs/00-capstone-brief.md` §3.1.

    use crate::error::VaultCoreError;
    use serde::{Deserialize, Serialize};

    #[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
    pub struct PartyPubkeys {
        pub borrower: String,
        pub platform: String,
        pub lender: String,
    }

    #[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
    pub struct VaultDescriptor {
        pub descriptor: String,
        pub address: String,
    }

    /// Builds a 2-of-3 multisig descriptor from the three parties' pubkeys.
    ///
    /// TODO(term 1, weeks 3-6): replace this with a real `miniscript`
    /// `Descriptor<PublicKey>` and derive a real P2WSH address. The mock
    /// address below is deterministic (same pubkeys always produce the same
    /// descriptor/address) so downstream code can treat it as if it were
    /// real for now.
    pub fn build_descriptor(parties: &PartyPubkeys) -> VaultDescriptor {
        let descriptor = format!(
            "wsh(sortedmulti(2,{},{},{}))",
            parties.borrower, parties.platform, parties.lender
        );
        let address = format!("tb1q{:x}", mock_hash(&descriptor));
        VaultDescriptor {
            descriptor,
            address,
        }
    }

    use bitcoin::bip32::{ChildNumber, DerivationPath, Xpub};
    use miniscript::{
        DescriptorPublicKey, Threshold,
        descriptor::{Descriptor, DescriptorXKey, Wildcard},
    };

    pub fn build_multisig_descriptor(
        xpubs: &[Xpub],
        threshold: usize,
    ) -> Result<Descriptor<DescriptorPublicKey>, VaultCoreError> {
        let receive_path = DerivationPath::from(vec![ChildNumber::Normal { index: 0 }]);

        let keys = xpubs
            .iter()
            .cloned()
            .map(|xkey| {
                DescriptorPublicKey::XPub(DescriptorXKey {
                    origin: None,
                    xkey,
                    derivation_path: receive_path.clone(),
                    wildcard: Wildcard::Unhardened,
                })
            })
            .collect();

        let threshold = Threshold::new(threshold, keys)?;

        Ok(Descriptor::new_wsh_sortedmulti(threshold)?)
    }

    /// Derives `descriptor` at `index` and returns its real Bitcoin address.
    ///
    /// A ranged descriptor contains `/*` after its xpub derivation path. For a
    /// non-ranged descriptor, Miniscript accepts the index but derives no child.
    pub fn derive_address_at(
        descriptor: &Descriptor<DescriptorPublicKey>,
        index: u32,
        network: bitcoin::Network,
    ) -> Result<bitcoin::Address, VaultCoreError> {
        let secp = bitcoin::secp256k1::Secp256k1::verification_only();
        let definite_descriptor = descriptor.at_derivation_index(index)?;
        let derived_descriptor = definite_descriptor.derived_descriptor(&secp);

        Ok(derived_descriptor.address(network)?)
    }

    fn mock_hash(s: &str) -> u64 {
        use std::hash::{Hash, Hasher};
        let mut h = std::collections::hash_map::DefaultHasher::new();
        s.hash(&mut h);
        h.finish()
    }

    #[cfg(test)]
    mod tests {
        use super::*;
        use bitcoin::{
            Network, NetworkKind,
            bip32::{ChainCode, ChildNumber, Fingerprint, Xpub},
            secp256k1::PublicKey,
        };
        use miniscript::descriptor::WshInner;
        use std::str::FromStr;

        fn test_xpubs() -> Vec<Xpub> {
            [
                "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
                "02c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5",
                "02f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9",
            ]
            .into_iter()
            .enumerate()
            .map(|(index, key)| Xpub {
                network: NetworkKind::Test,
                depth: 0,
                parent_fingerprint: Fingerprint::default(),
                child_number: ChildNumber::Normal { index: 0 },
                public_key: PublicKey::from_str(key).expect("valid compressed public key"),
                chain_code: ChainCode::from([(index + 1) as u8; 32]),
            })
            .collect()
        }

        fn parties() -> PartyPubkeys {
            PartyPubkeys {
                borrower: "borrower_pk".into(),
                platform: "platform_pk".into(),
                lender: "lender_pk".into(),
            }
        }

        #[test]
        fn same_parties_produce_same_descriptor() {
            let a = build_descriptor(&parties());
            let b = build_descriptor(&parties());
            assert_eq!(a, b);
        }

        #[test]
        fn different_parties_produce_different_addresses() {
            let a = build_descriptor(&parties());
            let mut other = parties();
            other.lender = "different_lender_pk".into();
            let b = build_descriptor(&other);
            assert_ne!(a.address, b.address);
        }

        #[test]
        fn generic_multisig_descriptor_round_trips() {
            let descriptor =
                build_multisig_descriptor(&test_xpubs(), 2).expect("valid 2-of-3 descriptor");
            let encoded = descriptor.to_string();

            assert!(encoded.starts_with("wsh(sortedmulti(2,"));
            assert_eq!(encoded.matches("/0/*").count(), 3);
            assert!(descriptor.has_wildcard());

            let parsed = Descriptor::<DescriptorPublicKey>::from_str(&encoded)
                .expect("generated descriptor parses");

            assert_eq!(parsed, descriptor);

            let Descriptor::Wsh(wsh) = &parsed else {
                panic!("expected P2WSH descriptor");
            };
            let WshInner::SortedMulti(sortedmulti) = wsh.as_inner() else {
                panic!("expected sortedmulti descriptor");
            };

            assert_eq!(sortedmulti.k(), 2);
            assert_eq!(sortedmulti.n(), 3);
            assert_eq!(sortedmulti.pks().len(), 3);
        }

        #[test]
        fn generic_multisig_descriptor_has_real_addresses() {
            let descriptor =
                build_multisig_descriptor(&test_xpubs(), 2).expect("valid 2-of-3 descriptor");

            let testnet_address = derive_address_at(&descriptor, 0, Network::Testnet)
                .expect("real testnet P2WSH address");
            let mainnet_address = derive_address_at(&descriptor, 0, Network::Bitcoin)
                .expect("real mainnet P2WSH address");
            let next_testnet_address = derive_address_at(&descriptor, 1, Network::Testnet)
                .expect("real next testnet P2WSH address");

            assert!(testnet_address.to_string().starts_with("tb1q"));
            assert!(mainnet_address.to_string().starts_with("bc1q"));
            assert_ne!(testnet_address, mainnet_address);
            assert_ne!(testnet_address, next_testnet_address);
        }

        #[test]
        fn sortedmulti_address_is_independent_of_input_order() {
            let xpubs = test_xpubs();
            let descriptor = build_multisig_descriptor(&xpubs, 2).expect("valid 2-of-3 descriptor");

            let mut reordered_xpubs = xpubs;
            reordered_xpubs.rotate_left(1);
            let reordered_descriptor = build_multisig_descriptor(&reordered_xpubs, 2)
                .expect("valid reordered 2-of-3 descriptor");

            let address =
                derive_address_at(&descriptor, 0, Network::Testnet).expect("testnet P2WSH address");
            let reordered_address = derive_address_at(&reordered_descriptor, 0, Network::Testnet)
                .expect("reordered testnet P2WSH address");

            assert_eq!(address, reordered_address);
        }

        #[test]
        fn rejects_threshold_greater_than_key_count() {
            let err = build_multisig_descriptor(&test_xpubs(), 4).unwrap_err();
            assert!(matches!(err, VaultCoreError::Threshold(_)), "got {err:?}");
        }

        #[test]
        fn wraps_invalid_derivation_indices_in_vault_core_error() {
            let descriptor = build_multisig_descriptor(&test_xpubs(), 2).unwrap();
            let err = derive_address_at(&descriptor, 1 << 31, Network::Testnet).unwrap_err();

            assert!(
                matches!(err, VaultCoreError::NonDefiniteDescriptorKey(_)),
                "got {err:?}"
            );
        }
    }
}

pub mod derivation {
    //! BIP-48 child pubkey derivation (`m/48'/0'/0'/2'`) from an account xpub.

    use crate::error::VaultCoreError;
    use bitcoin::bip32::{ChildNumber, DerivationPath, Fingerprint, Xpub};

    /// The BIP-48 path element this project uses for P2WSH multisig
    /// (the `2'` script-type element). See
    /// `.claude/skills/bitcoin-fundamentals/SKILL.md`.
    pub const BIP48_MULTISIG_PATH: &str = "m/48'/0'/0'/2'";

    /// Derives the child pubkey at `index` under a party's account xpub.
    ///
    /// TODO(term 1, weeks 3-6): replace with real BIP-32 derivation via the
    /// `bitcoin` crate's `Xpub` type. This mock is deterministic per
    /// `(account_xpub, index)` pair, which is enough for other modules to
    /// build against.
    pub fn derive_child_pubkey(account_xpub: &str, index: u32) -> String {
        format!("{account_xpub}/{BIP48_MULTISIG_PATH}/{index}")
    }

    /// `Debug` is safe here: every field is public key material. Contrast the
    /// warning on `keys::generate_entropy` — nothing in this struct is secret.
    #[derive(Debug)]
    pub struct DerivedKey {
        pub fingerprint: Fingerprint,
        pub xpub: Xpub,
        pub derivation_path: DerivationPath,
    }

    pub fn derive_account_xpub(
        account_xpub: &Xpub,
        change: u32,
        index: u32,
    ) -> Result<DerivedKey, VaultCoreError> {
        let path = DerivationPath::from(vec![
            ChildNumber::from_normal_idx(change)?,
            ChildNumber::from_normal_idx(index)?,
        ]);

        let derived = account_xpub.derive_pub(&bitcoin::secp256k1::Secp256k1::new(), &path)?;

        Ok(DerivedKey {
            fingerprint: derived.fingerprint(),
            xpub: derived,
            derivation_path: path,
        })
    }

    #[cfg(test)]
    mod tests {
        use std::str::FromStr;

        use super::*;

        /// BIP-32 Test Vector 1, chain `m/0H/1/2H`, verbatim from the BIP.
        fn vector1_account_xpub() -> Xpub {
            Xpub::from_str(
                "xpub6D4BDPcP2GT577Vvch3R8wDkScZWzQzMMUm3PWbmWvVJrZwQY4VUNgqFJPMM3No2dFDFGTsxxpG5uJh7n7epu4trkrX7x7DogT5Uv6fcLW5",
            )
            .unwrap()
        }

        #[test]
        fn same_inputs_produce_same_pubkey() {
            assert_eq!(
                derive_child_pubkey("xpub_borrower", 0),
                derive_child_pubkey("xpub_borrower", 0)
            );
        }

        #[test]
        fn different_index_produces_different_pubkey() {
            assert_ne!(
                derive_child_pubkey("xpub_borrower", 0),
                derive_child_pubkey("xpub_borrower", 1)
            );
        }

        /// Two key holders at the same BIP-48 path must not collide — a duplicate key
        /// in a 2-of-3 descriptor would let one party satisfy two of the three slots.
        #[test]
        fn two_parties_derive_different_fingerprints() {
            use crate::keys::{ScriptType, account_multisig_xpub_from_mnemonic, generate_mnemonic};
            use bitcoin::Network;

            // Fixed entropy, so each "party" is deterministic without pasting word lists.
            let account_xpub = |entropy: [u8; 32]| {
                let mnemonic = generate_mnemonic(&entropy).unwrap();
                account_multisig_xpub_from_mnemonic(
                    &mnemonic,
                    "",
                    Network::Bitcoin,
                    0,
                    ScriptType::P2wsh,
                )
                .unwrap()
                .1
            };

            let borrower_account = account_xpub([0u8; 32]);
            let borrower = derive_account_xpub(&borrower_account, 0, 0).unwrap();
            let lender = derive_account_xpub(&account_xpub([1u8; 32]), 0, 0).unwrap();

            assert_ne!(borrower.fingerprint, lender.fingerprint);
            assert_ne!(borrower.xpub, lender.xpub);
            // …and the function actually descended two levels, rather than handing
            // back the account key it was given (which the asserts above can't catch).
            assert_eq!(borrower.xpub.depth, borrower_account.depth + 2);
        }

        /// BIP-32 Test Vector 1, chain `m/0H/1/2H` → `m/0H/1/2H/2/1000000000`
        /// (github.com/bitcoin/bips/blob/master/bip-0032.mediawiki).
        ///
        /// Provenance: both xpubs are verbatim from the BIP's table and were never
        /// produced by this crate. The fingerprint is not published there — it is
        /// `hash160(pubkey)[..4]` of the expected xpub, so it follows from the BIP
        /// value without depending on our derivation. The path string just restates
        /// the arguments. That is what separates this test from
        /// `keys::tests::account_derivation_functions_match_bip_vectors`, whose
        /// expected values came out of this implementation and so can only catch a
        /// *change* in our derivation, never a systematic error in it.
        ///
        /// The two elements below are non-hardened, and this is the only function in
        /// vault-core that derives from a public key alone. The account paths in
        /// `keys` are fully hardened, so nothing else exercises CKDpub.
        #[test]
        fn derive_account_xpub_matches_bip32_vector_1() {
            // `change` and `index` are deliberately different values: swapping the two
            // arguments changes the result, so this catches an argument-order bug.
            // (0, 0) or (0, 1) would not — don't "tidy" these into round numbers.
            let derived = derive_account_xpub(&vector1_account_xpub(), 2, 1_000_000_000).unwrap();

            assert_eq!(
                derived.xpub.to_string(),
                "xpub6H1LXWLaKsWFhvm6RVpEL9P4KfRZSW7abD2ttkWP3SSQvnyA8FSVqNTEcYFgJS2UaFcxupHiYkro49S8yGasTvXEYBVPamhGW6cFJodrTHy"
            );
            // The base58 above already covers depth, parent fingerprint, child number,
            // chain code and key under one checksum. `fingerprint` is this key's *own*
            // identifier, which the serialization does not carry — pin it separately so
            // a later switch to the master fingerprint (what descriptor key-origin
            // actually wants) fails loudly instead of silently.
            assert_eq!(derived.fingerprint.to_string(), "d69aa102");
            assert_eq!(derived.derivation_path.to_string(), "2/1000000000");
        }

        /// `2^31` is the first hardened index, and CKDpub cannot derive hardened
        /// children at all — it must be rejected, not silently wrapped into a normal
        /// index. Mirrors `keys::tests::out_of_range_account_is_a_bip32_error` on the
        /// derivation side.
        #[test]
        fn out_of_range_change_is_a_bip32_error() {
            let err = derive_account_xpub(&vector1_account_xpub(), 0x8000_0000, 0)
                .expect_err("change index 2^31 is hardened and cannot be derived from an xpub");
            assert!(matches!(err, VaultCoreError::Bip32(_)), "got {err:?}");
        }
    }
}

pub mod psbt {
    //! PSBT construction and parsing (rust-bitcoin's `Psbt` type once the
    //! `bitcoin` dependency is uncommented in Cargo.toml).

    use serde::{Deserialize, Serialize};

    #[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
    pub struct PsbtOutput {
        pub address: String,
        pub amount_sats: u64,
    }

    /// TODO(term 1, weeks 7-10): replace with `bitcoin::Psbt`. Kept
    /// deliberately simple (and `serde`-able) so it can be the shared
    /// interchange format between `custody-service` and
    /// `lender-signer-cli` before that lands.
    #[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
    pub struct UnsignedPsbt {
        pub loan_id: String,
        pub inputs: Vec<String>,
        pub outputs: Vec<PsbtOutput>,
    }

    pub fn build_psbt(
        loan_id: impl Into<String>,
        inputs: Vec<String>,
        outputs: Vec<PsbtOutput>,
    ) -> UnsignedPsbt {
        UnsignedPsbt {
            loan_id: loan_id.into(),
            inputs,
            outputs,
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn builds_psbt_with_given_fields() {
            let psbt = build_psbt(
                "loan-1",
                vec!["utxo:0".into()],
                vec![PsbtOutput {
                    address: "tb1qborrower".into(),
                    amount_sats: 100_000,
                }],
            );
            assert_eq!(psbt.loan_id, "loan-1");
            assert_eq!(psbt.outputs.len(), 1);
        }
    }
}

pub mod policy {
    //! The policy engine: verifies a PSBT's outputs match the reason it was
    //! submitted for signing (repayment / liquidation / fallback) before any
    //! key is used to sign it. This is the highest-risk module in the whole
    //! project — see `.claude/skills/policy-engine-review/SKILL.md` before
    //! changing anything here, and pair-review every change (two-person rule
    //! per `docs/00-capstone-brief.md` §3.6).
    //!
    //! This skeleton operates on the mock `psbt::UnsignedPsbt` type rather
    //! than a real Bitcoin PSBT, but the authorization logic — default-deny,
    //! exact address+amount match, reject any extra output — is the real
    //! shape the eventual policy engine needs. Term 1 weeks 7-10 is when
    //! this gets rebuilt against `bitcoin::Psbt`.

    use super::psbt::UnsignedPsbt;
    use serde::{Deserialize, Serialize};

    #[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
    pub enum SigningReason {
        CollateralReturn,
        Liquidation,
        Fallback,
    }

    #[derive(Debug, Clone, Serialize, Deserialize)]
    pub struct LoanContext {
        pub loan_id: String,
        pub reason: SigningReason,
        pub expected_output_address: String,
        pub expected_amount_sats: u64,
    }

    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    pub enum PolicyViolation {
        /// The PSBT references a different loan than the signing request claims.
        WrongLoan,
        /// The PSBT has no output paying the expected address at all.
        NoMatchingOutput,
        /// The PSBT's single output pays the right address but the wrong amount.
        WrongAmount,
        /// The PSBT's single output pays the wrong address entirely.
        WrongOutputAddress,
        /// The PSBT has more than one output — could be siphoning extra value
        /// to an attacker-controlled address alongside a legitimate one.
        UnexpectedExtraOutput,
    }

    pub struct PolicyEngine;

    impl PolicyEngine {
        /// Authorizes `psbt` against `context`. Default-deny: anything that
        /// isn't *exactly* one output paying the expected address the
        /// expected amount, for the expected loan, is rejected.
        pub fn authorize(
            psbt: &UnsignedPsbt,
            context: &LoanContext,
        ) -> Result<(), PolicyViolation> {
            if psbt.loan_id != context.loan_id {
                return Err(PolicyViolation::WrongLoan);
            }
            match psbt.outputs.as_slice() {
                [] => Err(PolicyViolation::NoMatchingOutput),
                [out] if out.address != context.expected_output_address => {
                    Err(PolicyViolation::WrongOutputAddress)
                }
                [out] if out.amount_sats != context.expected_amount_sats => {
                    Err(PolicyViolation::WrongAmount)
                }
                [_out] => Ok(()),
                _ => Err(PolicyViolation::UnexpectedExtraOutput),
            }
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;
        use crate::psbt::PsbtOutput;

        fn context() -> LoanContext {
            LoanContext {
                loan_id: "loan-1".into(),
                reason: SigningReason::CollateralReturn,
                expected_output_address: "tb1qborrower".into(),
                expected_amount_sats: 100_000,
            }
        }

        fn psbt_with_outputs(loan_id: &str, outputs: Vec<PsbtOutput>) -> UnsignedPsbt {
            UnsignedPsbt {
                loan_id: loan_id.into(),
                inputs: vec!["utxo:0".into()],
                outputs,
            }
        }

        #[test]
        fn authorizes_exact_match() {
            let psbt = psbt_with_outputs(
                "loan-1",
                vec![PsbtOutput {
                    address: "tb1qborrower".into(),
                    amount_sats: 100_000,
                }],
            );
            assert_eq!(PolicyEngine::authorize(&psbt, &context()), Ok(()));
        }

        #[test]
        fn rejects_wrong_output_address() {
            let psbt = psbt_with_outputs(
                "loan-1",
                vec![PsbtOutput {
                    address: "tb1qattacker".into(),
                    amount_sats: 100_000,
                }],
            );
            assert_eq!(
                PolicyEngine::authorize(&psbt, &context()),
                Err(PolicyViolation::WrongOutputAddress)
            );
        }

        #[test]
        fn rejects_wrong_amount() {
            let psbt = psbt_with_outputs(
                "loan-1",
                vec![PsbtOutput {
                    address: "tb1qborrower".into(),
                    amount_sats: 999_999,
                }],
            );
            assert_eq!(
                PolicyEngine::authorize(&psbt, &context()),
                Err(PolicyViolation::WrongAmount)
            );
        }

        #[test]
        fn rejects_wrong_loan() {
            let psbt = psbt_with_outputs(
                "loan-2",
                vec![PsbtOutput {
                    address: "tb1qborrower".into(),
                    amount_sats: 100_000,
                }],
            );
            assert_eq!(
                PolicyEngine::authorize(&psbt, &context()),
                Err(PolicyViolation::WrongLoan)
            );
        }

        #[test]
        fn rejects_extra_siphoned_output_even_when_legit_output_present() {
            let psbt = psbt_with_outputs(
                "loan-1",
                vec![
                    PsbtOutput {
                        address: "tb1qborrower".into(),
                        amount_sats: 100_000,
                    },
                    PsbtOutput {
                        address: "tb1qattacker".into(),
                        amount_sats: 50_000,
                    },
                ],
            );
            assert_eq!(
                PolicyEngine::authorize(&psbt, &context()),
                Err(PolicyViolation::UnexpectedExtraOutput)
            );
        }

        #[test]
        fn rejects_psbt_with_no_outputs() {
            let psbt = psbt_with_outputs("loan-1", vec![]);
            assert_eq!(
                PolicyEngine::authorize(&psbt, &context()),
                Err(PolicyViolation::NoMatchingOutput)
            );
        }
    }
}

pub mod keys;

pub mod hw;
