use bitcoin::bip32::{Fingerprint, Xpub};

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

#[cfg(test)]
mod tests {
    use std::str::FromStr;

    use bitcoin::{bip32::ChainCode, secp256k1::PublicKey};

    use super::*;

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
