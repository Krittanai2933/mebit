//! Reading and writing a several-key wallet as a BSMS record (BIP-129), a bare descriptor, or the
//! app's own older `.bsms` layout. Whatever is read leaves as the stored form `script.rs` parses:
//! `wsh(sortedmulti(m,[fp/48'/1'/0'/2']tpub,...))`, no key suffix, no checksum. Public data only.

use std::str::FromStr;

use miniscript::Descriptor;
use miniscript::descriptor::{DescriptorPublicKey, checksum::verify_checksum};

use crate::script::{WalletShape, wallet_describe};
use crate::{KeyNetwork, MobileSignerError};

/// Where the file came from.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum WalletFileFormat {
    /// A BIP-129 record.
    Bsms,
    /// The `.bsms` this app wrote before it wrote BIP-129 records.
    AppBsms,
    /// One line, a descriptor.
    Descriptor,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct WalletImport {
    /// The stored form, as the app's `walletDescriptor` writes it.
    pub descriptor: String,
    pub shape: WalletShape,
    pub format: WalletFileFormat,
    /// The first address the file states, if it states one. It matched `shape.first_address`.
    pub stated_address: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum WalletFileRead {
    Read {
        wallet: WalletImport,
    },
    /// The keys belong to the other network.
    WrongNetwork,
    /// A descriptor of another kind, or one this app does not take: `found` names it.
    Unsupported {
        found: String,
    },
    /// The file states a first address its keys do not give. Never import it.
    AddressMismatch {
        stated: String,
        derived: String,
    },
    /// Anything else. For logs, in English.
    Unreadable {
        reason: String,
    },
}

const FORMS: [&str; 4] = ["", "/**", "/<0;1>/*", "/0/*"];
const RECORD_HEADER: &str = "BSMS 1.0";
const PATHS: &str = "/0/*,/1/*";

fn unreadable(reason: impl Into<String>) -> WalletFileRead {
    WalletFileRead::Unreadable {
        reason: reason.into(),
    }
}

/// Rewrites every `[origin]key` of `text`: the fingerprint lowercase, `h` as `'`, and the suffix after
/// the key replaced by what `suffix` returns for the old one. Text outside keys is copied as is.
/// Returns the new text and how many keys it held.
fn rewrite_keys<E>(
    text: &str,
    mut suffix: impl FnMut(&str) -> Result<String, E>,
) -> Result<(String, usize), E> {
    let mut out = String::with_capacity(text.len());
    let mut count = 0;
    let mut rest = text;
    while let Some(open) = rest.find('[') {
        out.push_str(&rest[..open]);
        let after = &rest[open + 1..];
        let Some(close) = after.find(']') else {
            // No end to the origin: leave it for the descriptor parser to refuse.
            out.push_str(&rest[open..]);
            return Ok((out, count));
        };
        let origin = &after[..close];
        let (fp, path) = origin.split_once('/').unwrap_or((origin, ""));
        out.push('[');
        out.push_str(&fp.to_lowercase());
        if !path.is_empty() {
            out.push('/');
            out.push_str(&path.replace(['h', 'H'], "'"));
        }
        out.push(']');
        let tail = &after[close + 1..];
        let key_end = tail
            .find(|c: char| !c.is_ascii_alphanumeric())
            .unwrap_or(tail.len());
        out.push_str(&tail[..key_end]);
        let tail = &tail[key_end..];
        let suffix_end = tail.find([',', ')']).unwrap_or(tail.len());
        out.push_str(&suffix(&tail[..suffix_end])?);
        rest = &tail[suffix_end..];
        count += 1;
    }
    out.push_str(rest);
    Ok((out, count))
}

/// The kind of descriptor `text` starts as, for a refusal.
fn kind_of(text: &str) -> Option<&'static str> {
    [
        ("wsh(sortedmulti(", "wsh(sortedmulti)"),
        ("wsh(multi(", "wsh(multi)"),
        ("sh(wsh(", "sh(wsh)"),
        ("sh(wpkh(", "sh(wpkh)"),
        ("wpkh(", "wpkh"),
        ("pkh(", "pkh"),
        ("tr(", "tr"),
        ("wsh(", "wsh(miniscript)"),
        ("sh(", "sh"),
    ]
    .into_iter()
    .find(|(prefix, _)| text.starts_with(prefix))
    .map(|(_, name)| name)
}

/// A descriptor line, a template or the stored form, to the stored form and its shape.
fn read_descriptor(
    line: &str,
    network: KeyNetwork,
) -> Result<(String, WalletShape), WalletFileRead> {
    let line = line.trim();
    if line.contains('#') && verify_checksum(line).is_err() {
        return Err(unreadable("the descriptor's checksum does not verify"));
    }
    let body = line.split('#').next().unwrap_or(line);
    match kind_of(body) {
        Some("wsh(sortedmulti)") => {}
        Some(other) => {
            return Err(WalletFileRead::Unsupported {
                found: other.into(),
            });
        }
        None => return Err(unreadable("not a descriptor this app knows")),
    }
    let mut forms = Vec::new();
    let (stored, keys) = rewrite_keys(body, |old| {
        if !FORMS.contains(&old) {
            return Err(WalletFileRead::Unsupported {
                found: format!("key suffix {old}"),
            });
        }
        forms.push(old.to_string());
        Ok(String::new())
    })?;
    if keys == 0 {
        return Err(unreadable("no key with an origin [fingerprint/path]"));
    }
    if forms.windows(2).any(|w| w[0] != w[1]) {
        return Err(WalletFileRead::Unsupported {
            found: "mixed key suffixes".into(),
        });
    }
    let wrong = match network {
        KeyNetwork::Test => "xpub",
        KeyNetwork::Main => "tpub",
    };
    // Each key follows its origin's closing bracket, so its first letters name its network. A search of the
    // whole text would also find "xpub" inside a test key's random letters.
    if stored.split(']').skip(1).any(|key| key.starts_with(wrong)) {
        return Err(WalletFileRead::WrongNetwork);
    }
    match wallet_describe(stored.clone(), network) {
        Ok(shape) => Ok((stored, shape)),
        // shortcut: script.rs words its refusals as text, so these three are told apart by their wording.
        Err(e) => {
            let why = e.to_string();
            let found = if why.contains("same key") {
                Some("duplicate keys")
            } else if why.contains("one key")
                || why.contains("same phrase")
                || why.contains("phrase of key")
            {
                Some("two keys from one phrase")
            } else if why.contains("at most") {
                Some("more than 15 keys")
            } else {
                None
            };
            Err(match found {
                Some(found) => WalletFileRead::Unsupported {
                    found: found.into(),
                },
                None => unreadable(why),
            })
        }
    }
}

/// The address after `receive[0]=` on the app's comment line.
fn app_stated_address(lines: &[&str]) -> Option<String> {
    lines.iter().find_map(|l| {
        let (_, after) = l.strip_prefix('#')?.split_once("receive[0]=")?;
        after.split_whitespace().next().map(str::to_string)
    })
}

fn read(text: &str, network: KeyNetwork) -> Result<WalletImport, WalletFileRead> {
    let lines: Vec<&str> = text.trim().lines().map(str::trim).collect();
    let (descriptor_line, format, stated) = match lines.as_slice() {
        [] | [""] => return Err(unreadable("the file is empty")),
        [header, descriptor, rest @ ..] if *header == RECORD_HEADER => match rest {
            [paths, ..] if *paths == PATHS || *paths == "No path restrictions" => (
                *descriptor,
                WalletFileFormat::Bsms,
                rest.get(1).map(|a| a.to_string()),
            ),
            [paths, ..] if paths.starts_with('/') => {
                return Err(WalletFileRead::Unsupported {
                    found: format!("path restriction {paths}"),
                });
            }
            _ => (
                *descriptor,
                WalletFileFormat::AppBsms,
                app_stated_address(rest),
            ),
        },
        // A descriptor file. Other wallets add "#" comment lines, and the single-chain forms after the main one.
        _ => match lines.iter().find(|l| !l.is_empty() && !l.starts_with('#')) {
            Some(first) => (*first, WalletFileFormat::Descriptor, None),
            None => return Err(unreadable("no descriptor line")),
        },
    };
    let (descriptor, shape) = read_descriptor(descriptor_line, network)?;
    if let Some(stated) = &stated
        && !stated.eq_ignore_ascii_case(&shape.first_address)
    {
        return Err(WalletFileRead::AddressMismatch {
            stated: stated.clone(),
            derived: shape.first_address,
        });
    }
    Ok(WalletImport {
        descriptor,
        shape,
        format,
        stated_address: stated,
    })
}

/// What a wallet file holds: always an outcome, never an error. Takes a BIP-129 record, the app's older
/// `.bsms`, or one descriptor line (checksum optional, verified when present), with a key suffix of
/// none, the BIP-129 double star, the BIP-389 multipath, or the single-chain receive form.
#[uniffi::export]
pub fn wallet_file_read(text: String, network: KeyNetwork) -> WalletFileRead {
    match read(&text, network) {
        Ok(wallet) => WalletFileRead::Read { wallet },
        Err(outcome) => outcome,
    }
}

fn with_suffix(
    descriptor: &str,
    network: KeyNetwork,
    suffix: &str,
) -> Result<(String, WalletShape), MobileSignerError> {
    let shape = wallet_describe(descriptor.to_string(), network)?;
    if kind_of(descriptor) != Some("wsh(sortedmulti)") {
        return Err(MobileSignerError::InvalidPsbtJson(
            "descriptor: only a wsh(sortedmulti) wallet is written as a record".into(),
        ));
    }
    let (text, _) = rewrite_keys::<std::convert::Infallible>(
        descriptor.split('#').next().unwrap_or(descriptor),
        |_| Ok(suffix.to_string()),
    )
    .unwrap_or_else(|e| match e {});
    Ok((text, shape))
}

/// The BIP-129 record of a stored-form wallet: header, template with the BIP-129 key suffix, the path restriction line, receive address 0.
#[uniffi::export]
pub fn wallet_bsms_record(
    descriptor: String,
    network: KeyNetwork,
) -> Result<String, MobileSignerError> {
    let (template, shape) = with_suffix(&descriptor, network, "/**")?;
    Ok([RECORD_HEADER, &template, PATHS, &shape.first_address].join("\n"))
}

/// The stored form with the BIP-389 multipath suffix on each key and its BIP-380 checksum.
#[uniffi::export]
pub fn wallet_descriptor_text(
    descriptor: String,
    network: KeyNetwork,
) -> Result<String, MobileSignerError> {
    let (text, _) = with_suffix(&descriptor, network, "/<0;1>/*")?;
    let parsed = Descriptor::<DescriptorPublicKey>::from_str(&text)
        .map_err(|e| MobileSignerError::InvalidPsbtJson(format!("descriptor: {e}")))?;
    Ok(parsed.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::address_type::AddressType;
    use crate::script::tests::{multisig, one_key, two_of_three};

    const NET: KeyNetwork = KeyNetwork::Test;

    fn read_ok(text: &str) -> WalletImport {
        match wallet_file_read(text.into(), NET) {
            WalletFileRead::Read { wallet } => wallet,
            other => panic!("{other:?}"),
        }
    }

    fn with(descriptor: &str, suffix: &str) -> String {
        with_suffix(descriptor, NET, suffix).unwrap().0
    }

    fn address(descriptor: &str) -> String {
        wallet_describe(descriptor.into(), NET)
            .unwrap()
            .first_address
    }

    /// As the app's `walletBsms` writes it.
    fn app_bsms(descriptor: &str, address: &str) -> String {
        let shape = wallet_describe(descriptor.into(), NET).unwrap();
        let paths: Vec<String> = shape
            .keys
            .iter()
            .map(|k| format!("{} {}", k.fingerprint, k.path))
            .collect();
        format!(
            "BSMS 1.0\n{descriptor}\n{}\n/0/*,/1/*\n# mebit personal_custody · receive[0]={address}\n# export=file · not QR",
            paths.join("\n")
        )
    }

    #[test]
    fn every_suffix_form_reads_as_the_stored_form() {
        let stored = two_of_three();
        for suffix in FORMS {
            let got = read_ok(&with(&stored, suffix));
            assert_eq!(got.descriptor, stored, "suffix {suffix:?}");
            assert_eq!(got.format, WalletFileFormat::Descriptor);
            assert_eq!(got.stated_address, None);
            assert_eq!(got.shape.needed, 2);
        }
    }

    #[test]
    fn a_descriptor_file_with_comments_reads_its_first_descriptor() {
        let stored = two_of_three();
        // The shape another wallet's export takes: comment lines, the multipath line, then the receive-only line.
        let file = format!(
            "# Receive and change descriptor (BIP-389)\n{}\n\n# Receive descriptor\n{}\n",
            wallet_descriptor_text(stored.clone(), NET).unwrap(),
            with(&stored, "/0/*"),
        );
        let got = read_ok(&file);
        assert_eq!(
            (got.descriptor.as_str(), got.format),
            (stored.as_str(), WalletFileFormat::Descriptor)
        );
        assert!(matches!(
            wallet_file_read("# a comment only\n\n".into(), NET),
            WalletFileRead::Unreadable { .. }
        ));
    }

    #[test]
    fn a_bip129_record_and_the_old_app_layout_read() {
        let stored = two_of_three();
        let first = address(&stored);
        let record = wallet_bsms_record(stored.clone(), NET).unwrap();
        let got = read_ok(&record);
        assert_eq!(
            (got.descriptor.as_str(), got.format),
            (stored.as_str(), WalletFileFormat::Bsms)
        );
        assert_eq!(got.stated_address, Some(first.clone()));
        assert!(record.starts_with("BSMS 1.0\nwsh(sortedmulti(2,["));
        assert!(record.ends_with(&format!("\n/0/*,/1/*\n{first}")));
        let crlf = format!("  {}  \r\n", record.replace('\n', "\r\n"));
        assert_eq!(read_ok(&crlf).descriptor, stored);
        let no_limits = record.replace("/0/*,/1/*", "No path restrictions");
        assert_eq!(read_ok(&no_limits).descriptor, stored);

        let old = read_ok(&app_bsms(&stored, &first));
        assert_eq!(
            (old.descriptor.as_str(), old.format),
            (stored.as_str(), WalletFileFormat::AppBsms)
        );
        assert_eq!(old.stated_address, Some(first));
        // Any line count, any wallet size.
        let five = multisig(3, &[1, 2, 3, 4, 5]);
        assert_eq!(read_ok(&app_bsms(&five, &address(&five))).descriptor, five);
    }

    #[test]
    fn h_reads_as_the_apostrophe_and_the_fingerprint_as_lowercase() {
        let stored = two_of_three();
        let odd = stored.replace('\'', "h");
        assert_ne!(odd, stored);
        assert_eq!(read_ok(&odd).descriptor, stored);
        assert_eq!(read_ok(&stored.replace('\'', "H")).descriptor, stored);
        let fp = &wallet_describe(stored.clone(), NET).unwrap().keys[0].fingerprint;
        let upper = stored.replace(fp.as_str(), &fp.to_uppercase());
        assert_eq!(read_ok(&upper).descriptor, stored);
    }

    #[test]
    fn a_checksum_must_verify_when_present() {
        let stored = two_of_three();
        let text = wallet_descriptor_text(stored.clone(), NET).unwrap();
        let (_, sum) = text.split_once('#').unwrap();
        assert_eq!(sum.len(), 8);
        assert_eq!(read_ok(&text).descriptor, stored);
        let bad = format!("{}#{}", text.split('#').next().unwrap(), "qqqqqqqq");
        assert!(matches!(
            wallet_file_read(bad, NET),
            WalletFileRead::Unreadable { .. }
        ));
        // A checksum over another text does not carry over.
        let other = with(&stored, "/**").replace("/**", "/0/*");
        let wrong = format!("{other}#{sum}");
        assert!(matches!(
            wallet_file_read(wrong, NET),
            WalletFileRead::Unreadable { .. }
        ));
        let on_stored = format!("{stored}#{sum}");
        assert!(matches!(
            wallet_file_read(on_stored, NET),
            WalletFileRead::Unreadable { .. }
        ));
    }

    #[test]
    fn descriptor_text_is_the_multipath_form_that_miniscript_reads() {
        let stored = two_of_three();
        let text = wallet_descriptor_text(stored.clone(), NET).unwrap();
        assert!(text.matches("/<0;1>/*").count() == 3 && text.contains('#'));
        assert!(Descriptor::<DescriptorPublicKey>::from_str(&text).is_ok());
        assert_eq!(read_ok(&text).descriptor, stored);
    }

    #[test]
    fn a_record_read_back_is_the_same_descriptor() {
        for stored in [
            two_of_three(),
            multisig(1, &[1, 2]),
            multisig(3, &[1, 2, 3, 4, 5]),
        ] {
            let record = wallet_bsms_record(stored.clone(), NET).unwrap();
            assert_eq!(read_ok(&record).descriptor, stored);
            let text = wallet_descriptor_text(stored.clone(), NET).unwrap();
            assert_eq!(read_ok(&text).descriptor, stored);
            // And through the record again.
            let again = wallet_bsms_record(read_ok(&record).descriptor, NET).unwrap();
            assert_eq!(again, record);
        }
    }

    #[test]
    fn the_wrong_network_is_named() {
        let main = two_of_three().replace("tpub", "xpub");
        assert_eq!(wallet_file_read(main, NET), WalletFileRead::WrongNetwork);
        let tpub = wallet_file_read(two_of_three(), KeyNetwork::Main);
        assert_eq!(tpub, WalletFileRead::WrongNetwork);
    }

    #[test]
    fn other_kinds_name_themselves() {
        let found = |text: String| match wallet_file_read(text, NET) {
            WalletFileRead::Unsupported { found } => found,
            other => panic!("{other:?}"),
        };
        let key = |i: u8| {
            let d = multisig(1, &[i]);
            d["wsh(sortedmulti(1,".len()..d.len() - 2].to_string()
        };
        assert_eq!(found(one_key(AddressType::Taproot)), "tr");
        assert_eq!(found(one_key(AddressType::NativeSegwit)), "wpkh");
        assert_eq!(found(one_key(AddressType::Legacy)), "pkh");
        assert_eq!(found(one_key(AddressType::NestedSegwit)), "sh(wpkh)");
        assert_eq!(
            found(format!("sh(wsh(sortedmulti(1,{},{})))", key(1), key(2))),
            "sh(wsh)"
        );
        assert_eq!(
            found(format!("wsh(multi(1,{},{}))", key(1), key(2))),
            "wsh(multi)"
        );
    }

    #[test]
    fn duplicate_too_many_and_odd_suffix_keys_are_refused() {
        let found = |text: String| match wallet_file_read(text, NET) {
            WalletFileRead::Unsupported { found } => found,
            other => panic!("{other:?}"),
        };
        let key = |i: u8| {
            let d = multisig(1, &[i]);
            d["wsh(sortedmulti(1,".len()..d.len() - 2].to_string()
        };
        assert_eq!(
            found(format!("wsh(sortedmulti(1,{},{}))", key(1), key(1))),
            "duplicate keys"
        );
        let sixteen: Vec<String> = (1..=16).map(key).collect();
        assert_eq!(
            found(format!("wsh(sortedmulti(2,{}))", sixteen.join(","))),
            "more than 15 keys"
        );
        let stored = two_of_three();
        assert_eq!(found(with(&stored, "/1/*")), "key suffix /1/*");
        assert_eq!(found(with(&stored, "/0/*'")), "key suffix /0/*'");
        assert_eq!(found(with(&stored, "/<0;1>")), "key suffix /<0;1>");
    }

    #[test]
    fn mixed_suffix_forms_are_refused() {
        let stored = two_of_three();
        let once = with(&stored, "/**").replacen("/**", "/0/*", 1);
        assert_eq!(
            wallet_file_read(once, NET),
            WalletFileRead::Unsupported {
                found: "mixed key suffixes".into()
            }
        );
        let first_bare = with(&stored, "/**").replacen("/**", "", 1);
        assert!(matches!(
            wallet_file_read(first_bare, NET),
            WalletFileRead::Unsupported { .. }
        ));
    }

    #[test]
    fn a_stated_address_the_keys_do_not_give_is_tampering() {
        let stored = two_of_three();
        let other = address(&multisig(2, &[1, 2, 4]));
        let derived = address(&stored);
        let record = wallet_bsms_record(stored.clone(), NET)
            .unwrap()
            .replace(&derived, &other);
        assert_eq!(
            wallet_file_read(record, NET),
            WalletFileRead::AddressMismatch {
                stated: other.clone(),
                derived: derived.clone()
            }
        );
        assert!(matches!(
            wallet_file_read(app_bsms(&stored, &other), NET),
            WalletFileRead::AddressMismatch { .. }
        ));
        let junk = wallet_bsms_record(stored, NET)
            .unwrap()
            .replace(&derived, "not an address");
        assert!(matches!(
            wallet_file_read(junk, NET),
            WalletFileRead::AddressMismatch { .. }
        ));
    }

    #[test]
    fn anything_else_is_unreadable() {
        for text in [
            "",
            "   \n ",
            "hello",
            "BSMS 2.0\nwsh(sortedmulti(1,x))",
            "BSMS 1.0",
            "line one\nline two",
            "wsh(sortedmulti(2,[zz/48'/1'/0'/2']tpubnope))",
        ] {
            assert!(
                matches!(
                    wallet_file_read(text.into(), NET),
                    WalletFileRead::Unreadable { .. }
                ),
                "{text:?}"
            );
        }
        let record = wallet_bsms_record(two_of_three(), NET)
            .unwrap()
            .replace("/0/*,/1/*", "/0/*");
        assert!(matches!(
            wallet_file_read(record, NET),
            WalletFileRead::Unsupported { .. }
        ));
    }

    #[test]
    fn writing_a_bad_descriptor_is_an_error() {
        assert!(wallet_bsms_record("nonsense".into(), NET).is_err());
        assert!(wallet_descriptor_text(one_key(AddressType::Taproot), NET).is_err());
    }
}
