# Design reference

Design tokens and brand assets pulled from the Claude Design project **"Mebit mobile app design"** (`mebit App.dc.html`), for the `mobile-signer-ffi` team to build the real borrower app against. See [`../docs/design-notes.md`](../docs/design-notes.md) for the screens, flows, and product reasoning that go with these tokens.

- `colors_and_type.css` — Mapboss/mebit color tokens, type scale, radii, shadows, motion curves. `@font-face` blocks reference `fonts/FCVision-*.otf`, which are **not** included here (proprietary Mapboss typeface) — request them from the design team before wiring up real font rendering; substitute a system sans-serif in the meantime.
- `logos/` — mebit/Mapboss logo marks (landscape lockup, "powered by mapboss" endorsement, symbol-only).

Do not copy `android-frame.jsx` / `ios-frame.jsx` from the Claude Design project into the app — those are Claude Design's own device-frame preview components (used only to render the prototype inside claude.ai/design), not reusable app code.
