---
name: design-tokens
description: Quick reference to the mebit/Mapboss design tokens (colors, type, radii, shadows) and where the full borrower-app design reference lives. Use when building any UI for mobile-signer-ffi's demo app, or any other screen/dashboard in this project that should look on-brand.
---

# mebit design tokens

Full screens/flows/product reasoning: `docs/design-notes.md`. Full token file: `design-reference/colors_and_type.css`. Logos: `design-reference/logos/`. This skill is just the cheat sheet.

## Core palette

| Token | Hex | Use |
|---|---|---|
| `--mb-teal` | `#007368` | Primary — headings, key UI, primary buttons |
| `--mb-green-900` | `#06312D` | Darkest surface — reserve for weighty moments only (Loan Dashboard risk panel, success screen) |
| `--mb-green-leaf` | `#4DB848` | "Safe" / positive status, card accent bars |
| `--mb-yellow` | `#FCC330` | The single warm accent dot — use sparingly, never as a base color |
| `--mb-orange` | `#F8981C` | Secondary warm — risk/warning zones, icons/data-viz only |
| `--mb-mint-tint` | `#F2F8F7` | Default light-theme background wash |
| `--mb-ink` / `--mb-gray-700` | `#1E1E1E` / `#4B4B4B` | Primary / body text on light |

Risk-zone convention used throughout the design: leaf green below 50% LTV, yellow 50–65%, orange above 65% — reuse this exact mapping in any new risk UI so it stays consistent with the Loan Dashboard.

## Type

Everything is **FC Vision** (`--font-display` / `--font-body` / `--font-label` all resolve to it) — a proprietary Mapboss typeface. The `.otf` files are not in this repo; request them from the design team, and use a system sans-serif fallback (already wired via the CSS `font-family` stack) until then. Thai carries substance; English is reserved for uppercase signposting labels (e.g. "LOAN DASHBOARD", "LTV").

## Radii & shadows

`--radius-lg` (28px) is the signature soft card corner, especially on dark surfaces. `--shadow-card` / `--shadow-soft` / `--shadow-float` give three elevation tiers — see `design-reference/colors_and_type.css` for exact values.

## Before building a new screen

1. Check `docs/design-notes.md` — the screen you need may already exist as one of the three explored variants (Home, risk display, borrow-input all have three each).
2. Reuse the worked numeric example (0.412 BTC, ฿350,000 loan, 16.3% LTV, ฿3,125,000 liquidation price) as fixture/demo data so screens are comparable across the app.
3. Remember: liquidation price is always **derived** from LTV + collateral + loan amount, never a typed/hardcoded number — see the correction documented in `docs/design-notes.md`.
