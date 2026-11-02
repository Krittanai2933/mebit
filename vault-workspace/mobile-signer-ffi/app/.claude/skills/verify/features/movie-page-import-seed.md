# Movie Page import seed

A user who already has a wallet taps นำเข้ากระเป๋าเดิม, fills 24 words by typing with suggestions or by pasting, may add an English-only passphrase, imports the key, and goes on to App Lock and the first-run home without a backup step.

## Sub-features

- `mp-import-grid` shows 24 numbered slots in 3 columns, numbered down each column, and a Passphrase checkbox row inside the card.
- `mp-import-suggest` shows a popover as wide as a slot with the first 4 matching words and a "+N" count of the rest, under the slot being typed. On slot 24 it lists the first 4 of the 8 valid 24th words. It opens upward when below would pass the card's bottom (boards 1B.2.1 to 1B.3.1).
- `mp-import-errors` shows the invalid-word and invalid-checksum warnings and keeps นำเข้ากุญแจ disabled.
- `mp-import-passphrase` takes an English-only passphrase in a sheet, then shows a fingerprint check after the import.
- `mp-import-done` goes to App Lock and then Home with no backup screens.

## How to get to it (user POV)

- Welcome → นำเข้ากระเป๋าเดิม.
- Debug: the route catalog row `IMPORT SEED`.

## Driving it with verify.sh

Preconditions:

- iOS only: `verify.sh ios-start` finished in this run, Welcome is showing (`id:screen_welcome`). Android has no typing recipe yet.
- Test phrases are BIP-39 vectors, never real funds. Valid: `abandon` ×23 then `art`. Bad checksum: `abandon` ×24. Bad word: `abandonn`. Evidence PNGs keep what is on screen, so never run this with a real phrase.
- Master fingerprints of the valid phrase: `88bca92b` with the passphrase `TREZOR` (the app showed it and it matches the independent value), `5436d724` with no passphrase (independent value only).
- Build the words with a loop, never with `$(...)`: command substitution drops the last newline, and every word, the 24th too, needs its own return. `nl=$'\n'; all24=""; for i in $(seq 24); do all24+="abandon${nl}"; done`. Build `all22` the same way with 22.
- The first time a keyboard opens on a fresh simulator iOS shows a "Speed up your typing" tip. Run `verify.sh ios-tap "text:Continue"` once, right after the first tap on a slot.
- Each `verify.sh` command is its own test run of about 10 s. Never edit a driver script while it runs.

- **Open.** `verify.sh ios-tap "id:cta_import"`, `verify.sh ios-see "id:screen_onboarding/import-seed"`. Shot `mp-import-empty`: 24 empty slots, the Passphrase box unticked inside the card, no warning, นำเข้ากุญแจ grey.
- **Suggestions.** `verify.sh ios-tap "id:seed_slot_0"`, `verify.sh ios-type "id:seed_slot_0" "ab"`, `verify.sh ios-see "id:seed_suggestion_0"`. Shot `mp-import-suggest`: four rows under slot 4's column width, `abandon` first and highlighted, then ability, able, about, and "+4" at the right of the last row (`id:seed_suggestion_more`). There is no `seed_suggestion_4`: the other matches are reached by typing on. `verify.sh ios-tap "id:seed_suggestion_0"` takes `abandon` and moves to slot 2.
- **24th-word picker.** After the suggestion tap, `verify.sh ios-type "id:seed_slot_1" "${all22}"` fills slots 2 to 23 and slot 24 opens on its own with the first four valid last words, art, diesel, false, kite, and "+4" for organ, ready, surface, trouble. The popover opens upward over slots 20 to 23. `verify.sh ios-see "id:seed_suggestion_3"`. Shot `mp-import-pick24` (board 1B.3.1).
- **Fill 24 with a bad checksum.** On a fresh Import Seed, `verify.sh ios-tap "id:seed_slot_0"`, `verify.sh ios-type "id:seed_slot_0" "${all24}"`. The 24th return puts the keyboard away. `verify.sh ios-see "id:seed_warning"`. Shot `mp-import-checksum`: the banner reads "Checksum ไม่ถูกต้อง", slot 24 is painted orange, no slot is active, no list shows and the button is grey. The banner is one merged element, so `ios-see "text~:Checksum"` does not match: use the id.
- **Fix the last word.** Tap another slot first, then the slot to fix: `verify.sh ios-tap "id:seed_slot_22"`, `verify.sh ios-type "id:seed_slot_23" "art${nl}"`. The first key typed into a slot that already holds a word replaces that word. Typing into the slot that already has the keyboard puts the letters inside the old word. Then `verify.sh ios-gone "id:seed_warning"`. Shot `mp-import-filled`: slot 24 reads `art`, the button is navy.
- **Wrong 24th word while typing.** After `verify.sh ios-type "id:seed_slot_0" "${all23}"`, `verify.sh ios-type "id:seed_slot_23" "a"` lists only `art` and shows no warning. `verify.sh ios-type "id:seed_slot_23" "c"` makes it `ac`, which no valid last word starts with: slot 24 turns orange and "Checksum ไม่ถูกต้อง" shows above the keyboard (`verify.sh ios-see "id:seed_warning"`). Shot `mp-import-wrong24` (board 1B.4.2). The return then puts the keyboard away and the warning under the grid reads "ไม่อยู่ในรายการคำ BIP39", because `ac` is not a word.
- **Passphrase.** `id:seed_passphrase` is the Passphrase checkbox row inside the card. `verify.sh ios-tap "id:seed_passphrase"`, `verify.sh ios-see "id:sheet_passphrase"`, `verify.sh ios-type "id:seed_passphrase_field" "TREZOR${nl}"`. The return closes the sheet. Shot `mp-import-passphrase`: the same row in the card turns ticked and reads "Passphrase · ตั้งแล้ว" with `id:seed_passphrase_edit` (แก้ไข) on its right, and nothing shows under the card. A tap on แก้ไข reopens `id:sheet_passphrase`. A tap on the ticked row clears the passphrase, as ไม่มี passphrase does. A Thai character typed into the field is dropped and the helper line turns orange.
- **Import with a passphrase.** `verify.sh ios-tap "id:cta_import_seed"`, `verify.sh ios-see "id:sheet_fingerprint"`, `verify.sh ios-see "88BCA92B"`. Shot `mp-import-fingerprint`. `verify.sh ios-tap "id:cta_fingerprint_ok"` goes to `id:screen_onboarding/app-lock`.
- **Import with no passphrase.** `verify.sh ios-tap "id:cta_import_seed"` on the valid phrase goes straight to `id:screen_onboarding/app-lock`, and `verify.sh ios-gone "id:sheet_fingerprint"` passes.
- **Home.** Enable the lock as in [Movie Page app lock](./movie-page-app-lock.md), then `verify.sh ios-see "id:screen_home/first-run"`. Shot `mp-import-home`: the hero reads 0.0000 BTC for the empty testnet wallet, and there is no backup warning.

## Gotchas

- Import Seed and its passphrase sheet block capture on Android (FLAG_SECURE), so every Android `verify.sh shot` of them is black. On an iPhone the screen comes out black in screenshots and recordings, but the passphrase sheet and the system keyboard are not covered (docs/04-open-items.md item 40). See [onboarding](./movie-page-onboarding.md) for the commands that proved it.
- While a slot has the keyboard, the warning shows above it, pinned where the button normally is, because the one under the grid is behind the keyboard.
- While a slot has the keyboard, the debug link under the screen is hidden. Dismiss the keyboard or finish typing before `ios-tap "id:open_catalog"`.
- `ios-type` types into whatever has the keyboard after the tap. The tap on a slot is what moves the focus, so one `ios-type` per slot when the words are not separated by newlines.
- A tap on the slot that already has the keyboard lands the caret inside the word and skips the replace-on-first-key. Tap another slot first.
- The seed field turns off autocorrect and uses an ASCII keyboard on purpose. If the predictive bar shows up, that is a regression.
- XCUITest cannot paste. Pasting 24 words is a manual check.
- A wrong passphrase is still valid: only the fingerprint sheet tells the user.
- The fingerprint sheet shows the multisig path `m/48'/1'/0'/2'`, while the single-sig wallet uses `m/84'/1'/0'/0/0`. The fingerprint is the same for both.
