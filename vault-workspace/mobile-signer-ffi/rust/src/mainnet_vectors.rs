//! What mainnet must produce, written before the mainnet work: published test vectors for the
//! "abandon … about" words, which any other wallet derives the same way.

use crate::KeyNetwork;
use crate::address_type::AddressType;
use crate::hot_wallet::hot_receive_address;

fn about() -> Vec<String> {
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        .split(' ')
        .map(String::from)
        .collect()
}

fn receive(network: KeyNetwork, index: u32) -> String {
    hot_receive_address(
        about(),
        String::new(),
        network,
        AddressType::NativeSegwit,
        index,
    )
    .unwrap()
}

/// BIP84's own vectors, m/84'/0'/0'/0/0 and m/84'/0'/0'/0/1: coin type 0' on mainnet, so the
/// words restore to the same addresses in any other wallet.
#[test]
fn a_one_key_mainnet_wallet_derives_at_coin_type_0() {
    assert_eq!(
        receive(KeyNetwork::Main, 0),
        "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
    );
    assert_eq!(
        receive(KeyNetwork::Main, 1),
        "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g"
    );
}

/// Testnet keeps coin type 1', so every existing test wallet keeps its addresses.
#[test]
fn a_one_key_testnet_wallet_keeps_coin_type_1() {
    assert_eq!(
        receive(KeyNetwork::Test, 0),
        "tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl"
    );
}

/// The one gate every chain call passes takes mainnet.
#[test]
fn the_chain_gate_takes_mainnet() {
    assert!(crate::chain::check_network(KeyNetwork::Main).is_ok());
}
