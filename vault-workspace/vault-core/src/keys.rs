use bitcoin::bip32::{ChildNumber, DerivationPath, Fingerprint, Xpub};

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

// vault-workspace/vault-core/src/keys.rs (เพิ่มเข้าไปในไฟล์เดิมจาก No.1)
use bip39::Mnemonic; // เพิ่ม dependency ใหม่ใน Cargo.toml — ยังไม่มีในนี้ตอนนี้
use bitcoin::Network;
use bitcoin::bip32::Xpriv;

#[derive(Debug)]
pub enum SeedGenerationError {
    EntropyFailure,
    InvalidWordCount,
    SeedFailure,
}

#[derive(Debug)]
pub enum DerivedError {
    DerivedPath,
    DerivedXprivError,
}

/// สร้าง mnemonic ใหม่จาก entropy ที่ปลอดภัย (OS CSPRNG) — ผู้เรียกต้องแสดงให้ผู้ใช้ backup
/// แล้วทำลายทิ้งจาก memory ทันทีหลังส่งต่อให้ secure storage (No.9.5) เก็บ ไม่ค้างไว้ใน state นานๆ
pub fn generate_entropy() -> Result<[u8; 32], SeedGenerationError> {
    // create entropy
    let mut entropy = [0u8; 32];
    getrandom::fill(&mut entropy).map_err(|_| SeedGenerationError::EntropyFailure)?;
    Ok(entropy)
}

pub fn generate_mnemonic(entropy: &[u8; 32]) -> Result<Mnemonic, SeedGenerationError> {
    // Entropy -> Mnemonic
    Mnemonic::from_entropy(entropy).map_err(|_| SeedGenerationError::InvalidWordCount)
}

pub fn generate_seed(
    mnemonic: &Mnemonic,
    passphrase: &str,
) -> Result<[u8; 64], SeedGenerationError> {
    // Mnemonic + Passphrase -> Seed
    Ok(mnemonic.to_seed(passphrase))
}

fn coin_type(network: Network) -> ChildNumber {
    match network {
        Network::Bitcoin => ChildNumber::from_hardened_idx(0).unwrap(), // mainnet
        _ => ChildNumber::from_hardened_idx(1).unwrap(),                // testnet/signet/regtest
    }
}

pub fn generate_master_xpriv(network: Network, seed: &[u8; 64]) -> Result<Xpriv, DerivedError> {
    Ok(Xpriv::new_master(network, seed).map_err(|_| DerivedError::DerivedXprivError)?)
}

/// แปลง mnemonic (+ passphrase optional) เป็น master seed แล้ว derive account-level xpub
/// ที่ path m/48'/coin_type'/account' (3 hardened element แรกตาม BIP-48 ก่อนถึง script_type ที่ No.2 จะ derive ต่อ)
pub fn account_xpub_from_mnemonic(
    mnemonic: &Mnemonic,
    passphrase: &str,
    network: Network,
    account: u32,
) -> Result<(Xpriv, Xpub), DerivedError> {
    let secp = bitcoin::secp256k1::Secp256k1::new();
    let seed = generate_seed(mnemonic, passphrase).map_err(|_| DerivedError::DerivedPath)?;

    let path = DerivationPath::from(vec![
        ChildNumber::from_hardened_idx(48).map_err(|_| DerivedError::DerivedPath)?,
        coin_type(network),
        ChildNumber::from_hardened_idx(account).map_err(|_| DerivedError::DerivedPath)?,
    ]);

    let master_xpriv =
        generate_master_xpriv(network, &seed).map_err(|_| DerivedError::DerivedXprivError)?;
    let account_xpriv = master_xpriv
        .derive_priv(&secp, &path)
        .map_err(|_| DerivedError::DerivedXprivError)?;

    let account_xpub = Xpub::from_priv(&secp, &account_xpriv);

    Ok((account_xpriv, account_xpub))
}

#[cfg(test)]
mod tests {
    use std::str::FromStr;

    use bitcoin::{bip32::ChainCode, hex::FromHex, secp256k1::PublicKey};

    use super::*;

    #[test]
    fn bip39_24_word_test_vectors() {
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
            let seed = generate_seed(&mnemonic, "TREZOR").unwrap();
            let xprv = generate_master_xpriv(Network::Bitcoin, &seed).unwrap();

            assert_eq!(
                mnemonic.to_string(),
                expected_mnemonic,
                "entropy: {entropy_hex}"
            );
            assert_eq!(seed, expected_seed, "entropy: {entropy_hex}");
            assert_eq!(xprv.to_string(), expected_xprv, "entropy: {entropy_hex}");
        }
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
