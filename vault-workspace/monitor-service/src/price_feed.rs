//! MVP: public Esplora API. Keep this behind a trait so it can be swapped
//! for a self-hosted Electrs instance later without touching `ltv`.

pub trait PriceFeed {
    /// Current BTC price in THB.
    fn price_thb(&mut self) -> f64;
}

/// Stands in for a real Esplora/price-oracle client: starts at a fixed
/// price and randomly walks it a few percent per tick, so the monitor loop
/// has something to react to. Replace with a real HTTP client once
/// monitor-service starts polling a live feed.
pub struct MockPriceFeed {
    price_thb: f64,
}

impl MockPriceFeed {
    pub fn new(starting_price_thb: f64) -> Self {
        Self { price_thb: starting_price_thb }
    }
}

impl PriceFeed for MockPriceFeed {
    fn price_thb(&mut self) -> f64 {
        let drift = rand::random_range(-0.03..0.03);
        self.price_thb *= 1.0 + drift;
        self.price_thb
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn price_stays_positive_after_many_ticks() {
        let mut feed = MockPriceFeed::new(5_206_000.0);
        for _ in 0..100 {
            assert!(feed.price_thb() > 0.0);
        }
    }
}
