# Movie Page address type

After the key is made or imported, first setup asks which address type the single-sig wallet uses (Penpot 1.5.1, the
same screen at 1.5.3, 1.3.2, 1B.4.5 and 1B.4.6). Native SegWit is preselected. The choice is saved with the wallet, and
Receive, change, signing and fee estimates follow it. The hardware path (Penpot 1.2.2) has no such screen, because a
hardware key makes no single-sig wallet.

## Sub-features

- `mp-type-screen` shows four rows (Legacy BIP44, Nested SegWit BIP49, Native SegWit BIP84, Taproot BIP86) with Native
  SegWit selected and its "ค่าเริ่มต้น" badge, the eyebrow NEW WALLET or IMPORT WALLET · ADDRESS TYPE, and each row's
  address prefixes for the session's network (testnet `m หรือ n`, `2`, `tb1q`, `tb1p`; mainnet `1`, `3`, `bc1q`, `bc1p`).
- `mp-type-create` follows Verify Words, either ทำทีหลัง on Key Created or Backup Words, and goes on to App Lock. With a
  passphrase set after Verify Words, ต่อไป first shows the fingerprint to write down (Penpot 1.5.4).
- `mp-type-import` follows Import Seed. A passphrase import shows the fingerprint to compare after ต่อไป (Penpot
  1B.4.4). ไม่ตรง · แก้ไข and the back chip return to Import Seed with the 24 words and the passphrase still filled.
- `mp-type-receive` makes Receive show an address of the chosen type, and a cold start keeps it.

## Navigate

- Create: Welcome → สร้างกระเป๋าใหม่ → มือถือเครื่องนี้ → สำรองตอนนี้ → จดครบแล้ว ไปยืนยัน → pick three words → ยืนยัน →
  Address Type → ต่อไป → App Lock → home.
- Import: Welcome → นำเข้ากระเป๋าเดิม → 24 words (and a passphrase) → นำเข้ากุญแจ → Address Type → ต่อไป → (fingerprint
  sheet for a passphrase import) → App Lock → home.

## Recipes (iOS simulator)

- **The screen.** After Verify Words or Import Seed, `verify.sh ios-see "id:screen_onboarding/address-type"`. Shot
  `mp-type-default`: Native SegWit has the orange frame, the filled radio and the badge, and the rows read the testnet
  prefixes.
- **Pick a type.** `verify.sh ios-tap "id:address_type_taproot"` (or `address_type_legacy`, `address_type_nested_segwit`,
  `address_type_native_segwit`), then `verify.sh ios-tap "id:cta_address_type"`. With no passphrase it goes to
  `id:screen_onboarding/app-lock`.
- **Passphrase import.** Import Seed with `abandon` ×23 then `art` and the passphrase `TREZOR`, then
  `verify.sh ios-tap "id:cta_import_seed"`, the screen, Nested SegWit, `cta_address_type`, then
  `verify.sh ios-see "id:sheet_fingerprint"`. Shot `mp-type-fingerprint`: `88BCA92B` over `m/49'/1'/0'`. The path line
  follows the chosen type. `verify.sh ios-tap "id:cta_fingerprint_ok"` goes to App Lock.
- **Receive.** After App Lock and home: `verify.sh ios-tap "id:cta_singlesig"`, `verify.sh ios-tap "id:cta_s1_receive"`,
  `verify.sh ios-see "id:screen_send/s3"`. The address starts with the chosen type's prefix. Check it against an
  independent derivation, never against the app itself.
- **Cold start.** As in [wallet restart](./wallet-restart.md). After the unlock the app opens on the single-sig screen
  (`id:screen_send/s1`), not the first-run home. Receive shows the same address.

## Seen in practice

- Run `20261107-233612-ios` (create, Taproot): Receive showed `tb1p9y49…c94sw`, which embit derives at
  `m/86'/1'/0'/0/0` for that run's throwaway words, and a cold start showed the same address.
- Run `20261108-004826-ios` (import, `TREZOR`, Nested SegWit): `88BCA92B` over `m/49'/1'/0'`, and Receive showed
  `2MuE6YWmddvYPJdpQFzgJiEKHCnAxYLEJ4F`, which embit derives at `m/49'/1'/0'/0/0`.
- Run `20261108-014538-ios`: one `ios-type` of `TREZOR` keeps all six letters in both passphrase sheets, and the import
  draft survives ไม่ตรง · แก้ไข and the back chip.
- The first `ios-tap` on Welcome right after `ios-start` can land during the launch animation and do nothing. Tap again
  when the next screen does not show.
- The first tap into a text field on a fresh simulator brings up iOS's slide-to-type tip, which can take the next keys.
  Dismiss it with `verify.sh ios-tap "Continue"` before typing.
- A text field's value is not its accessibility label, so `ios-see "text~:TREZOR"` does not find a typed passphrase.
  Reveal it with `id:seed_passphrase_eye` and read the screenshot.
