//! Code-entry pairing: the THP specification's "Pairing phase", the only
//! method a Safe 7 supports for a new host.
//!
//! The Safe 7 shows a six-digit code and the user types it on the host. Both
//! sides run CPace (draft-irtf-cfrg-cpace, the X25519 instance), with that code
//! as the password and the Noise handshake hash as the channel identifier, so
//! a man in the middle who can't see the screen can't finish the exchange.
//! The host then checks that the device committed to its secret before the
//! code was shown, and that the code really derives from that secret.
//!
//! Ported from Trezor's own host, trezorlib (`python/src/trezorlib/thp/
//! {cpace,curve25519,pairing}.py` at trezor-firmware `c33f81554a51`). The
//! tests pin outputs computed by that code and Trezor's firmware test vectors.
//!
//! Elligator2 has no public Rust implementation in a vetted crate
//! (curve25519-dalek keeps its own `pub(crate)`), so [`elligator2`] is a
//! straight-line port of trezorlib's, RFC 9380 §G.2.1 `ell2-opt`, on
//! `crypto-bigint`'s constant-time Montgomery arithmetic.

use crypto_bigint::modular::ConstMontyForm;
use crypto_bigint::{CtEq, CtSelect, U256, const_monty_params};
use sha2::{Digest, Sha256, Sha512};
use x25519_dalek::x25519;
use zeroize::{Zeroize, Zeroizing};

use super::TrezorError;
use super::protos::thp::ThpPairingMethod;

/// Digits in the code the Safe 7 shows.
pub const CODE_LEN: usize = 6;
/// Bytes in the host's challenge. The state machine in the specification
/// (state HP2) and trezorlib both use 16; the proto comment saying 32 is stale.
pub(super) const CHALLENGE_LEN: usize = 16;

const_monty_params!(
    Curve25519Prime,
    U256,
    "7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed",
    "p = 2^255 - 19"
);
type Fe = ConstMontyForm<Curve25519Prime, { U256::LIMBS }>;

/// Curve25519's Montgomery coefficient A ("J" in RFC 9380).
const J: u64 = 486_662;
/// sqrt(-1) mod p.
const C3: U256 =
    U256::from_be_hex("2b8324804fc1df0b2b4d00993dfbd7a72f431806ad2fe478c4ee1b274a0ea0b0");
/// (p - 5) / 8.
const C4: U256 =
    U256::from_be_hex("0ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffd");
/// p - 2, for inversion by Fermat (`inv0` in RFC 9380).
const P_MINUS_2: U256 =
    U256::from_be_hex("7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffeb");

/// RFC 9380 `map_to_curve_elligator2_curve25519`, returning the u-coordinate
/// only, as Trezor uses it. The input is decoded as an RFC 7748 u-coordinate
/// (top bit dropped, little-endian, reduced mod p). Constant time: the only
/// branches are the constant-time selects.
pub fn elligator2(input: &[u8; 32]) -> [u8; 32] {
    let mut decoded = Zeroizing::new(*input);
    decoded[31] &= 0x7f;
    let u = Fe::new(&U256::from_le_slice(decoded.as_ref()));

    let j = Fe::new(&U256::from_u64(J));
    let tv1 = u.square().double(); //                       2u²
    let xd = tv1 + Fe::ONE; //                              1 + 2u², never zero
    let x1n = j.neg(); //                                   x1 = -J / xd
    let tv2 = xd.square();
    let gxd = tv2 * xd;
    let gx1 = ((j * tv1 * x1n) + tv2) * x1n;
    let tv3 = gxd.square();
    let tv2 = tv3.square();
    let tv3 = tv3 * gxd * gx1;
    let tv2 = tv2 * tv3;
    let y11 = tv2.pow(&C4) * tv3;
    let y12 = y11 * Fe::new(&C3);
    let e1 = (y11.square() * gxd).ct_eq(&gx1);
    let y1 = y12.ct_select(&y11, e1); //                    a square root of g(x1), if one exists
    let x2n = x1n * tv1; //                                 x2 = 2u² x1
    let e3 = (y1.square() * gxd).ct_eq(&gx1); //            is g(x1) square?
    let xn = x2n.ct_select(&x1n, e3);
    let x = xn * xd.pow(&P_MINUS_2);

    let mut output = [0u8; 32];
    output.copy_from_slice(x.retrieve().to_le_bytes().as_ref());
    output
}

/// CPace's generator: `elligator2(SHA-512(generator_string)[..32])`.
pub(super) fn generator(prs: &[u8], ci: &[u8], sid: &[u8]) -> [u8; 32] {
    let mut hashed = Sha512::digest(generator_string(prs, ci, sid).as_slice());
    let mut pregenerator = Zeroizing::new([0u8; 32]);
    pregenerator.copy_from_slice(&hashed[..32]);
    hashed.as_mut_slice().zeroize();
    elligator2(&pregenerator)
}

/// trezorlib `cpace._generator_string`: the length-prefixed concatenation of
/// "CPace255", the password, zero padding to fill SHA-512's first block,
/// the channel identifier and the session identifier.
fn generator_string(prs: &[u8], ci: &[u8], sid: &[u8]) -> Zeroizing<Vec<u8>> {
    const DSI: &[u8] = b"CPace255";
    const SHA512_BLOCK_LEN: usize = 128;
    // Each length is a one-byte LEB128, which every input here fits.
    let zero_padding = SHA512_BLOCK_LEN.saturating_sub(1 + DSI.len() + 1 + prs.len() + 1);
    let mut string = Zeroizing::new(Vec::with_capacity(
        SHA512_BLOCK_LEN + ci.len() + sid.len() + 3,
    ));
    for field in [DSI, prs, &[0u8; SHA512_BLOCK_LEN][..zero_padding], ci, sid] {
        debug_assert!(field.len() < 0x80);
        string.push(field.len() as u8);
        string.extend_from_slice(field);
    }
    string
}

/// What the host sends in `ThpCodeEntryCpaceHostTag`.
pub(super) struct HostTag {
    pub(super) public_key: [u8; 32],
    pub(super) tag: [u8; 32],
}

/// The host's half of CPace, for `code` typed by the user, with a fresh
/// random private key. Refuses a device key that makes the shared secret
/// zero, i.e. a low-order point.
pub(super) fn host_tag(
    code: &str,
    handshake_hash: &[u8; 32],
    trezor_public_key: &[u8; 32],
) -> Result<HostTag, TrezorError> {
    let mut private_key = Zeroizing::new([0u8; 32]);
    getrandom::fill(private_key.as_mut())
        .map_err(|_| TrezorError::PairingFailed("no randomness for the pairing key"))?;
    host_tag_with_key(code, handshake_hash, trezor_public_key, &private_key)
}

fn host_tag_with_key(
    code: &str,
    handshake_hash: &[u8; 32],
    trezor_public_key: &[u8; 32],
    private_key: &[u8; 32],
) -> Result<HostTag, TrezorError> {
    check_code(code)?;
    let generator = generator(code.as_bytes(), handshake_hash, b"");
    let public_key = x25519(*private_key, generator);
    let shared = Zeroizing::new(x25519(*private_key, *trezor_public_key));
    // OR-ing every byte, rather than comparing, keeps this branch-free.
    if shared.iter().fold(0u8, |acc, byte| acc | byte) == 0 {
        return Err(TrezorError::PairingFailed(
            "the Trezor sent an invalid pairing key",
        ));
    }
    Ok(HostTag {
        public_key,
        tag: Sha256::digest(&shared[..]).into(),
    })
}

/// After the device revealed its secret: it must be the one it committed to
/// before showing the code, and the code must derive from it (trezorlib
/// `CodeEntry.send_code`).
pub(super) fn verify_code_entry(
    code: &str,
    handshake_hash: &[u8; 32],
    challenge: &[u8; CHALLENGE_LEN],
    commitment: &[u8],
    secret: &[u8],
) -> Result<(), TrezorError> {
    check_code(code)?;
    if Sha256::digest(secret).as_slice() != commitment {
        return Err(TrezorError::PairingFailed(
            "the Trezor's secret does not match its commitment",
        ));
    }
    if expected_code(handshake_hash, secret, challenge).as_str() != code {
        return Err(TrezorError::PairingFailed(
            "the code does not derive from the Trezor's secret",
        ));
    }
    Ok(())
}

/// `SHA-256(CodeEntry ‖ handshake_hash ‖ secret ‖ challenge)`, as a
/// big-endian integer, mod 10^6, zero-padded to six digits.
fn expected_code(handshake_hash: &[u8; 32], secret: &[u8], challenge: &[u8]) -> Zeroizing<String> {
    let hash = Sha256::new()
        .chain_update([ThpPairingMethod::CodeEntry as u8])
        .chain_update(handshake_hash)
        .chain_update(secret)
        .chain_update(challenge)
        .finalize();
    let value = hash
        .iter()
        .fold(0u64, |acc, &byte| (acc * 256 + u64::from(byte)) % 1_000_000);
    Zeroizing::new(format!("{value:06}"))
}

/// Exactly six ASCII digits.
pub(super) fn check_code(code: &str) -> Result<(), TrezorError> {
    if code.len() == CODE_LEN && code.bytes().all(|byte| byte.is_ascii_digit()) {
        Ok(())
    } else {
        Err(TrezorError::PairingCodeInvalid)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bytes32(hex: &str) -> [u8; 32] {
        let mut out = [0u8; 32];
        for (i, byte) in out.iter_mut().enumerate() {
            *byte = u8::from_str_radix(&hex[2 * i..2 * i + 2], 16).unwrap();
        }
        out
    }

    fn hex(bytes: &[u8]) -> String {
        bytes.iter().map(|b| format!("{b:02x}")).collect()
    }

    /// trezor-firmware `core/tests/test_trezor.crypto.elligator2.py`, itself
    /// from elligator.org's `curve25519_direct.vec`.
    const FIRMWARE_VECTORS: [(&str, &str); 10] = [
        (
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0000000000000000000000000000000000000000000000000000000000000000",
        ),
        (
            "66665895c5bc6e44ba8d65fd9307092e3244bf2c18877832bd568cb3a2d38a12",
            "04d44290d13100b2c25290c9343d70c12ed4813487a07ac1176daa5925e7975e",
        ),
        (
            "673a505e107189ee54ca93310ac42e4545e9e59050aaac6f8b5f64295c8ec02f",
            "242ae39ef158ed60f20b89396d7d7eef5374aba15dc312a6aea6d1e57cacf85e",
        ),
        (
            "990b30e04e1c3620b4162b91a33429bddb9f1b70f1da6e5f76385ed3f98ab131",
            "998e98021eb4ee653effaa992f3fae4b834de777a953271baaa1fa3fef6b776e",
        ),
        (
            "341a60725b482dd0de2e25a585b208433044bc0a1ba762442df3a0e888ca063c",
            "683a71d7fca4fc6ad3d4690108be808c2e50a5af3174486741d0a83af52aeb01",
        ),
        (
            "922688fa428d42bc1fa8806998fbc5959ae801817e85a42a45e8ec25a0d7541a",
            "696f341266c64bcfa7afa834f8c34b2730be11c932e08474d1a22f26ed82410b",
        ),
        (
            "0d3b0eb88b74ed13d5f6a130e03c4ad607817057dc227152827c0506a538bb3a",
            "0b00df174d9fb0b6ee584d2cf05613130bad18875268c38b377e86dfefef177f",
        ),
        (
            "01a3ea5658f4e00622eeacf724e0bd82068992fae66ed2b04a8599be16662e35",
            "7ae4c58bc647b5646c9f5ae4c2554ccbf7c6e428e7b242a574a5a9c293c21f7e",
        ),
        (
            "1d991dff82a84afe97874c0f03a60a56616a15212fbe10d6c099aa3afcfabe35",
            "f81f235696f81df90ac2fc861ceee517bff611a394b5be5faaee45584642fb0a",
        ),
        (
            "185435d2b005a3b63f3187e64a1ef3582533e1958d30e4e4747b4d1d3376c728",
            "f938b1b320abb0635930bd5d7ced45ae97fa8b5f71cc21d87b4c60905c125d34",
        ),
    ];

    #[test]
    fn elligator2_matches_the_firmware_vectors() {
        for (input, output) in FIRMWARE_VECTORS {
            assert_eq!(hex(&elligator2(&bytes32(input))), output, "input {input}");
        }
    }

    /// Edge cases, with outputs computed by trezorlib's `curve25519.elligator2`:
    /// inputs at or above p and with the top bit set must decode as RFC 7748
    /// says, and u and -u map to the same point.
    #[test]
    fn elligator2_decodes_inputs_as_rfc7748_does() {
        let one = "9cdb525555555555555555555555555555555555555555555555555555555555";
        for input in [
            "0100000000000000000000000000000000000000000000000000000000000000", // 1
            "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f", // p + 1
            "0100000000000000000000000000000000000000000000000000000000000080", // 1, top bit set
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f", // p - 1
        ] {
            assert_eq!(hex(&elligator2(&bytes32(input))), one, "input {input}");
        }
        assert_eq!(
            hex(&elligator2(&[0xff; 32])),
            "1e5942dd97c756040d27755f1e5b11349cd47d796c45d07052f7e5b11541c349"
        );
    }

    /// trezorlib `python/tests/test_cpace.py`.
    #[test]
    fn generator_matches_trezorlib() {
        let ci = b"oc\x0bB_responder\x0bA_initiator";
        let sid = [
            0x7e, 0x4b, 0x47, 0x91, 0xd6, 0xa8, 0xef, 0x01, 0x9b, 0x93, 0x6c, 0x79, 0xfb, 0x7f,
            0x2c, 0x57,
        ];
        let string = generator_string(b"Password", ci, &sid);
        assert_eq!(
            hex(&string),
            "0843506163653235350850617373776f72646d000000000000000000\
             00000000000000000000000000000000000000000000000000000000\
             00000000000000000000000000000000000000000000000000000000\
             00000000000000000000000000000000000000000000000000000000\
             000000000000000000000000000000001a6f630b425f726573706f6e\
             6465720b415f696e69746961746f72107e4b4791d6a8ef019b936c79\
             fb7f2c57"
        );
        assert_eq!(
            hex(&generator(b"Password", ci, &sid)),
            "64e8099e3ea682cfdc5cb665c057ebb514d06bf23ebc9f743b51b82242327074"
        );
    }

    /// Code entry end to end, with fixed keys, against trezorlib's `cpace()`
    /// and the checks of `CodeEntry.send_code`.
    #[test]
    fn host_side_matches_trezorlib() {
        let code = "042137";
        let handshake_hash: [u8; 32] = std::array::from_fn(|i| i as u8);
        let trezor_public_key =
            bytes32("8e55f71abb13f8b28c7bca80bfe3f3e99b2ce60c8b45051a37b7984b4afa765c");
        assert_eq!(
            hex(&generator(code.as_bytes(), &handshake_hash, b"")),
            "4e2ff044d5116cae5248c89020f396e9bda1df1a802efb5513b202075b09ba1e"
        );

        let host =
            host_tag_with_key(code, &handshake_hash, &trezor_public_key, &[0x42; 32]).unwrap();
        assert_eq!(
            hex(&host.public_key),
            "b524ba554cd8398c6a5198839c3da5eb507d686b568bc7f56e886ab47ec1641b"
        );
        assert_eq!(
            hex(&host.tag),
            "1d69af05e22f718da0526b490e8ad266bcdd49b9abebf7a7c8f782280903d7f2"
        );

        let secret: Vec<u8> = (100..116).collect();
        let challenge: [u8; 16] = std::array::from_fn(|i| 200 + i as u8);
        let commitment =
            bytes32("076f819029f2523b31e8dac8270fa3d2ce28c9fcb9eadd96369002499b02e516");
        assert_eq!(
            expected_code(&handshake_hash, &secret, &challenge).as_str(),
            "374748"
        );
        verify_code_entry("374748", &handshake_hash, &challenge, &commitment, &secret).unwrap();
    }

    #[test]
    fn code_entry_checks_refuse_mismatches() {
        let handshake_hash = [7u8; 32];
        let challenge = [9u8; 16];
        let secret = [5u8; 16];
        let commitment: [u8; 32] = Sha256::digest(secret).into();
        let code = expected_code(&handshake_hash, &secret, &challenge);
        verify_code_entry(&code, &handshake_hash, &challenge, &commitment, &secret).unwrap();

        let wrong_code = if code.as_str() == "000000" {
            "000001"
        } else {
            "000000"
        };
        let other_secret = [6u8; 16];
        let other_commitment: [u8; 32] = Sha256::digest(other_secret).into();
        let other_challenge = [8u8; 16];
        let other_hash = [1u8; 32];
        for (case, result) in [
            (
                // The code and the secret agree; only the commitment is off.
                "committed to another secret",
                verify_code_entry(
                    &code,
                    &handshake_hash,
                    &challenge,
                    &other_commitment,
                    &secret,
                ),
            ),
            (
                "wrong code",
                verify_code_entry(
                    wrong_code,
                    &handshake_hash,
                    &challenge,
                    &commitment,
                    &secret,
                ),
            ),
            (
                "secret not committed",
                verify_code_entry(
                    &code,
                    &handshake_hash,
                    &challenge,
                    &commitment,
                    &other_secret,
                ),
            ),
            (
                "another challenge",
                verify_code_entry(
                    &code,
                    &handshake_hash,
                    &other_challenge,
                    &commitment,
                    &secret,
                ),
            ),
            (
                "another channel",
                verify_code_entry(&code, &other_hash, &challenge, &commitment, &secret),
            ),
        ] {
            assert!(
                matches!(result, Err(TrezorError::PairingFailed(_))),
                "{case}"
            );
        }
    }

    #[test]
    fn codes_are_exactly_six_digits() {
        for good in ["000000", "123456", "999999"] {
            assert!(check_code(good).is_ok());
        }
        for bad in [
            "",
            "12345",
            "1234567",
            "12345a",
            " 12345",
            "12345 ",
            "١٢٣٤٥٦",
            "12 456",
        ] {
            assert!(
                matches!(check_code(bad), Err(TrezorError::PairingCodeInvalid)),
                "{bad:?}"
            );
        }
        let hash = [0u8; 32];
        assert!(matches!(
            host_tag_with_key("12345", &hash, &[9; 32], &[1; 32]),
            Err(TrezorError::PairingCodeInvalid)
        ));
    }

    /// A low-order device key makes every shared secret zero, whatever the
    /// code: that would let a device skip code entry, so it is refused.
    #[test]
    fn refuses_a_low_order_trezor_key() {
        let hash = [3u8; 32];
        for low_order in [[0u8; 32], {
            let mut one = [0u8; 32];
            one[0] = 1;
            one
        }] {
            assert!(matches!(
                host_tag_with_key("123456", &hash, &low_order, &[0x42; 32]),
                Err(TrezorError::PairingFailed(_))
            ));
        }
    }
}
