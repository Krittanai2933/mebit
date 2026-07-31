//! Watches BTC price and LTV per active loan, triggers margin-call /
//! liquidation in custody-service. See `docs/00-capstone-brief.md` §3.5.

mod ltv;
mod price_feed;

use ltv::{calc_liquidation_price, calc_ltv, risk_zone, Loan, RiskZone};
use price_feed::{MockPriceFeed, PriceFeed};
use std::{thread, time::Duration};

const TICKS: u32 = 5;

fn main() {
    // Same fixture loans as the mobile-signer-ffi demo app
    // (app/src/mockVault.ts), so both sides of the product agree on the
    // numbers used in demos.
    let loans = vec![
        Loan { id: "สัญญา #1".into(), collateral_btc: 0.20, debt_thb: 500_000.0 },
        Loan { id: "สัญญา #2".into(), collateral_btc: 0.08, debt_thb: 120_000.0 },
        Loan { id: "สัญญา #3".into(), collateral_btc: 0.05, debt_thb: 90_000.0 },
    ];

    let mut feed = MockPriceFeed::new(5_206_000.0);

    for tick in 1..=TICKS {
        let price = feed.price_thb();
        println!("\n=== tick {tick}/{TICKS} — BTC price ~ ฿{price:.0} ===");
        for loan in &loans {
            report(loan, price);
        }
        thread::sleep(Duration::from_millis(300));
    }
}

fn report(loan: &Loan, price_thb: f64) {
    let ltv = calc_ltv(loan.debt_thb, loan.collateral_btc, price_thb);
    let zone = risk_zone(ltv);
    let liq_price = calc_liquidation_price(loan.debt_thb, loan.collateral_btc);
    let flag = match zone {
        RiskZone::Safe => "",
        RiskZone::Watch => "  <- approaching margin call",
        RiskZone::Danger => "  !! would trigger liquidation in custody-service",
    };
    println!("  {:<10} LTV {:>5.1}%  liquidates at ~฿{:>10.0}{flag}", loan.id, ltv * 100.0, liq_price);
}
