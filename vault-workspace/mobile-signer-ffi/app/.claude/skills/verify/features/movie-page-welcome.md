# Movie Page welcome

A user lands on the Movie Page Welcome board, can open the debug route catalog, and starts a new wallet or seed import.

## Sub-features

- `mp-welcome` shows the WELCOME eyebrow and Thai create/import CTAs.
- `mp-catalog` opens the route catalog from the debug link.
- `mp-create` starts the new-wallet path at Key Source.

## How to get to it (user POV)

- Android: `verify.sh start` installs and opens the app on Welcome.
- Debug: tap `รายการหน้าทั้งหมด (debug)` to jump boards.

## Driving it with verify.sh

Preconditions:

- `verify.sh start` finished in this run and `verify.sh doctor` passes.
- Tip branch includes Movie Page UI (PR-A+).

- **Welcome visible.** Run `verify.sh see "id:screen_welcome"` and `verify.sh see "WELCOME"`. Run `verify.sh shot mp-welcome`.
- **Catalog.** Run `verify.sh tap "id:open_catalog"`, then `verify.sh see "MOVIE PAGE ROUTES"`. Run `verify.sh shot mp-catalog`. Dismiss by tapping a route or system back if needed.
- **Create wallet.** From Welcome, run `verify.sh tap "id:cta_create"` (or `verify.sh tap "สร้างกระเป๋าใหม่"`), then `verify.sh see "id:screen_onboarding/key-source"` and `verify.sh see "NEW WALLET"`. Run `verify.sh shot mp-create`.

## Gotchas

- Prefer `id:` tags once Android root sets `testTagsAsResourceId`. Fall back to Thai CTA text if `id:` fails.
- Catalog is a ModalBottomSheet; after opening it, dismiss before other recipes that expect Welcome alone.
