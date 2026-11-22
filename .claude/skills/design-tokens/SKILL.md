---
name: design-tokens
description: Quick reference to the mebit/Mapboss design tokens (colors, type, radii, shadows) and where the full borrower-app design reference lives. Use when building any UI for mobile-signer-ffi's demo app, or any other screen/dashboard in this project that should look on-brand.
---

# mebit design tokens

Full screens/flows/product reasoning: `docs/design-notes.md`. Full token file: `design-reference/colors_and_type.css`. There are no logo files in the repo yet (`docs/04-open-items.md` item 106). This skill is just the cheat sheet.

## Core palette

The brand palette (Orbit Navy `#002B49`, Mercury Gold `#FFBD59`, and the rest) is in `DESIGN.md`; the CSS variables are in `design-reference/colors_and_type.css`. Take values from those two files, and see `docs/04-open-items.md` items 107 and 108 for where they still disagree with Penpot.

## Type

Shipping typeface is **Kanit** (OFL, Thai + Latin) — `--font-display` / `--font-body` / `--font-label` all resolve to it. Files live in `design-reference/fonts/Kanit-*.ttf`; Compose loads `Res.font.kanit_*`. Penpot historically used FC Vision; do not claim glyph pixel-zero against those boards. Thai carries substance; English is reserved for uppercase signposting labels (e.g. "LOAN DASHBOARD", "LTV").

## Radii & shadows

`--radius-lg` (24px, the same value as `--org-radius-card`) is the signature soft card corner, especially on dark surfaces. `--shadow-card` / `--shadow-soft` / `--shadow-float` give three elevation tiers — see `design-reference/colors_and_type.css` for exact values.

## Before building a new screen

1. Check `docs/design-notes.md` — the screen you need may already exist as one of the three explored variants (Home, risk display, borrow-input all have three each).
2. Reuse the worked numeric example (0.412 BTC, ฿350,000 loan, 16.3% LTV, ฿3,125,000 liquidation price) as fixture/demo data so screens are comparable across the app.
3. Remember: liquidation price is always **derived** from LTV + collateral + loan amount, never a typed/hardcoded number — see the correction documented in `docs/design-notes.md`.
