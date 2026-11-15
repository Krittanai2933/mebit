//! The Bitcoin price in baht, for the fiat lines on the wallet screens.

use std::io::Read;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde_json::Value;

use crate::MobileSignerError;

const BITKUB_URL: &str = "https://api.bitkub.com/api/v3/market/ticker?sym=btc_thb";
const COINGECKO_URL: &str = "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=thb&include_last_updated_at=true";

// Real prices sit near 2.6 million baht (late 2026), so a market move never trips this range. It exists
// so that a zero, a negative number, NaN, infinity or a decimal point moved several places never
// becomes a price: a source that answers nonsense must fall through to the next one.
const MIN_THB_PER_BTC: f64 = 1_000.0;
const MAX_THB_PER_BTC: f64 = 1_000_000_000.0;
// A body cut at the cap fails to parse, which counts as a failed source.
const MAX_BODY_BYTES: u64 = 64 * 1024;

/// Baht per bitcoin and the phone clock at the moment of the call.
#[derive(Clone, Copy, Debug, PartialEq, uniffi::Record)]
pub struct BtcPrice {
    /// Baht for one bitcoin, between 1,000 and 1,000,000,000.
    pub thb_per_btc: f64,
    /// Unix seconds on the phone's clock when the call started. Bitkub sends no time of its own.
    pub fetched_at: u64,
}

/// Baht per bitcoin from Bitkub, or from CoinGecko when Bitkub fails or answers nonsense.
/// Neither publishes terms for use inside an app: fine for this testnet build, not for production.
///
/// Blocks for up to 20 seconds when both sources hang, so call it off the main thread. Fails with
/// `Network`, which names both reasons, when neither source gives a price.
#[uniffi::export]
pub fn fetch_btc_thb() -> Result<BtcPrice, MobileSignerError> {
    // A clock set before 1970 reads as 0, which the app treats as an old quote.
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |since| since.as_secs());
    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(5))
        .timeout(Duration::from_secs(10))
        .build();
    quote_from(&|url| get_capped(&agent, url), now)
}

fn quote_from(
    get: &dyn Fn(&str) -> Result<String, String>,
    now: u64,
) -> Result<BtcPrice, MobileSignerError> {
    let thb_per_btc = match get(BITKUB_URL).and_then(|body| bitkub_last(&body)) {
        Ok(price) => price,
        Err(bitkub) => get(COINGECKO_URL)
            .and_then(|body| coingecko_thb(&body))
            .map_err(|coingecko| {
                MobileSignerError::Network(format!("Bitkub: {bitkub}; CoinGecko: {coingecko}"))
            })?,
    };
    Ok(BtcPrice {
        thb_per_btc,
        fetched_at: now,
    })
}

fn get_capped(agent: &ureq::Agent, url: &str) -> Result<String, String> {
    let mut body = String::new();
    agent
        .get(url)
        .call()
        .map_err(|e| e.to_string())?
        .into_reader()
        .take(MAX_BODY_BYTES)
        .read_to_string(&mut body)
        .map_err(|e| e.to_string())?;
    Ok(body)
}

fn bitkub_last(body: &str) -> Result<f64, String> {
    let json: Value = serde_json::from_str(body).map_err(|e| format!("not JSON: {e}"))?;
    if let Some(code) = json.get("error") {
        return Err(format!("error {code}"));
    }
    let row = json
        .as_array()
        .and_then(|rows| rows.iter().find(|row| row["symbol"] == "BTC_THB"))
        .ok_or("no BTC_THB row")?;
    let last = row["last"].as_str().ok_or("no last price")?;
    sane(
        last.parse()
            .map_err(|_| format!("last is not a number: {last}"))?,
    )
}

fn coingecko_thb(body: &str) -> Result<f64, String> {
    let json: Value = serde_json::from_str(body).map_err(|e| format!("not JSON: {e}"))?;
    sane(
        json["bitcoin"]["thb"]
            .as_f64()
            .ok_or("no bitcoin.thb number")?,
    )
}

fn sane(price: f64) -> Result<f64, String> {
    if (MIN_THB_PER_BTC..=MAX_THB_PER_BTC).contains(&price) {
        Ok(price)
    } else {
        Err(format!(
            "{price} is outside {MIN_THB_PER_BTC} to {MAX_THB_PER_BTC} baht per bitcoin"
        ))
    }
}

#[cfg(test)]
mod tests {
    use std::cell::RefCell;

    use super::*;

    const REAL_BITKUB: &str = include_str!("../fixtures/price/bitkub_v3_btc_thb.json");
    const REAL_COINGECKO: &str = include_str!("../fixtures/price/coingecko_btc_thb.json");
    const REAL_BITKUB_V1_ERROR: &str = include_str!("../fixtures/price/bitkub_v1_error_11.json");
    const BITKUB_LAST_ZERO: &str =
        include_str!("../fixtures/price/synthetic_bitkub_last_zero.json");

    const NOW: u64 = 1_793_555_000;
    const BITKUB_ASKED: &str = "https://api.bitkub.com/api/v3/market/ticker?sym=btc_thb";
    const COINGECKO_ASKED: &str = "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=thb&include_last_updated_at=true";

    // Answers by host and records every URL it is asked for.
    fn getter<'a>(
        asked: &'a RefCell<Vec<String>>,
        bitkub: Result<&'a str, &'a str>,
        coingecko: Result<&'a str, &'a str>,
    ) -> impl Fn(&str) -> Result<String, String> + 'a {
        move |url| {
            asked.borrow_mut().push(url.to_string());
            let answer = if url.contains("bitkub") {
                bitkub
            } else {
                coingecko
            };
            answer.map(str::to_string).map_err(str::to_string)
        }
    }

    #[test]
    fn the_real_recordings_parse_to_the_prices_they_hold() {
        assert_eq!(bitkub_last(REAL_BITKUB), Ok(2_621_051.11));
        assert_eq!(coingecko_thb(REAL_COINGECKO), Ok(2_619_363.0));
    }

    #[test]
    fn every_error_shape_is_rejected() {
        let bitkub = [
            ("bitkub_v1_error_11", REAL_BITKUB_V1_ERROR),
            (
                "synthetic_bitkub_empty_array",
                include_str!("../fixtures/price/synthetic_bitkub_empty_array.json"),
            ),
            (
                "synthetic_bitkub_last_not_a_number",
                include_str!("../fixtures/price/synthetic_bitkub_last_not_a_number.json"),
            ),
            ("synthetic_bitkub_last_zero", BITKUB_LAST_ZERO),
        ];
        for (name, body) in bitkub {
            assert!(bitkub_last(body).is_err(), "{name} was accepted");
        }
        // Bitkub's own code is the reason the Network error shows.
        assert_eq!(
            bitkub_last(REAL_BITKUB_V1_ERROR),
            Err("error 11".to_string())
        );
        let coingecko = [
            (
                "synthetic_coingecko_empty_object",
                include_str!("../fixtures/price/synthetic_coingecko_empty_object.json"),
            ),
            (
                "synthetic_coingecko_bitcoin_empty",
                include_str!("../fixtures/price/synthetic_coingecko_bitcoin_empty.json"),
            ),
            (
                "synthetic_coingecko_rate_limit",
                include_str!("../fixtures/price/synthetic_coingecko_rate_limit.json"),
            ),
        ];
        for (name, body) in coingecko {
            assert!(coingecko_thb(body).is_err(), "{name} was accepted");
        }
    }

    #[test]
    fn bitkub_reads_the_btc_thb_row_not_the_first_row() {
        let rows =
            r#"[{"symbol":"ETH_THB","last":"90000"},{"symbol":"BTC_THB","last":"2621051.11"}]"#;
        assert_eq!(bitkub_last(rows), Ok(2_621_051.11));
        let other_coin_only = r#"[{"symbol":"ETH_THB","last":"90000"}]"#;
        assert!(bitkub_last(other_coin_only).is_err());
    }

    #[test]
    fn only_a_price_inside_the_range_passes() {
        for price in [1_000.0, 2_621_051.11, 1_000_000_000.0] {
            assert_eq!(sane(price), Ok(price));
        }
        let outside = [0.0, -2_621_051.11, 999.99, 1_000_000_000.01];
        for price in outside
            .into_iter()
            .chain([f64::NAN, f64::INFINITY, f64::NEG_INFINITY])
        {
            assert!(sane(price).is_err(), "{price} passed");
        }
        // Rust's float parser reads these words as numbers, so the range check has to stop them.
        for word in ["NaN", "inf", "-inf", "1e400"] {
            let body = format!(r#"[{{"symbol":"BTC_THB","last":"{word}"}}]"#);
            assert!(bitkub_last(&body).is_err(), "{word} passed");
        }
    }

    #[test]
    fn bitkub_answering_means_coingecko_is_never_asked() {
        let asked = RefCell::new(Vec::new());
        let get = getter(&asked, Ok(REAL_BITKUB), Ok(REAL_COINGECKO));
        let price = quote_from(&get, NOW).unwrap();
        assert_eq!(
            price,
            BtcPrice {
                thb_per_btc: 2_621_051.11,
                fetched_at: NOW
            }
        );
        assert_eq!(*asked.borrow(), [BITKUB_ASKED]);
    }

    #[test]
    fn bitkub_failing_or_answering_nonsense_uses_coingecko() {
        for bitkub in [
            Err("timed out"),
            Ok(REAL_BITKUB_V1_ERROR),
            Ok(BITKUB_LAST_ZERO),
        ] {
            let asked = RefCell::new(Vec::new());
            let get = getter(&asked, bitkub, Ok(REAL_COINGECKO));
            let price = quote_from(&get, NOW).unwrap();
            assert_eq!(
                price,
                BtcPrice {
                    thb_per_btc: 2_619_363.0,
                    fetched_at: NOW
                }
            );
            assert_eq!(
                *asked.borrow(),
                [BITKUB_ASKED, COINGECKO_ASKED],
                "{bitkub:?}"
            );
        }
    }

    #[test]
    fn both_sources_failing_gives_one_network_error_naming_both() {
        let asked = RefCell::new(Vec::new());
        let get = getter(&asked, Err("connection refused"), Err("status code 429"));
        let Err(MobileSignerError::Network(text)) = quote_from(&get, NOW) else {
            panic!("expected a Network error");
        };
        assert!(text.contains("Bitkub: connection refused"), "{text}");
        assert!(text.contains("CoinGecko: status code 429"), "{text}");
        assert_eq!(*asked.borrow(), [BITKUB_ASKED, COINGECKO_ASKED]);
    }

    #[test]
    #[ignore = "calls the real Bitkub and CoinGecko APIs"]
    fn live_quote_from_the_real_sources() {
        let price = fetch_btc_thb().unwrap();
        println!("live quote: {price:?}");
        assert!((MIN_THB_PER_BTC..=MAX_THB_PER_BTC).contains(&price.thb_per_btc));
        assert!(price.fetched_at > 1_700_000_000, "{price:?}");

        // The fallback only runs when Bitkub is down, so ask each source on its own as well.
        let agent = ureq::agent();
        let bitkub = get_capped(&agent, BITKUB_URL).and_then(|body| bitkub_last(&body));
        let coingecko = get_capped(&agent, COINGECKO_URL).and_then(|body| coingecko_thb(&body));
        println!("live bitkub: {bitkub:?}\nlive coingecko: {coingecko:?}");
        assert!(bitkub.is_ok() && coingecko.is_ok());
    }
}
