//! Key generation and BIP account derivation.
//!
//! # Memory hygiene — what the zeroize work here does and does not buy
//!
//! It buys: the entropy and seed this module *owns* are wiped on drop, and
//! bip39's `zeroize` feature gives `Mnemonic` `ZeroizeOnDrop`. The master
//! `Xpriv` this module holds is volatile-erased once it has been derived from.
//!
//! Three copy sites remain that cannot be closed from here:
//!
//! 1. `Mnemonic` and `to_seed` both return by value. A Rust move is a memcpy and
//!    copy elision is an optimization, not a guarantee, so a source copy may
//!    survive un-wiped.
//! 2. `Xpriv` is `#[derive(Copy)]` in rust-bitcoin, and `derive_priv` copies it
//!    once per path level. The private keys themselves are therefore duplicated
//!    inside a crate we do not control, and no `Drop` impl can catch that. Those
//!    copies all live in callee frames that are popped and clobbered by the next
//!    call at that depth, which is why the one binding we own is worth erasing
//!    and they are not reachable.
//! 3. zeroize's own docs note that stack spilling may leave temporaries anyway.
//!
//! So treat this as defense in depth, not a guarantee. It defends against reads
//! *after* the fact — a core dump, swap, a memory-disclosure bug — never against
//! an attacker reading memory while these functions run. The boundary that
//! actually matters next is UniFFI (No.8): crossing it serializes secrets into
//! buffers that are never zeroized, so onboarding should cross it once, not once
//! per step.

use bitcoin::bip32::{ChildNumber, DerivationPath, Fingerprint, Xpub};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Purpose {
    Bip44,
    Bip49,
    Bip84,
    Bip86,
}

impl Purpose {
    fn index(self) -> u32 {
        match self {
            Self::Bip44 => 44,
            Self::Bip49 => 49,
            Self::Bip84 => 84,
            Self::Bip86 => 86,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum KeySourceType {
    MebitThisDevice,
    MebitOtherDevice,
    HardwareWallet(HwVendor),
}

#[derive(Debug, Clone, PartialEq)]
pub enum HwVendor {
    Jade,
    TrezorSafe7,
}

#[derive(Debug, Clone)]
pub struct VaultKey {
    pub label: String,
    pub source_type: KeySourceType,
    pub fingerprint: Fingerprint,
    pub xpub: Xpub,
    pub derivation_path: String, // เช่น "m/48'/1'/0'/2'"
}

use bip39::Mnemonic;
use bitcoin::Network;
use bitcoin::bip32::Xpriv;

use zeroize::Zeroizing;

use crate::error::VaultCoreError;

/// สร้าง mnemonic ใหม่จาก entropy ที่ปลอดภัย (OS CSPRNG) — ผู้เรียกต้องแสดงให้ผู้ใช้ backup
/// แล้วทำลายทิ้งจาก memory ทันทีหลังส่งต่อให้ secure storage (No.9.5) เก็บ ไม่ค้างไว้ใน state นานๆ
///
/// `Zeroizing` wipes the bytes when the caller drops them. Note that `Zeroizing`
/// derives `Debug` and prints what it wraps, so never Debug-print one of these.
pub fn generate_entropy() -> Result<Zeroizing<[u8; 32]>, VaultCoreError> {
    // create entropy
    let mut entropy = Zeroizing::new([0u8; 32]);
    getrandom::fill(&mut *entropy)?;
    Ok(entropy)
}

pub fn generate_mnemonic(entropy: &[u8; 32]) -> Result<Mnemonic, VaultCoreError> {
    // Entropy -> Mnemonic
    Ok(Mnemonic::from_entropy(entropy)?)
}

/// Mnemonic + Passphrase -> Seed. Infallible: `to_seed` is just PBKDF2 over an
/// already-validated `Mnemonic`, so there is nothing left to reject here.
///
/// Wiped on drop, with one gap we can't close: `to_seed` hands back a `[u8; 64]`
/// by value, so the temporary this wraps is itself an un-wiped copy.
pub fn generate_seed(mnemonic: &Mnemonic, passphrase: &str) -> Zeroizing<[u8; 64]> {
    Zeroizing::new(mnemonic.to_seed(passphrase))
}

fn coin_type(network: Network) -> ChildNumber {
    match network {
        Network::Bitcoin => ChildNumber::Hardened { index: 0 }, // mainnet
        _ => ChildNumber::Hardened { index: 1 },                // testnet/signet/regtest
    }
}

/// **Caller contract:** this returns the *master* key — strictly more sensitive than
/// an account key, since it reconstructs every account on every network. Erase it with
/// `.private_key.non_secure_erase()` as soon as you have derived what you need, the way
/// `account_xpub_from_mnemonic` does. `Xpriv` is `Copy`, so nothing enforces this.
pub fn generate_master_xpriv(network: Network, seed: &[u8; 64]) -> Result<Xpriv, VaultCoreError> {
    Ok(Xpriv::new_master(network, seed)?)
}

/// แปลง mnemonic (+ passphrase optional) เป็น master seed แล้ว derive account-level xpub
/// ที่ path m/{purpose}'/coin_type'/account' (3 hardened element แรกตาม BIP ก่อนถึงส่วนต่อไปของ path)
///
/// **Caller contract for the returned `Xpriv`:** hand it to secure storage (No.9.5)
/// and drop it immediately. This crate cannot wipe it for you — `Xpriv` is
/// `#[derive(Copy)]` in rust-bitcoin, so it is duplicated implicitly on every move
/// and no `Drop` impl can catch those copies. Documentation, not enforcement.
/// The intermediate seed and master `Xpriv` *are* wiped here.
pub fn account_xpub_from_mnemonic(
    mnemonic: &Mnemonic,
    passphrase: &str,
    purpose: Purpose,
    network: Network,
    account: u32,
) -> Result<(Xpriv, Xpub), VaultCoreError> {
    let secp = bitcoin::secp256k1::Secp256k1::new();
    let seed = generate_seed(mnemonic, passphrase);

    let path = DerivationPath::from(vec![
        ChildNumber::from_hardened_idx(purpose.index())?,
        coin_type(network),
        ChildNumber::from_hardened_idx(account)?,
    ]);

    let mut master_xpriv = generate_master_xpriv(network, &seed)?;
    let account_xpriv = master_xpriv.derive_priv(&secp, &path)?;
    // Best effort: a volatile write over the one master-key copy we own, and the only
    // one that outlives this frame — the copies inside new_master/derive_priv/ckd_priv
    // sit in callee frames that are popped and clobbered by the next call. Skipped on
    // the `?` above; derive_priv only fails on a negligible tweak failure here, since
    // both indices are already known-valid hardened numbers.
    master_xpriv.private_key.non_secure_erase();

    let account_xpub = Xpub::from_priv(&secp, &account_xpriv);

    Ok((account_xpriv, account_xpub))
}

pub enum ScriptType {
    P2wsh,
    P2shP2wsh,
} // 2' primary, 1' fallback/import only

impl ScriptType {
    fn child_number(&self) -> ChildNumber {
        match self {
            ScriptType::P2wsh => ChildNumber::Hardened { index: 2 },
            ScriptType::P2shP2wsh => ChildNumber::Hardened { index: 1 },
        }
    }
}

pub fn account_multisig_xpub_from_mnemonic(
    mnemonic: &Mnemonic,
    passphrase: &str,
    network: Network,
    account: u32,
    script_type: ScriptType,
) -> Result<(Xpriv, Xpub), VaultCoreError> {
    let secp = bitcoin::secp256k1::Secp256k1::new();
    let seed = generate_seed(mnemonic, passphrase);

    let path = DerivationPath::from(vec![
        ChildNumber::from_hardened_idx(48)?,
        coin_type(network),
        ChildNumber::from_hardened_idx(account)?,
        script_type.child_number(),
    ]);

    let mut master_xpriv = generate_master_xpriv(network, &seed)?;
    let account_xpriv = master_xpriv.derive_priv(&secp, &path)?;
    // Best effort: a volatile write over the one master-key copy we own, and the only
    // one that outlives this frame — the copies inside new_master/derive_priv/ckd_priv
    // sit in callee frames that are popped and clobbered by the next call. Skipped on
    // the `?` above; derive_priv only fails on a negligible tweak failure here, since
    // both indices are already known-valid hardened numbers.
    master_xpriv.private_key.non_secure_erase();

    let account_xpub = Xpub::from_priv(&secp, &account_xpriv);

    Ok((account_xpriv, account_xpub))
}

#[cfg(test)]
mod tests {
    use std::str::FromStr;

    use bitcoin::{bip32::ChainCode, hex::FromHex, secp256k1::PublicKey};

    use super::*;

    /// Provenance, per column: entropy / mnemonic / seed / master xprv are the
    /// canonical BIP-39 English vectors with passphrase "TREZOR".
    #[test]
    fn bip39_test_vectors() {
        let vectors = [
            (
                "0000000000000000000000000000000000000000000000000000000000000000",
                "abandon abandon abandon abandon abandon abandon \
                 abandon abandon abandon abandon abandon abandon \
                 abandon abandon abandon abandon abandon abandon \
                 abandon abandon abandon abandon abandon art",
                "bda85446c68413707090a52022edd26a1c9462295029f2e60cd7c4f2bbd3097170af7a4d73245cafa9c3cca8d561a7c3de6f5d4a10be8ed2a5e608d68f92fcc8",
                "xprv9s21ZrQH143K32qBagUJAMU2LsHg3ka7jqMcV98Y7gVeVyNStwYS3U7yVVoDZ4btbRNf4h6ibWpY22iRmXq35qgLs79f312g2kj5539ebPM",
            ),
            (
                "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f",
                "legal winner thank year wave sausage worth useful \
                 legal winner thank year wave sausage worth useful \
                 legal winner thank year wave sausage worth title",
                "bc09fca1804f7e69da93c2f2028eb238c227f2e9dda30cd63699232578480a4021b146ad717fbb7e451ce9eb835f43620bf5c514db0f8add49f5d121449d3e87",
                "xprv9s21ZrQH143K3Y1sd2XVu9wtqxJRvybCfAetjUrMMco6r3v9qZTBeXiBZkS8JxWbcGJZyio8TrZtm6pkbzG8SYt1sxwNLh3Wx7to5pgiVFU",
            ),
            (
                "8080808080808080808080808080808080808080808080808080808080808080",
                "letter advice cage absurd amount doctor acoustic avoid \
                 letter advice cage absurd amount doctor acoustic avoid \
                 letter advice cage absurd amount doctor acoustic bless",
                "c0c519bd0e91a2ed54357d9d1ebef6f5af218a153624cf4f2da911a0ed8f7a09e2ef61af0aca007096df430022f7a2b6fb91661a9589097069720d015e4e982f",
                "xprv9s21ZrQH143K3CSnQNYC3MqAAqHwxeTLhDbhF43A4ss4ciWNmCY9zQGvAKUSqVUf2vPHBTSE1rB2pg4avopqSiLVzXEU8KziNnVPauTqLRo",
            ),
            (
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                "zoo zoo zoo zoo zoo zoo zoo zoo \
                 zoo zoo zoo zoo zoo zoo zoo zoo \
                 zoo zoo zoo zoo zoo zoo zoo vote",
                "dd48c104698c30cfe2b6142103248622fb7bb0ff692eebb00089b32d22484e1613912f0a5b694407be899ffd31ed3992c456cdf60f5d4564b8ba3f05a69890ad",
                "xprv9s21ZrQH143K2WFF16X85T2QCpndrGwx6GueB72Zf3AHwHJaknRXNF37ZmDrtHrrLSHvbuRejXcnYxoZKvRquTPyp2JiNG3XcjQyzSEgqCB",
            ),
            (
                "68a79eaca2324873eacc50cb9c6eca8cc68ea5d936f98787c60c7ebc74e6ce7c",
                "hamster diagram private dutch cause delay private meat \
                 slide toddler razor book happy fancy gospel tennis \
                 maple dilemma loan word shrug inflict delay length",
                "64c87cde7e12ecf6704ab95bb1408bef047c22db4cc7491c4271d170a1b213d20b385bc1588d9c7b38f1b39d415665b8a9030c9ec653d75e65f847d8fc1fc440",
                "xprv9s21ZrQH143K2XTAhys3pMNcGn261Fi5Ta2Pw8PwaVPhg3D8DWkzWQwjTJfskj8ofb81i9NP2cUNKxwjueJHHMQAnxtivTA75uUFqPFeWzk",
            ),
            (
                "9f6a2878b2520799a44ef18bc7df394e7061a224d2c33cd015b157d746869863",
                "panda eyebrow bullet gorilla call smoke muffin taste \
                 mesh discover soft ostrich alcohol speed nation flash \
                 devote level hobby quick inner drive ghost inside",
                "72be8e052fc4919d2adf28d5306b5474b0069df35b02303de8c1729c9538dbb6fc2d731d5f832193cd9fb6aeecbc469594a70e3dd50811b5067f3b88b28c3e8d",
                "xprv9s21ZrQH143K2WNnKmssvZYM96VAr47iHUQUTUyUXH3sAGNjhJANddnhw3i3y3pBbRAVk5M5qUGFr4rHbEWwXgX4qrvrceifCYQJbbFDems",
            ),
            (
                "066dca1a2bb7e8a1db2832148ce9933eea0f3ac9548d793112d9a95c9407efad",
                "all hour make first leader extend hole alien behind guard \
                 gospel lava path output census museum junior mass \
                 reopen famous sing advance salt reform",
                "26e975ec644423f4a4c4f4215ef09b4bd7ef924e85d1d17c4cf3f136c2863cf6df0a475045652c57eb5fb41513ca2a2d67722b77e954b4b3fc11f7590449191d",
                "xprv9s21ZrQH143K3rEfqSM4QZRVmiMuSWY9wugscmaCjYja3SbUD3KPEB1a7QXJoajyR2T1SiXU7rFVRXMV9XdYVSZe7JoUXdP4SRHTxsT1nzm",
            ),
            (
                "f585c11aec520db57dd353c69554b21a89b20fb0650966fa0a9d6f74fd989d8f",
                "void come effort suffer camp survey warrior heavy shoot \
                 primary clutch crush open amazing screen patrol group \
                 space point ten exist slush involve unfold",
                "01f5bced59dec48e362f2c45b5de68b9fd6c92c6634f44d6d40aab69056506f0e35524a518034ddc1192e1dacd32c1ed3eaa3c3b131c88ed8e7e54c49a5d0998",
                "xprv9s21ZrQH143K39rnQJknpH1WEPFJrzmAqqasiDcVrNuk926oizzJDDQkdiTvNPr2FYDYzWgiMiC63YmfPAa2oPyNB23r2g7d1yiK6WpqaQS",
            ),
        ];

        for (entropy_hex, expected_mnemonic, expected_seed_hex, expected_xprv) in vectors {
            let entropy: [u8; 32] = <[u8; 32]>::from_hex(entropy_hex).unwrap();
            let expected_seed: [u8; 64] = <[u8; 64]>::from_hex(expected_seed_hex).unwrap();

            let mnemonic = generate_mnemonic(&entropy).unwrap();
            let seed = generate_seed(&mnemonic, "TREZOR");
            let xprv = generate_master_xpriv(Network::Bitcoin, &seed).unwrap();

            assert_eq!(
                mnemonic.to_string(),
                expected_mnemonic,
                "entropy: {entropy_hex}"
            );
            assert_eq!(*seed, expected_seed, "entropy: {entropy_hex}");
            assert_eq!(xprv.to_string(), expected_xprv, "entropy: {entropy_hex}");
        }
    }

    /// Uses the first canonical BIP-39 mnemonic above with passphrase "TREZOR",
    /// with standard BIP-32 xprv/xpub version bytes.
    ///
    /// Provenance: unlike `bip39_test_vectors` above, the expected account keys here
    /// are *this implementation's own output* — a regression lock, not an oracle. No
    /// BIP publishes account keys for this 24-word mnemonic with passphrase "TREZOR",
    /// so there is nothing external to compare them to. This test therefore catches a
    /// *change* in our derivation, never a systematic error in it.
    ///
    /// To re-derive these independently: run BIP-32 CKDpriv over the seed in the first
    /// row of `bip39_test_vectors` for each path below, using any implementation other
    /// than this one. Nothing in-tree does that. For a test whose expected values this
    /// crate never produced, see
    /// `derivation::tests::derive_account_xpub_matches_bip32_vector_1`.
    #[test]
    fn account_derivation_functions_match_bip_vectors() {
        let mnemonic = Mnemonic::from_str(
            "abandon abandon abandon abandon abandon abandon \
             abandon abandon abandon abandon abandon abandon \
             abandon abandon abandon abandon abandon abandon \
             abandon abandon abandon abandon abandon art",
        )
        .unwrap();
        let vectors = [
            (
                Purpose::Bip44,
                "m/44'/0'/0'",
                "xprv9zRCqLPie7vUnPYfwoDE7tyA5djgC9RESmQJyob34Br2d1RufZpArD6BT5cSehpCWUh5XrUdHGjaBm89pj3uHjzv5utZ8WCrkxmwJpVfqeQ",
                "xpub6DQZEqvcUVUmzsd93pkEV2utdfaAbc95ozKunBzecXP1Vom4D78RQ1QfJPF9cLG5jawrpaopqg1PqTUEPwgkTLQ2H6WjFZ4hB9NBe2RG6Rx",
            ),
            (
                Purpose::Bip49,
                "m/49'/0'/0'",
                "xprv9xoF3hZStMxrXBDEDgwYNztdqq7YuiYV5JfDCVWAY8RW5AuFPqMi7XFn2VJYPHdC24GeJ9r89mzU3oXxkPRbdAnBPqmzWJ31RAqHDs93tGW",
                "xpub6BnbTD6LijX9jfHhKiUYk8qNPrx3KBGLSXaozsun6TxUwyEPwNfxfKaFsnRypS5H6UWwvSGGqp3YCVpboYfbXGoKKf33bDzZYHwJmXvT5pD",
            ),
            (
                Purpose::Bip84,
                "m/84'/0'/0'",
                "xprv9yUzThscUmTBgpV1qs6rbohKdrVVZ9XoUygFxWgh1i5JCEyEKk3uhfAe3HFe6BQvkqg51mx34hWbhKCAL593KAF2CfoY4dgUii1cvMSXnAi",
                "xpub6CULsDQWK91UuJZUwtdrxwe4BtKyxcFerCbrku6Ja3cH53JNsHNAFTV7tWzRhKnWqjmFz3x2sHqBu2rvGKEjDYxYf7MbQn2LE66NN17vZV6",
            ),
            (
                Purpose::Bip86,
                "m/86'/0'/0'",
                "xprv9zDGHfAoEomyADQ2n8ueQAU5zCqT7znLqhYUKznNm9F35xC6w7yzjja5t57qX1sLfnkGFbG2o7tFuSG96ykW7iZsFF3rNfQW9VdANSeeU3S",
                "xpub6DCchAhh5BLGNhUVtASemJQpYEfwXTWCCvU58PBzKUn1xkXFUfJFHXtZjMBqctFSAKDYoAHB94UwC5p9v8rcPoTQJYeZ6Xid6NEqpifeqWR",
            ),
        ];

        for (purpose, path, expected_account_xpriv, expected_account_xpub) in vectors {
            let (account_xpriv, account_xpub) =
                account_xpub_from_mnemonic(&mnemonic, "TREZOR", purpose, Network::Bitcoin, 0)
                    .unwrap();

            assert_eq!(
                account_xpriv.to_string(),
                expected_account_xpriv,
                "path: {path}"
            );
            assert_eq!(
                account_xpub.to_string(),
                expected_account_xpub,
                "path: {path}"
            );
        }

        let path = "m/48'/0'/0'/2'";
        let (account_xpriv, account_xpub) = account_multisig_xpub_from_mnemonic(
            &mnemonic,
            "TREZOR",
            Network::Bitcoin,
            0,
            ScriptType::P2wsh,
        )
        .unwrap();

        assert_eq!(
            account_xpriv.to_string(),
            "xprvA1frCZ7tJodAiMTf9EB41VzbBQLdrfNJxLeFhiDfgdaMabiWyXWXwxae4dZu2nvruG3Y7iVBeQHeow9LEFaBWHwbNxEigsbWvFZCwqa84oc",
            "path: {path}"
        );
        assert_eq!(
            account_xpub.to_string(),
            "xpub6EfCc4en9BBTvqY8FFi4NdwKjSB8G86AKZZrW6dHEy7LTQ3fX4pnVku7urz5FMqyMSwJjwxV2jHwifdanGWK2Amk34G4YnGtVFZrEdZuu31",
            "path: {path}"
        );
    }

    /// Two consecutive generations must differ — this is what separates a real
    /// CSPRNG from a fixed or mocked entropy source. The vector test above can't
    /// catch it, because it supplies the entropy itself.
    ///
    /// Compares `Mnemonic` values rather than their strings on purpose: bip39's
    /// `Debug` prints only the language, so a failure here cannot spill words
    /// into CI logs.
    #[test]
    fn consecutive_generations_use_fresh_entropy() {
        let first = generate_mnemonic(&generate_entropy().unwrap()).unwrap();
        let second = generate_mnemonic(&generate_entropy().unwrap()).unwrap();
        assert_ne!(
            first, second,
            "generate_entropy returned the same bytes twice"
        );
    }

    /// An account index >= 2^31 can't be hardened, so this is the one place in
    /// this module that reliably produces a real `bitcoin::bip32::Error`.
    #[test]
    fn out_of_range_account_is_a_bip32_error() {
        let mnemonic = generate_mnemonic(&[0u8; 32]).unwrap();
        let err = account_xpub_from_mnemonic(
            &mnemonic,
            "",
            Purpose::Bip49,
            Network::Testnet,
            0x8000_0000,
        )
        .expect_err("account index 2^31 cannot be hardened");
        assert!(matches!(err, VaultCoreError::Bip32(_)), "got {err:?}");
    }

    #[test]
    fn construct_vault_key() {
        let vault_key = VaultKey {
            label: String::from("test construct vault key"),
            source_type: KeySourceType::MebitThisDevice,
            fingerprint: Fingerprint::default(),
            xpub: Xpub {
                network: bitcoin::NetworkKind::Test,
                depth: 0,
                parent_fingerprint: Fingerprint::default(),
                child_number: bitcoin::bip32::ChildNumber::Hardened { index: 1 },
                public_key: PublicKey::from_str(
                    "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
                )
                .expect("error to create public key"),
                chain_code: ChainCode::from([0x00; 32]),
            },
            derivation_path: String::from("m/48'/1'/0'/2'"),
        };

        assert_eq!(vault_key.label, String::from("test construct vault key"));
        assert_eq!(vault_key.source_type, KeySourceType::MebitThisDevice);
        assert_eq!(vault_key.fingerprint, Fingerprint::default());
        assert_eq!(
            vault_key.xpub,
            Xpub {
                network: bitcoin::NetworkKind::Test,
                depth: 0,
                parent_fingerprint: Fingerprint::default(),
                child_number: bitcoin::bip32::ChildNumber::Hardened { index: 1 },
                public_key: PublicKey::from_str(
                    "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
                )
                .expect("error to create public key"),
                chain_code: ChainCode::from([0x00; 32]),
            }
        );
        assert_eq!(vault_key.derivation_path, String::from("m/48'/1'/0'/2'"));
    }
}
