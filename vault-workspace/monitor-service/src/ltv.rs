//! LTV = debt / collateral value. Thresholds are configurable — brief's
//! example: init 50% / margin call 70% / liquidate 80% (this skeleton uses
//! 50/65/80, matching `mobile-signer-ffi`'s mock — reconcile with the team
//! before changing either). Liquidation price must always be derived, never
//! hardcoded — see the correction documented in `docs/design-notes.md`.
//! These formulas are kept identical to
//! `vault-workspace/mobile-signer-ffi/app/src/mockVault.ts` so every module
//! agrees on the math.

pub const WATCH_LTV: f64 = 0.5;
pub const MARGIN_CALL_LTV: f64 = 0.65;
pub const LIQUIDATION_LTV: f64 = 0.8;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RiskZone {
    Safe,
    Watch,
    Danger,
}

#[derive(Debug, Clone)]
pub struct Loan {
    pub id: String,
    pub collateral_btc: f64,
    pub debt_thb: f64,
}

pub fn calc_ltv(debt_thb: f64, collateral_btc: f64, price_thb: f64) -> f64 {
    let collateral_value = collateral_btc * price_thb;
    if collateral_value <= 0.0 {
        return 0.0;
    }
    debt_thb / collateral_value
}

pub fn calc_liquidation_price(debt_thb: f64, collateral_btc: f64) -> f64 {
    if collateral_btc <= 0.0 {
        return 0.0;
    }
    debt_thb / (collateral_btc * LIQUIDATION_LTV)
}

pub fn risk_zone(ltv: f64) -> RiskZone {
    if ltv >= MARGIN_CALL_LTV {
        RiskZone::Danger
    } else if ltv >= WATCH_LTV {
        RiskZone::Watch
    } else {
        RiskZone::Safe
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_worked_example_from_design_notes() {
        // 0.20 BTC collateral / ฿500,000 debt / ฿5,206,000 price -> ~48% LTV,
        // per docs/design-notes.md's worked example.
        let ltv = calc_ltv(500_000.0, 0.20, 5_206_000.0);
        assert!((ltv - 0.4802).abs() < 0.001);
        assert_eq!(risk_zone(ltv), RiskZone::Safe);
    }

    #[test]
    fn liquidation_price_is_derived_not_hardcoded() {
        let liq = calc_liquidation_price(500_000.0, 0.20);
        assert!((liq - 3_125_000.0).abs() < 1.0);
    }

    #[test]
    fn zones_match_thresholds() {
        assert_eq!(risk_zone(0.3), RiskZone::Safe);
        assert_eq!(risk_zone(0.55), RiskZone::Watch);
        assert_eq!(risk_zone(0.7), RiskZone::Danger);
    }
}
