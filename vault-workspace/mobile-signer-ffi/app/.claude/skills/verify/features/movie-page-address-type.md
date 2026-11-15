# Movie Page address type

After the key is made or imported, first setup asks which address type the single-sig wallet uses (Penpot 1.5.1, the
same screen at 1.5.3, 1.3.2, 1B.4.5 and 1B.4.6). Native SegWit is preselected. Each tap is saved at once as a pick the
user has not confirmed, and ต่อไป confirms it, so a restart before ต่อไป comes back to this screen with the pick selected
and never finalizes a type by itself. Receive, change, signing and fees follow the confirmed type. On Mainnet only Native
SegWit can be picked. The hardware path (Penpot 1.2.2) has no such screen, because a hardware key makes no single-sig
wallet.

## Sub-features

- `mp-type-screen` shows four rows (Legacy BIP44, Nested SegWit BIP49, Native SegWit BIP84, Taproot BIP86) with Native
  SegWit selected and its "ค่าเริ่มต้น" badge, the eyebrow NEW WALLET or IMPORT WALLET · ADDRESS TYPE, and each row's
  address prefixes for the session's network (testnet `m หรือ n`, `2`, `tb1q`, `tb1p`; mainnet `1`, `3`, `bc1q`, `bc1p`).
- `mp-type-create` follows Verify Words, either ทำทีหลัง on Key Created or Backup Words, and goes on to App Lock. For a
  new key with a passphrase whose words were verified, ต่อไป first shows the fingerprint to write down (Penpot 1.5.4),
  after a restart too, and แก้ passphrase there goes to Backup Words with the type still unconfirmed.
- `mp-type-import` follows Import Seed. A passphrase import shows the fingerprint to compare after ต่อไป (Penpot
  1B.4.4). ไม่ตรง · แก้ไข and the back chip return to Import Seed with the 24 words and the passphrase still filled.
- `mp-type-receive` makes Receive show an address of the chosen type, and a cold start keeps it.
- `mp-type-restart` brings a cold start before ต่อไป back to this screen, after the unlock, with the last tapped type
  selected and no back chip. ต่อไป then goes on to App Lock, and only then is the type final: the next cold start opens
  Home.
- `mp-type-mainnet` keeps the Legacy, Nested SegWit and Taproot rows visible on Mainnet, grey, with the badge
  ยังไม่รองรับบน Mainnet, and a tap on them changes nothing. The core refuses those types on Mainnet by the same rule.

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
- **Receive.** After App Lock and the Wallet list: `verify.sh ios-tap "id:card_spending"`, `verify.sh ios-tap "id:cta_s1_receive"`,
  `verify.sh ios-see "id:screen_send/s3"`. The address starts with the chosen type's prefix. Check it against an
  independent derivation, never against the app itself.
- **Cold start.** As in [wallet restart](./wallet-restart.md). After the unlock the app opens on the Wallet list
  (`id:screen_vaults`), and the card opens `id:screen_send/s1`. Receive shows the same address.
- **Restart before ต่อไป.** Enrol Face ID as in [app lock](./movie-page-app-lock.md), then `verify.sh ios-tap
  "id:cta_create"`, `verify.sh ios-tap "id:cta_phone"`, `verify.sh ios-tap "id:cta_skip_backup"`, `verify.sh ios-see
  "id:screen_onboarding/address-type"` and `verify.sh ios-tap "id:address_type_taproot"`. Then `xcrun simctl terminate
  $U com.mebit.app` and `xcrun simctl launch $U com.mebit.app`. The unlock screen (ปลดล็อก mebit) comes up with the
  Face ID card. Give it about 6 s, in a background command because a foreground wait is not allowed, before `xcrun simctl
  spawn $U notifyutil -p com.apple.BiometricKit_Sim.pearl.match`, and send no `ios-*` action while the card is up.
  `verify.sh ios-see "id:screen_onboarding/address-type"` and `verify.sh ios-gone "id:back"` pass. Shot
  `mp-type-restart`: Taproot has the orange frame and the filled radio. `idb ui describe-all --udid $U --json` gives
  `address_type_taproot` alone the `Selected` trait. `verify.sh ios-tap "id:cta_address_type"` and `verify.sh ios-see
  "id:screen_onboarding/app-lock"`. Another `terminate`, `launch` and `pearl.match` then open
  `id:screen_vaults`, because ต่อไป confirmed the type.
- **Mainnet.** The app has no network switch and saves testnet wallets only, so no simulator run reaches the Mainnet
  rows. `AddressTypeScreenTest.onMainnetOnlyNativeSegwitsRowIsEnabled`, `AddressTypeChoiceTest.onMainnetATapOnATypeTheCoreRefusesChangesNothing`
  and the core's `the_rows_the_app_enables_are_the_accounts_the_core_derives` cover them.

## Seen in practice

- Run `20261107-233612-ios` (create, Taproot): Receive showed `tb1p9y49…c94sw`, which embit derives at
  `m/86'/1'/0'/0/0` for that run's throwaway words, and a cold start showed the same address.
- Run `20261108-004826-ios` (import, `TREZOR`, Nested SegWit): `88BCA92B` over `m/49'/1'/0'`, and Receive showed
  `2MuE6YWmddvYPJdpQFzgJiEKHCnAxYLEJ4F`, which embit derives at `m/49'/1'/0'/0/0`.
- Run `20261108-014538-ios`: one `ios-type` of `TREZOR` keeps all six letters in both passphrase sheets, and the import
  draft survives ไม่ตรง · แก้ไข and the back chip.
- Run `20261108-045750-ios` (iPhone 17 Pro, iOS 26.5, create, Taproot): after the kill, the relaunch and Face ID the app
  opened Address Type with Taproot selected and no back chip, ต่อไป reached App Lock, and a second cold start opened
  the Wallet list. The testnet rows were all enabled, as before.
- `verify.sh ios-see` takes no wait in seconds on iOS. `ios-see SELECTOR 30` stops with a usage line.
- The first `ios-tap` on Welcome right after `ios-start` can land during the launch animation and do nothing. Tap again
  when the next screen does not show.
- The first tap into a text field on a fresh simulator brings up iOS's slide-to-type tip, which can take the next keys.
  Dismiss it with `verify.sh ios-tap "Continue"` before typing.
- A text field's value is not its accessibility label, so `ios-see "text~:TREZOR"` does not find a typed passphrase.
  Reveal it with `id:seed_passphrase_eye` and read the screenshot.
