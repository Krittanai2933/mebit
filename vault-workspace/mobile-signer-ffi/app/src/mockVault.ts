// Mock stand-in for the real vault-core / UniFFI binding.
//
// vault-workspace/mobile-signer-ffi/rust doesn't have a stable interface yet
// (vault-core's descriptor/PSBT work is still in progress — see
// ../../../docs/00-capstone-brief.md §4), and CLAUDE.md's convention for this
// project is explicit: "Don't build UniFFI/mobile app scaffolding
// speculatively ahead of vault-core's interface stabilizing — mock it
// instead." Everything in this file is plain TypeScript arithmetic with no
// cryptography — it exists so the UI/UX can be built and played with now,
// and gets swapped for real UniFFI calls once vault-core ships.
//
// Fixture numbers match the worked example in ../../../docs/design-notes.md
// so screens are comparable with the original design reference.
//
// Multi-loan model: a wallet can hold several concurrent loan contracts,
// each pledging its own slice of BTC as collateral, isolated from the
// others (see docs/design-notes.md — this mirrors the real system, where
// each contract is its own on-chain vault per vault-core §3.1). Liquidation
// math is always per-contract; "available credit" is always based on
// unpledged ("free") BTC, never total holdings.

export const LIQUIDATION_LTV = 0.8; // matches docs/design-notes.md — do not hardcode a liquidation price anywhere, always derive it from this
export const MARGIN_CALL_LTV = 0.65;
export const WATCH_LTV = 0.5;
export const INITIAL_MAX_LTV = 0.6; // cap on a *new* contract's LTV against the collateral it pledges

export type RiskZone = 'safe' | 'watch' | 'danger';

export interface LoanContract {
  id: number;
  label: string;
  openedLabel: string; // display date, e.g. "21 ก.ค. 2569"
  daysOpen: number;
  collateralBtc: number;
  debtThb: number;
  color: string; // accent used for this contract's card/allocation slice
}

export interface VaultState {
  btcHeld: number; // total BTC in the borrower's wallet
  btcPriceThb: number; // current spot price, mocked — monitor-service owns the real feed
  interestRatePct: number; // flat rate across all contracts
  contracts: LoanContract[];
}

export const initialVaultState: VaultState = {
  btcHeld: 0.412,
  btcPriceThb: 5_206_000,
  interestRatePct: 6,
  contracts: [
    {
      id: 1,
      label: 'สัญญา #1 · ทุนหมุนเวียน',
      openedLabel: '21 ก.ค. 2569',
      daysOpen: 10,
      collateralBtc: 0.2,
      debtThb: 500_000,
      color: '#FCC330',
    },
    {
      id: 2,
      label: 'สัญญา #2 · ค่าเล่าเรียน',
      openedLabel: '2 ก.ค. 2569',
      daysOpen: 29,
      collateralBtc: 0.08,
      debtThb: 120_000,
      color: '#4DB848',
    },
    {
      id: 3,
      label: 'สัญญา #3 · ซ่อมบ้าน',
      openedLabel: '8 ก.ค. 2569',
      daysOpen: 23,
      collateralBtc: 0.05,
      debtThb: 90_000,
      color: '#009B68',
    },
  ],
};

export function btcValueThb(btc: number, priceThb: number): number {
  return btc * priceThb;
}

/** LTV = debt / collateral value. This is the one formula every module (mobile UI, vault-core's policy engine, monitor-service) must agree on — see docs/design-notes.md. */
export function calcLtv(debtThb: number, collateralBtc: number, priceThb: number): number {
  const collateralValue = btcValueThb(collateralBtc, priceThb);
  if (collateralValue <= 0) return 0;
  return debtThb / collateralValue;
}

/** Liquidation price is always derived — never hand-entered. See the correction documented in docs/design-notes.md. */
export function calcLiquidationPrice(debtThb: number, collateralBtc: number): number {
  if (collateralBtc <= 0) return 0;
  return debtThb / (collateralBtc * LIQUIDATION_LTV);
}

export function riskZone(ltv: number): RiskZone {
  if (ltv >= MARGIN_CALL_LTV) return 'danger';
  if (ltv >= WATCH_LTV) return 'watch';
  return 'safe';
}

export function riskLabel(zone: RiskZone): string {
  switch (zone) {
    case 'safe':
      return 'ปลอดภัย';
    case 'watch':
      return 'เฝ้าระวัง';
    case 'danger':
      return 'เสี่ยงสูง';
  }
}

export function pledgedBtc(contracts: LoanContract[]): number {
  return contracts.reduce((sum, c) => sum + c.collateralBtc, 0);
}

export function freeBtc(state: VaultState): number {
  return Math.max(0, state.btcHeld - pledgedBtc(state.contracts));
}

export function totalDebtThb(contracts: LoanContract[]): number {
  return contracts.reduce((sum, c) => sum + c.debtThb, 0);
}

/** Blended LTV across the whole portfolio: total debt against total pledged collateral. */
export function portfolioLtv(contracts: LoanContract[], priceThb: number): number {
  return calcLtv(totalDebtThb(contracts), pledgedBtc(contracts), priceThb);
}

export function contractLtv(contract: LoanContract, priceThb: number): number {
  return calcLtv(contract.debtThb, contract.collateralBtc, priceThb);
}

export function contractLiquidationPrice(contract: LoanContract): number {
  return calcLiquidationPrice(contract.debtThb, contract.collateralBtc);
}

export function accruedInterestThb(contract: LoanContract, annualRatePct: number): number {
  return contract.debtThb * (annualRatePct / 100) * (contract.daysOpen / 365);
}

/** Contracts sorted riskiest (highest LTV) first — matches the Portfolio screen's card order. */
export function sortContractsByRisk(contracts: LoanContract[], priceThb: number): LoanContract[] {
  return [...contracts].sort((a, b) => contractLtv(b, priceThb) - contractLtv(a, priceThb));
}

export function worstContract(contracts: LoanContract[], priceThb: number): { contract: LoanContract; ltv: number } {
  return contracts.reduce(
    (worst, c) => {
      const ltv = contractLtv(c, priceThb);
      return ltv > worst.ltv ? { contract: c, ltv } : worst;
    },
    { contract: contracts[0], ltv: contracts.length ? contractLtv(contracts[0], priceThb) : 0 }
  );
}

/** Available credit / the cap on a brand-new contract: INITIAL_MAX_LTV against the BTC being newly pledged. */
export function maxInitialBorrow(collateralBtc: number, priceThb: number): number {
  return Math.floor((btcValueThb(collateralBtc, priceThb) * INITIAL_MAX_LTV) / 10_000) * 10_000;
}

export function netWorthThb(state: VaultState): number {
  return Math.round(btcValueThb(state.btcHeld, state.btcPriceThb) - totalDebtThb(state.contracts));
}

export interface BorrowPreset {
  key: 'careful' | 'balanced' | 'max';
  label: string;
  ltv: number;
}

// Not used by the live slider-based Borrow screen — kept as reference for
// the "risk-named presets" input variant documented in docs/design-notes.md.
export const BORROW_PRESETS: BorrowPreset[] = [
  { key: 'careful', label: 'ระมัดระวัง', ltv: 0.25 },
  { key: 'balanced', label: 'สมดุล', ltv: 0.5 },
  { key: 'max', label: 'สูงสุด', ltv: 0.7 },
];

export function priceDropToLiquidationPct(currentPriceThb: number, liquidationPriceThb: number): number {
  if (currentPriceThb <= 0) return 0;
  return Math.round(((currentPriceThb - liquidationPriceThb) / currentPriceThb) * 100);
}

export function formatThb(amount: number): string {
  return `฿${Math.round(amount).toLocaleString('en-US')}`;
}

export function formatBtc(amount: number, decimals = 3): string {
  return amount.toFixed(decimals);
}

export function formatPct(fraction: number, decimals = 1): string {
  return `${(fraction * 100).toFixed(decimals)}%`;
}

// A visibly-fake mnemonic for the seed-backup screen — 12 static placeholder
// words, never real entropy. Real seed generation belongs in vault-core.
export const MOCK_SEED_WORDS = [
  'temple', 'orbit', 'copper', 'gravel', 'future', 'salmon',
  'ladder', 'onion', 'velvet', 'modest', 'hazard', 'punch',
];

export function mockAddress(): string {
  return 'tb1q' + 'mebitdemo0000000000000000000wallet0';
}

export function mockTxid(): string {
  const chars = '0123456789abcdef';
  let out = '';
  for (let i = 0; i < 64; i++) out += chars[Math.floor(Math.random() * chars.length)];
  return out;
}
