# Movie Page keys + critical boards

A user adds a key from three sources, verifies fingerprint (mismatch never saves), and reaches the keyring. Critical boards T13–T15 are reachable from hardware demo and catalog.

## Sub-features

- `mp-add-key-three-sources` shows this device, mebit other, and hardware CTAs.
- `mp-verify-mismatch` refuses save and lands on T14.
- `mp-t13-unsupported` shows Unsupported Model from hardware demo.

## How to get to it (user POV)

- Catalog → ADD KEY, or Home first-run → Vault path → keyring → เพิ่มกุญแจ.
- Hardware → จำลองรุ่นอื่น (Unsupported) → T13.
- Verify → จำลองไม่ตรง → T14.

## Driving it with verify.sh

Preconditions:

- App running; prefer catalog jump for isolation.

- **Add Key.** Open catalog (`id:open_catalog`), tap the ADD KEY row (`id:catalog_keys/add` or text containing `ADD KEY`), `verify.sh see "id:screen_keys/add"`, `verify.sh see "id:cta_add_this_device"`, `verify.sh see "id:cta_add_mebit_other"`, `verify.sh see "id:cta_add_hardware"`. Shot `mp-add-key-three-sources`.
- **Mismatch.** From Add Key, `tap id:cta_add_this_device`, on Verify fill wrong path via `tap id:cta_verify_mismatch`, `see id:screen_critical/t14`, `see id:mismatch_message`. Shot `mp-verify-mismatch`.
- **T13.** Catalog → HARDWARE WALLET (`id:catalog_keys/hardware`), `tap id:cta_hw_unsupported`, `see id:screen_critical/t13`. Shot `mp-t13-unsupported`.

## Gotchas

- Match path needs typing the expected fingerprint into `id:observed_fp` or using `id:cta_fill_expected` then `id:cta_verify_match`.
- Pair another mebit by pasting three lines (fingerprint, xpub, path) or BSMS 1.0 into `id:pair_paste`, then `id:cta_pair_paste`. `id:cta_pair_demo` still stages a fixture key when the run has no paste. No QR. docs/04 item 26.
