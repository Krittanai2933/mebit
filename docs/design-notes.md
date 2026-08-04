# mebit borrower app — design reference

Source: Claude Design project **"Mebit mobile app design"** (`mebit App.dc.html` / `AppScreen.dc.html`), imported 2026-07-31, revised 2026-07-31 to add multi-loan support and three new screens, then again to add the hot-wallet requirement below. This is the UI/UX starting point for the `mobile-signer-ffi` demo app (capstone brief §3.3) — the team should adapt it rather than redesign from scratch. Design tokens live in [`../design-reference/`](../design-reference/); this file captures the screens, flows, and product reasoning behind them.

## Product framing

> "กระเป๋าบิตคอยน์ที่ถือกุญแจเอง แต่ใช้มูลค่าได้โดยไม่ต้องขาย"
> (A Bitcoin wallet where you keep your own keys, but can still use its value without selling.)

Sub-brand `mebit`, built on the Mapboss corporate identity (teal `#007368`, leaf green, a single yellow accent dot, FC Vision typeface throughout, "powered by mapboss" endorsement on onboarding). Light theme by default; the deep green (`#06312D`) surface is reserved for moments that should feel weighty: the Portfolio net-position panel, the Loan Dashboard risk panel, the Settings identity panel, and the success screen. Thai carries all substantive copy; English is used only for uppercase signposting labels (RECEIVE BITCOIN, LOAN DASHBOARD, LTV).

## Multi-loan / multi-contract model (new)

The single-loan model from the first design pass is gone. A borrower can now hold **several concurrent loan contracts against the same wallet**, each pledging its own slice of BTC as collateral — isolated, not cross-collateralized. If one contract's LTV crosses the liquidation threshold, only *that* contract's pledged collateral is at risk; other contracts and any unpledged ("free") BTC are unaffected. This is the single biggest structural change and touches almost every screen:

- **Home** now shows *aggregate* figures (total debt across all contracts, blended LTV) instead of one loan's numbers, and available credit is computed from **free (unpledged) BTC only**.
- **Loan Dashboard** is now per-contract — it needs to know *which* contract it's showing (`docs/design-notes.md`'s worked example below has three).
- **Portfolio** (new screen) is where the multi-loan view actually lives: net position, collateral allocation across contracts + free BTC, and a risk-sorted list of every open contract.
- **Borrow** now pledges from free BTC specifically, and opening a loan is really "creating a new contract" — confirming routes to Portfolio, not straight to one Loan Dashboard, since the borrower now has more than one loan to look at.

This maps cleanly onto the real system: each contract is its own on-chain vault (its own 2-of-3 multisig descriptor per `vault-core`), so "isolated, not cross-collateralized" isn't just a UI framing — it's the actual custody model in `docs/00-capstone-brief.md` §3.1, just made visible for the first time in the mobile UI once there's more than one loan to show.

## This app is a full Bitcoin wallet, not just a PSBT-signing screen (new)

The original scope for `mobile-signer-ffi` was "wrap vault-core's key derivation and PSBT signing." That's too narrow once you look at what the design actually asks for: the borrower's BTC is always in one of two states — **free** (not pledged to any loan) or **pledged** (locked in a specific loan's multisig vault) — and the app has to be a real wallet for the free portion, not just a display of a number:

1. **Transaction construction** — coin selection from the UTXO set, fee calculation, assembling ordinary (non-vault) transactions
2. **Send** — build, sign (single-sig, for the free portion), and broadcast
3. **Receive** — generate a new address per the descriptor, track incoming deposits, confirm on-chain
4. **Node connectivity** — an Electrum/Esplora client (or full node later) to sync UTXOs, balance, history, and fee estimates *from the app itself*, not only through the backend — this is what makes "self-custody" actually true: the borrower can see and move their own free BTC even if the backend is down
5. **Watch-only PSBT construction** — a device holding only the public descriptor/xpub (no private key) builds an unsigned PSBT that a different device with the real key signs separately — the same air-gapped pattern `lender-signer-cli` already uses, and useful later if a borrower wants their own cold-storage/hardware-wallet backup

**Recommendation: build this on `bdk` (Bitcoin Dev Kit) Rust core**, not `bdk-rn`/`bdk-dart` wrappers (those were still integration-testing-only as of July 2026) and not a hand-rolled wallet layer. `bdk` supports all 5 items above natively — a wallet bound to a descriptor, a swappable blockchain backend (Electrum/Esplora), coin selection, PSBT-native everywhere including watch-only mode. Reinventing this would burn time that should go toward the policy engine and multisig vault, which are the actual points of technical risk in this project.

The vault-signer half (wrapping `vault-core` for pledged BTC) is unchanged from the original scope. Both halves need to read as one balance on Home and Portfolio, but the key/signing logic underneath stays clearly separated — free BTC is single-sig (a weaker security model, by design, since it isn't loan collateral), pledged BTC is multisig.

**Scope reality check**: 12 full screens *plus* a real hot wallet is more than one person can finish solo in two terms. Split into **MVP screens** (onboarding → seed backup → Face ID → home → receive → borrow → loan dashboard → repay → success — this is the tappable prototype's actual path, and matches the midterm/final demo) and **stretch goals** (activity, portfolio, settings — nice to have if time allows, or picked up by teammates in term 2 once their own modules stabilize).

## Screens (12)

1. **Onboarding** — now a single combined screen (no separate splash): logo eyebrow, headline, tagline, "สร้างกระเป๋าใหม่" (create → seed backup) / "นำเข้ากระเป๋าเดิม" (import → straight to Home), powered-by-mapboss mark
2. **Seed backup** — "STEP 2 · BACKUP", 12-word grid, scam warning, confirm checkbox
3. **Face ID** — "STEP 3 · SECURITY", enroll or skip (both proceed to Home)
4. **Home** — price pill, total BTC, aggregate credit/debt, blended LTV (tap → Portfolio), Borrow CTA, Receive/Send/Swap row, recent activity
5. **Receive** — BTC address / QR placeholder
6. **Borrow** — pledges from free BTC, live LTV/liquidation-price recalculation, confirm → Portfolio
7. **Loan Dashboard** — per-contract risk status + stats + Repay / Add BTC / Withdraw
8. **Repay** — per-contract, preset + custom amount, payment method, after-repay preview
9. **Success** — post-repay confirmation (paid / remaining debt / new LTV)
10. **Activity** *(new)* — merged BTC + loan ledger, month-grouped, filterable
11. **Portfolio** *(new)* — net position, collateral allocation, all contracts sorted by risk
12. **Settings** *(new)* — identity/key-custody panel, security, loan alerts, preferences

Bottom nav (Wallet / Borrow / Activity / Portfolio / Settings, all five now functional) only appears on Home, Activity, Portfolio, and Settings — Receive/Borrow/Loan Dashboard/Repay/Success are stack screens with a back button instead.

## Numbers used throughout (keep consistent in any implementation)

Fixture data — three contracts against one wallet, useful as-is for demos/tests:

| Field | Value |
|---|---|
| BTC held (total) | 0.412 BTC |
| BTC price | ฿5,206,000 |
| Contract #1 — ทุนหมุนเวียน (working capital) | opened 21 ก.ค. 2569 · 0.20 BTC pledged · ฿500,000 debt |
| Contract #2 — ค่าเล่าเรียน (tuition) | opened 2 ก.ค. 2569 · 0.08 BTC pledged · ฿120,000 debt |
| Contract #3 — ซ่อมบ้าน (home repair) | opened 8 ก.ค. 2569 · 0.05 BTC pledged · ฿90,000 debt |
| Pledged BTC (sum) | 0.330 BTC |
| Free/unpledged BTC | 0.082 BTC |
| Total debt | ฿710,000 |
| Blended LTV | ~41.3% |
| Interest rate (all contracts) | 6% per year, accrued = `debt × 0.06 × (daysOpen / 365)` |
| Liquidation threshold | 80% LTV, always **derived** per-contract from that contract's own debt/collateral — never hand-entered |
| Initial-borrow cap | 60% LTV of the collateral being newly pledged (`docs/design-notes.md`'s earlier ฿800,000 flat cap is gone — the cap is now a percentage of free BTC's value, so it scales as free BTC changes) |
| Warning thresholds | 65% and 72% LTV, always before the 80% liquidation trigger |

Per-contract liquidation price and LTV use the same derivation as before (`debt / (collateral × price)` for LTV, `debt / (0.8 × collateral)` for liquidation price) — just computed per-contract now instead of once for the whole wallet.

## Design decisions worth carrying into implementation

- **Liquidation is per-contract, never wallet-wide.** A contract crossing 80% LTV only puts *its own* pledged BTC at risk. This must hold all the way down to `vault-core`: each contract's policy engine authorization should reference only that contract's vault/UTXO (see `.claude/skills/policy-engine-review/SKILL.md`'s "wrong loan" checklist item — this design decision is exactly the property that check exists to protect).
- **Available credit and initial borrow limits are based on free (unpledged) BTC, not total holdings.** Already-pledged BTC isn't available to back a new loan twice.
- **Home's LTV row now opens Portfolio, not a single Loan Dashboard** — once there's more than one contract, "your risk" is inherently a portfolio-level question; Loan Dashboard is reached by tapping a specific contract from Portfolio (or Activity).
- **Risk is shown spatially, not just numerically.** The arc-to-liquidation visualization (full sweep = 80% LTV) is used on both the Borrow screen and Loan Dashboard. Zone coloring is consistent everywhere: leaf green <50%, yellow 50–65%, orange ≥65%.
- **Liquidation price is always derived from LTV + collateral + loan, never typed as a raw number.** Unchanged from the original correction against the product brief's hand-entered figure — now applies per-contract.
- **Deleting the app doesn't delete the BTC, but losing the seed phrase means nobody — including the mebit team — can recover the wallet.** This sentence is explicit copy on the Settings screen; keep it verbatim in any real implementation, it's a load-bearing trust claim for a self-custody product.
- **No icon set exists yet in the Mapboss CI.** Nav glyphs remain minimal line icons as a flagged placeholder.

## Screen variants explored (not all need to ship — pick per team judgment)

Still valid from the first design pass — the multi-loan changes above apply on top of whichever variant is chosen:

- **Home**: (a) balance-led — BTC first, credit/loan side-by-side, thin tappable LTV bar [used in the wired prototype, now portfolio-aware]; (b) credit-led — the borrowable baht amount is the hero; (c) two-sided ledger — one dark panel with assets above a rule and debt below, net worth as the resolution.
- **Risk display**: (a) arc to liquidation [used in the wired prototype]; (b) price ruler — reframes risk as "how far can price fall" with LTV as a footnote; (c) zoned bar with the liquidation consequence spelled out in a sentence, plus Add-BTC/Repay actions inline.
- **Borrow amount input**: (a) continuous slider with live LTV [used in the wired prototype]; (b) numeric keypad for people who know the exact amount; (c) risk-named presets — the safest default for keeping first-time borrowers away from high LTV, since there's no free-form number entry at all.

## Roadmap context (from the original product brief, `uploads/` in the design project)

Useful for scoping V2/V3 conversations, but **out of MVP/capstone scope**:

- V2: buy/sell BTC via connected providers, on-chain/Lightning swap, real-time risk push notifications, passkey + hardware wallet support
- V3: multi-collateral, revolving BTC-backed credit line, Nostr Wallet Connect, BTC-backed debit card

Note that "multi-collateral" was originally scoped as V3 — the multi-*contract* model added here is adjacent but distinct: each contract still has one simple collateral pledge, there's just more than one contract per wallet. True multi-collateral (one loan backed by several distinct pledges) is still out of scope.

## Assets

- Design tokens (colors, type scale, radii, shadows): [`../design-reference/colors_and_type.css`](../design-reference/colors_and_type.css) — unchanged by this revision, no new tokens were introduced
- Logos: [`../design-reference/logos/`](../design-reference/logos/)
- Fonts (FC Vision, proprietary to Mapboss): not copied here — request the `.otf` files from the design team when building the real app; see the CSS file's `@font-face` blocks for the expected filenames/weights.
