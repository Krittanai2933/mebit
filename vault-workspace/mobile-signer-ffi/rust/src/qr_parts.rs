//! Animated QR parts for a wallet file: the text goes out as `ur:bytes` (Uniform Resources, the
//! `ur` crate) and comes back through `QrPartsReader`. The UR payload is a CBOR byte string of the
//! text's UTF-8 bytes.

use std::sync::Mutex;

/// Longest fragment in a part. 140 bytes keeps a part at about QR version 10 in alphanumeric mode.
const MAX_FRAGMENT: usize = 140;

/// The text as `ur:bytes` parts, uppercased so a QR uses its alphanumeric mode. Only the plain
/// fragments `1..=N` come back, so a screen that cycles them shows an exact "x / N". A text that
/// fits one fragment is one single-part UR.
#[uniffi::export]
pub fn wallet_qr_parts(text: String) -> Vec<String> {
    let cbor = cbor_bytes(text.as_bytes());
    let parts = if cbor.len() <= MAX_FRAGMENT {
        vec![ur::ur::encode(&cbor, &ur::Type::Bytes)]
    } else {
        let mut encoder =
            ur::Encoder::bytes(&cbor, MAX_FRAGMENT).expect("the payload is not empty");
        (0..encoder.fragment_count())
            .map(|_| encoder.next_part().expect("a fragment encodes"))
            .collect()
    };
    parts.into_iter().map(|p| p.to_uppercase()).collect()
}

/// What one scanned frame did to a `QrPartsReader`.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum QrScanProgress {
    /// Not a UR: the caller uses the text as the file text.
    NotUr,
    /// A UR part arrived: `received` of `expected` fragments are known.
    Part { received: u32, expected: u32 },
    /// Every fragment is in.
    Done { text: String },
    /// A UR of another type, a part of another message, or bytes that are not UTF-8.
    Refused { reason: String },
}

/// Collects the parts of one wallet-file UR. Make a new one to start over.
#[derive(uniffi::Object)]
pub struct QrPartsReader {
    decoder: Mutex<ur::Decoder>,
}

#[uniffi::export]
impl QrPartsReader {
    #[uniffi::constructor]
    pub fn new() -> std::sync::Arc<Self> {
        std::sync::Arc::new(Self {
            decoder: Mutex::new(ur::Decoder::default()),
        })
    }

    pub fn receive(&self, scanned: String) -> QrScanProgress {
        let lower = scanned.trim().to_ascii_lowercase();
        let Some(rest) = lower.strip_prefix("ur:") else {
            return QrScanProgress::NotUr;
        };
        let refused = |reason: &str| QrScanProgress::Refused {
            reason: reason.into(),
        };
        if rest.split('/').next() != Some("bytes") {
            return refused("a UR of another type");
        }
        let message = if rest.matches('/').count() == 1 {
            match ur::ur::decode(&lower) {
                Ok((_, payload)) => payload,
                Err(e) => return refused(&e.to_string()),
            }
        } else {
            let mut decoder = self.decoder.lock().unwrap_or_else(|e| e.into_inner());
            if let Err(e) = decoder.receive(&lower) {
                return refused(&e.to_string());
            }
            if !decoder.complete() {
                return QrScanProgress::Part {
                    received: decoder.resolved_fragment_count().unwrap_or(0) as u32,
                    expected: decoder.fragment_count() as u32,
                };
            }
            match decoder.message() {
                Ok(Some(message)) => message,
                _ => return refused("the parts do not make one message"),
            }
        };
        match unwrap_cbor_bytes(&message).map(String::from_utf8) {
            Some(Ok(text)) => QrScanProgress::Done { text },
            Some(Err(_)) => refused("the bytes are not UTF-8 text"),
            None => refused("the payload is not a byte string"),
        }
    }
}

/// The CBOR byte string (major type 2) of `bytes`.
fn cbor_bytes(bytes: &[u8]) -> Vec<u8> {
    let n = bytes.len();
    let mut out = match n {
        0..=23 => vec![0x40 | n as u8],
        24..=0xff => vec![0x58, n as u8],
        0x100..=0xffff => [&[0x59][..], &(n as u16).to_be_bytes()].concat(),
        _ => [&[0x5a][..], &(n as u32).to_be_bytes()].concat(),
    };
    out.extend_from_slice(bytes);
    out
}

/// The bytes inside a definite-length CBOR byte string that fills `cbor` exactly.
fn unwrap_cbor_bytes(cbor: &[u8]) -> Option<Vec<u8>> {
    let (&head, rest) = cbor.split_first()?;
    let (n, body) = match head {
        0x40..=0x57 => ((head & 0x1f) as usize, rest),
        0x58 => (*rest.first()? as usize, &rest[1..]),
        0x59 => (
            u16::from_be_bytes(rest.get(..2)?.try_into().ok()?) as usize,
            &rest[2..],
        ),
        0x5a => (
            u32::from_be_bytes(rest.get(..4)?.try_into().ok()?) as usize,
            &rest[4..],
        ),
        _ => return None,
    };
    (body.len() == n).then(|| body.to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::KeyNetwork;
    use crate::bsms::wallet_bsms_record;
    use crate::script::tests::{three_of_five, two_of_three};

    fn read_all(parts: &[String]) -> QrScanProgress {
        let reader = QrPartsReader::new();
        let mut last = QrScanProgress::NotUr;
        for part in parts {
            last = reader.receive(part.clone());
        }
        last
    }

    fn text(n: usize) -> String {
        (0..n).map(|i| char::from(b'a' + (i % 26) as u8)).collect()
    }

    fn done(text: &str) -> QrScanProgress {
        QrScanProgress::Done { text: text.into() }
    }

    #[test]
    fn a_two_of_three_record_is_four_parts_that_read_back() {
        let record = wallet_bsms_record(two_of_three(), KeyNetwork::Test).unwrap();
        let parts = wallet_qr_parts(record.clone());
        assert_eq!(parts.len(), 4, "{} bytes", record.len());
        assert!(parts[0].starts_with("UR:BYTES/1-4/"));
        assert_eq!(read_all(&parts), done(&record));
        let big = wallet_bsms_record(three_of_five(), KeyNetwork::Test).unwrap();
        assert_eq!(read_all(&wallet_qr_parts(big.clone())), done(&big));
    }

    #[test]
    fn parts_in_reverse_and_with_duplicates_read_back() {
        let t = text(500);
        let parts = wallet_qr_parts(t.clone());
        let reversed: Vec<String> = parts.iter().rev().cloned().collect();
        assert_eq!(read_all(&reversed), done(&t));
        let doubled: Vec<String> = parts.iter().flat_map(|p| [p.clone(), p.clone()]).collect();
        assert_eq!(read_all(&doubled), done(&t));
        let reader = QrPartsReader::new();
        assert_eq!(
            reader.receive(parts[0].clone()),
            QrScanProgress::Part {
                received: 1,
                expected: 4
            }
        );
        assert_eq!(
            reader.receive(parts[0].clone()),
            QrScanProgress::Part {
                received: 1,
                expected: 4
            }
        );
    }

    #[test]
    fn a_missing_part_is_made_up_by_mixed_parts() {
        let t = text(500);
        let cbor = cbor_bytes(t.as_bytes());
        let mut encoder = ur::Encoder::bytes(&cbor, MAX_FRAGMENT).unwrap();
        let n = encoder.fragment_count();
        let plain: Vec<String> = (0..n).map(|_| encoder.next_part().unwrap()).collect();
        let reader = QrPartsReader::new();
        for p in plain.iter().skip(1) {
            assert!(matches!(
                reader.receive(p.to_uppercase()),
                QrScanProgress::Part { .. }
            ));
        }
        let mut last = reader.receive(plain[1].clone());
        for _ in 0..50 {
            if matches!(last, QrScanProgress::Done { .. }) {
                break;
            }
            last = reader.receive(encoder.next_part().unwrap());
        }
        assert_eq!(last, done(&t));
    }

    #[test]
    fn a_short_text_is_one_single_part_ur() {
        let parts = wallet_qr_parts("hello".into());
        assert_eq!(parts.len(), 1);
        assert!(parts[0].starts_with("UR:BYTES/") && parts[0].matches('/').count() == 1);
        assert_eq!(read_all(&parts), done("hello"));
    }

    #[test]
    fn plain_text_is_not_a_ur() {
        let reader = QrPartsReader::new();
        assert_eq!(
            reader.receive("BSMS 1.0\nwsh(...)".into()),
            QrScanProgress::NotUr
        );
        assert_eq!(reader.receive("bc1qabc".into()), QrScanProgress::NotUr);
    }

    #[test]
    fn another_type_or_another_message_is_refused() {
        let reader = QrPartsReader::new();
        assert!(matches!(
            reader.receive("ur:crypto-psbt/1-2/lpadaxcfax".into()),
            QrScanProgress::Refused { .. }
        ));
        assert!(matches!(
            reader.receive("ur:crypto-psbt/iehsjyhspmwfwfia".into()),
            QrScanProgress::Refused { .. }
        ));
        let a = wallet_qr_parts(text(500));
        let b = wallet_qr_parts(text(520).to_uppercase());
        assert_eq!(a.len(), b.len());
        let reader = QrPartsReader::new();
        reader.receive(a[0].clone());
        assert!(matches!(
            reader.receive(b[1].clone()),
            QrScanProgress::Refused { .. }
        ));
    }

    #[test]
    fn bytes_that_are_not_utf8_are_refused() {
        let ur = ur::ur::encode(&cbor_bytes(&[0xff, 0xfe]), &ur::Type::Bytes);
        assert!(matches!(
            QrPartsReader::new().receive(ur),
            QrScanProgress::Refused { .. }
        ));
    }

    #[test]
    fn lowercase_input_reads() {
        let t = text(300);
        let parts: Vec<String> = wallet_qr_parts(t.clone())
            .iter()
            .map(|p| p.to_lowercase())
            .collect();
        assert_eq!(read_all(&parts), done(&t));
    }

    #[test]
    fn cbor_headers_round_trip_at_their_edges() {
        for n in [0, 23, 24, 255, 256, 65_535, 65_536] {
            let bytes = vec![7u8; n];
            assert_eq!(unwrap_cbor_bytes(&cbor_bytes(&bytes)), Some(bytes));
        }
        assert_eq!(unwrap_cbor_bytes(&[0x42, 1]), None);
    }
}
