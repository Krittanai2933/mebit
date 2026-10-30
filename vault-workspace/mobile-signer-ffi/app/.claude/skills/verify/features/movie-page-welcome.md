# Movie Page welcome

A user lands on the Movie Page Welcome board, can open the debug route catalog, and starts a new wallet or seed import.

## Sub-features

- `mp-welcome` shows the mebit logo, the card fan (a single-sig card in front of a 2-of-3 and a 3-of-5 vault card), the gold Thai tagline, the headline, and the Thai create/import CTAs.
- `mp-catalog` opens the route catalog from the debug link.
- `mp-create` starts the new-wallet path at Key Source.

## How to get to it (user POV)

- Android: `verify.sh start` installs and opens the app on Welcome.
- Debug: tap `รายการหน้าทั้งหมด (debug)` to jump boards.

## Driving it with verify.sh

Preconditions:

- `verify.sh start` finished in this run and `verify.sh doctor` passes.
- Tip branch includes Movie Page UI (PR-A+).

- **Welcome visible.** Run `verify.sh see "id:screen_welcome"`, `verify.sh see "desc:mebit"` (the logo) and `verify.sh see "text~:คุณถือเองทุกดอก"` (the tagline). Run `verify.sh shot mp-welcome` and look at the PNG: the logo must sit top left and the card fan under it, with its right edge running off the screen. On iOS use `ios-see` and `ios-shot`.
- **Catalog.** Run `verify.sh tap "id:open_catalog"`, then `verify.sh see "MOVIE PAGE ROUTES"`. Run `verify.sh shot mp-catalog`. Dismiss by tapping a route or system back if needed.
- **Create wallet.** From Welcome, run `verify.sh tap "id:cta_create"` (or `verify.sh tap "สร้างกระเป๋าใหม่"`), then `verify.sh see "id:screen_onboarding/key-source"` and `verify.sh see "NEW WALLET"`. Run `verify.sh shot mp-create`.

## Gotchas

- Prefer `id:` tags once Android root sets `testTagsAsResourceId`. Fall back to Thai CTA text if `id:` fails.
- Catalog is a ModalBottomSheet; after opening it, dismiss before other recipes that expect Welcome alone.
- The card fan is hidden from accessibility on purpose (its balance is sample data), so only the screenshot proves it. `id:card_fan` may not be found.
