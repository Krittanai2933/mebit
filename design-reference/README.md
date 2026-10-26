# Design reference

Design tokens and brand assets pulled from the Claude Design project **"Mebit mobile app design"** (`mebit App.dc.html`), for the `mobile-signer-ffi` team to build the real borrower app against. See [`../docs/design-notes.md`](../docs/design-notes.md) for the screens, flows, and product reasoning that go with these tokens.

- `colors_and_type.css` — Mapboss/mebit color tokens, type scale, radii, shadows, motion curves. `@font-face` blocks reference `fonts/Kanit-*.ttf` (OFL shipping typeface; Thai + Latin). Penpot historically used FC Vision — we do not ship that cut.
- `fonts/` — Kanit TTFs + `Kanit-OFL.txt`.
- `logos/` — mebit/Mapboss logo marks (landscape lockup, "powered by mapboss" endorsement, symbol-only).

Do not copy `android-frame.jsx` / `ios-frame.jsx` from the Claude Design project into the app — those are Claude Design's own device-frame preview components (used only to render the prototype inside claude.ai/design), not reusable app code.
