---
name: organize-vault-send
description: >-
  Use when a change touches a several-key wallet's send: VaultSend, VaultPayment,
  keySigners, the phone or device signing of a vault key, the 5.1, 5.2 or review
  screens, or the wallet_* calls it makes in rust/src/spend.rs. Checks that a vault
  payment takes signatures only until m distinct fingerprints of that wallet signed
  it, and is finished and posted only after, as Rust read the signatures from the
  PSBT.
---

# Organize vault send

**Property.** A several-key wallet's send finishes and posts a payment only while at least m distinct fingerprints of that wallet have a valid signature on every input of it, as Rust read them from the PSBT it returned, and only the payment the last prepare made. Signing stops there: no sign reaches Rust, and no device is reached, once m distinct fingerprints of the wallet signed the payment the last prepare made, or for a key whose fingerprint already signed it. Nothing reaches Rust while a signature holds the send.

**Harm.** The app posts, or offers to post, a payment that fewer of the wallet's owners approved than its threshold, counts a key that never signed this payment, asks an owner or a device to sign a payment that already has every signature its threshold needs, or keeps a signature of a payment it no longer holds.

Every Rust call of the send goes through `DescribedCore` with the wallet's BSMS line 2: `wallet_draft`, `wallet_sign_with_words` and `wallet_finish`, the calls a Jade or Safe 7 one-key wallet makes. Every device goes through `HardwareLink.use` and `WalletLink.signWalletPsbt`. `VaultSend` refuses to exist unless `wallet_describe` reads the book's own wallet: its m, its key fingerprints in order and its receive address 0. A device is read at the account path that reading gives its key (`m/48'/1'/a'/2'`), the one Rust's `held_key` checks.

Two gates hold the post. Kotlin's `VaultSend.readyToBroadcast` counts the distinct fingerprints in `signedFingerprints`: the master fingerprints Rust found signed (`SpendReview.signed`, uppercased into `VaultPayment.signed`) that are also the book's `keyFingerprints`. `broadcast` refuses below m, and refuses a finished transaction whose txid is not the one 5.2 showed before it writes a guard. Rust's `wallet_finish` refuses fewer than m fingerprints with a valid signature on every input (`signatures: k of the m keys needed signed every input`).

One gate holds the signing, for a phone key and a device key alike. `sign` (a phone row, after the owner check) and `signOnDevice` (a Jade or Safe 7 row) both call `collect`. `collect`'s `begin` claims the send under the lock: it refuses while `busy` is set, and refuses a key `needsKey` refuses, once m distinct fingerprints signed or when its own fingerprint already did. The signature then runs with no lock held, for as long as a device takes, a Jade's PIN and registration included. `keep` stores the answer under the lock only while the payment `begin` took is still the one held, the answer has its unsigned txid, and its signers are exactly the held signers and the row's fingerprint, so a fingerprint of no key of the wallet, a second key, or no new signature keeps nothing. The claim ends whatever happened, a cancel included. `prepare`, `broadcast` and `repostGuarded` refuse while the send is claimed, so one writer holds the payment by structure. Rust's `wallet_sign_with_words` signs with whichever key of the wallet the words make, and signs past m. A device's answer passes Rust's own gate in `rust/src/ble.rs` first: the payment shown, the device's key, a Jade's registration of the wallet, and that key's signature on every input. This skill checks the Kotlin gates. The Rust gates have their own tests in `vault-workspace/mobile-signer-ffi/rust/src/spend.rs`, among them `every_signer_subset_finishes_exactly_when_m_distinct_keys_signed` and the attack table `every_way_a_payment_can_differ_from_the_one_shown_is_refused`, and in `rust/src/ble.rs`, among them `each_device_signs_with_its_key_once_and_the_payment_finishes`.

The Penpot Organize page (`2067de56-df70-80bd-8008-da9f7127d68a`), section 5, is the screen map: 5.1 is the form, 5.2 collects signatures, 5.3 to 5.7 sign on a Jade or a Safe 7, and the review after 5.2, which the app draws as Penpot 4.5 in place of 5.8, posts and ends on 5.9's slip. Hardware signing has its Kotlin step (`signOnDevice`) and no screen yet.

## When to run

Run this check when a change touches any of these:

- `vault-workspace/mobile-signer-ffi/app/shared/src/data/VaultSend.kt`: `VaultPayment`, the `VaultSend` constructor, `prepare`, `sign`, `signOnDevice`, `collect`, `begin`, `keep`, `broadcast`, `repostGuarded`, `signedFingerprints`, `readyToBroadcast`, `needsKey`, and `keySigners`.
- `vault-workspace/mobile-signer-ffi/app/shared/src/data/HardwareLink.kt` and `HardwareWallets.nearest`: how a device is reached and which one.
- `vault-workspace/mobile-signer-ffi/app/shared/src/feature/send/VaultSendScreens.kt`: `VaultSendScreen` (5.1, `cta_m1_review` calls `prepare`), `CollectSignaturesScreen` (5.2, each `cta_m2_sign_<i>`, shown only while `needsKey`, calls `sign` with the owner check, and `cta_m2_review`, on only at `readyToBroadcast`, opens the review) and `VaultReviewScreen` (the review, whose `cta_m8_send` calls `broadcast` and is on only at `readyToBroadcast`).
- `vault-workspace/mobile-signer-ffi/app/shared/src/navigation/NavGraph.kt`: the `confirmSign` the 5.2 route passes, and every way that makes a `VaultSend`.
- `vault-workspace/mobile-signer-ffi/app/shared/test/OrganizeVaultSendSpec.kt`.
- A new call of `sign`, `signOnDevice` or `broadcast` anywhere.

Leave the check alone when the diff does not touch those.

## Run

From `vault-workspace/mobile-signer-ffi/app`:

```bash
./kotlin test -m shared -p android --include-classes com.mebit.app.data.OrganizeVaultSendSpec
```

On Windows the launcher is `.\kotlin.bat` with the same arguments. One command. Every Rust call is a fake `DescribedCore` and every device a `FakeDeviceLink` behind the send's `HardwareLink`, so it does not need the Rust library. The class walks every sequence itself. There is no separate model file.

## Bounds

- Wallets of 1, 2 and 3 keys over `IPHONE`, `JADE`, `TREZOR`, standing in for the three Organize devices, with every m from 1 to n, and `[IPHONE, IPHONE, JADE]` at m = 2: two keys of one seed, which share a fingerprint. Key 0 is always `IPHONE`, and the last key is a device in every wallet that has one.
- Sequences of length 0 through 5, about 587,000 of them. The alphabet is one call a screen makes each, with the answer the fake Rust or the fake device gives:
  - `Prepare` is `prepare()`, answered with a new payment no key signed.
  - `Sign(i)`, one for each key, is 5.2's sign button on key i's row by its kind: `sign` with the owner check passing for `IPHONE`, `signOnDevice` on that key's device for `JADE` and `TREZOR`. It is answered with the held payment and key i's fingerprint added, in lowercase as Rust names it.
  - `SignRefused` is `sign` on key 0's row with the owner check failing. Rust must not hear of it.
  - `LieOtherTxid` is a sign on the last key's row, a device's when the wallet has one, answered with that key added to a payment of another txid.
  - `LieForeignFingerprint` is a sign on the last key's row, answered with that key and `5eed0000`, a fingerprint that is no key of the wallet. An answer that names more than the key it was asked for is a lie as a whole, so the model counts none of it.
  - `PrepareWhileDeviceSigns`, only for a wallet with a device key, is a sign on the last key's device during whose wait 5.1's review button runs `prepare()`. The device then answers the payment it was given, signed.
  - `SignWhileDeviceSigns`, only for a wallet with a device key, is a sign on the last key's device during whose wait key 0's sign button runs `sign`. The device then answers the payment it was given, signed.
  - `Broadcast` is `broadcast()`.

The fakes keep the model: the keys whose signature Rust truly added to the payment the last `Prepare` made. Each fake answer is built on the PSBT text `VaultSend` passed, as `wallet_sign_with_words` builds on the PSBT it is given. After every prefix, a call of the fake finish or the fake post is a violation unless that set holds at least m distinct fingerprints and the payment is the last prepared one, `readyToBroadcast()` is a violation unless the same holds, `SignRefused` reaching the fake sign is a violation, and so is any call of the fake sign, or any device reached, while that set already holds m distinct fingerprints or the fingerprint of the key it signs. While a fake device waits for its confirm, any fake Rust call and any device reached is a violation.

The walk count is checked before the property. `sequence walk stopped early` means the walker is wrong. That is a modeling error, not a product bug.

## What a failure means

The test prints one line per violating sequence, then fails with `violations=`, `shortest=`, `duplicateFingerprint=` (the shortest on the two-key fingerprint wallet with no lie in it) and `lie=` (the shortest with a lie).

Replay the sequence as calls on `VaultSend`. A step that is not one of the calls above is a modeling error. Fix the spec, not the property. A sequence whose every step is one of those calls, and whose fake finish or post ran below m distinct fingerprints or on another payment, or whose Rust call or device came while a device held the send, is a bug in the draft. Do not weaken the property, or drop a lie, a letter or the two-key fingerprint wallet from the alphabet, to make the test pass.

## Proving the check can still fail

Run each scratch break below on a scratch copy of `VaultSend.kt`, rerun the command above, read the lines, and put the file back before the next. If any scratch run stays green, the spec no longer watches that rule. Fix the spec.

1. In `keep`, replace the exact-signers check `next.signed != held.signed + key` with `key !in next.signed`. `lie` must name `m=1 vault=[IPHONE, JADE, TREZOR] steps=Prepare; LieForeignFingerprint: readyToBroadcast() at 0 distinct fingerprints of 1`, and the lines must include the same on `[IPHONE]`, the phone's path.
2. In `keep`, drop the txid check `next.txid != held.txid`. `lie` must name `m=1 vault=[IPHONE, JADE, TREZOR] steps=Prepare; LieOtherTxid: readyToBroadcast() at 0 distinct fingerprints of 1`, and the lines must include the same on `[IPHONE]`.
3. In `heldFor`, drop `&& needsKey(index)`. The lines must include `m=1 vault=[IPHONE, JADE] steps=Prepare; Sign(0); Sign(1): a device reached at 1 distinct fingerprints of 1` and `m=2 vault=[IPHONE, IPHONE, JADE] steps=Prepare; Sign(0); Sign(1): sign of key 1, whose fingerprint already counts, reached Rust`.
4. In `prepare`, drop the `busy` check. `shortest` must be `m=1 vault=[IPHONE, JADE, TREZOR] steps=Prepare; PrepareWhileDeviceSigns: prepare reached Rust while a device signature held the send`.
5. Drop both the `busy` check in `prepare` and the same-payment check (`current.payment !== held`) in `keep`. The lines must include `m=1 vault=[IPHONE, JADE] steps=Prepare; PrepareWhileDeviceSigns; Broadcast: finish of 00000000, not the payment prepared last`. Break 4 alone prints no such line, because the same-payment check alone keeps an old payment's signature out.
6. In `begin`, drop the `busy` check. `shortest` must be `m=1 vault=[IPHONE, JADE, TREZOR] steps=Prepare; SignWhileDeviceSigns: sign reached Rust while a device signature held the send`.

`intersect fingerprints.toSet()` in `signedFingerprints` stays green when dropped, because `keep`'s exact-signers check keeps every foreign fingerprint out first. It stays as a second line behind that check. Put the file back and rerun green. `VaultSendTest` goes red on the same breaks, and its device tests name the case.

Move the sync commit below only after you have read the covered functions at the new commit and confirmed each alphabet step is still one call in them. A rename or a new call site of `sign`, `signOnDevice` or `broadcast` means the spec changes first. Then rerun, and for a spec change repeat the scratch breaks above before moving the commit.

## Coverage

| | |
|---|---|
| Sync commit | the commit that put device signatures through the gate (the first line of `git log --reverse --format=%h -S PrepareWhileDeviceSigns -- vault-workspace/mobile-signer-ffi/app/shared/test/OrganizeVaultSendSpec.kt`) |
| Spec | `vault-workspace/mobile-signer-ffi/app/shared/test/OrganizeVaultSendSpec.kt`, `broadcastRequiresDistinctVaultFingerprints` |
| Draft | `VaultSend.kt`: `prepare`, `sign`, `signOnDevice`, `collect`, `begin`, `keep`, `broadcast`, `signedFingerprints`, `readyToBroadcast`, `needsKey` |
| Call sites | `VaultSendScreens.kt`: 5.1 `cta_m1_review`, 5.2 `cta_m2_sign_<i>` per key the payment still needs, the review's `cta_m8_send`. `NavGraph.kt`: the 5.2 route's `confirmSign` |
| Rust gate | `rust/src/spend.rs`: `finished` and `review`'s `signers`, with their tests. `rust/src/ble.rs`: `sign_wallet` and `ensure_registered`, with their tests |

## Left out

The 5.3 to 5.7 screens, which no board approves yet: no screen calls `signOnDevice`, and a device row's sign button on 5.2 is drawn but does nothing. The Jade's registration is Rust's, inside `sign_wallet`, and `rust/src/ble.rs` tests it. A device that is not alone in range is refused by `HardwareWallets.nearest` before the send is claimed, and `HardwareWalletsTest` tests it. A wallet whose two keys share a master fingerprint is refused by Rust's `Described::parse`, so it has no send at all. The spec keeps `[IPHONE, IPHONE, JADE]` because the Kotlin gates must hold on their own. Wallets above 3 keys, sequences above 5 and a lying `prepare` are outside the bound. The pay-twice guard around the post is the several-key guard file `pending-vault-payment` (`Wallets.vaultPayments`, apart from the one-key `PendingPayments` file), with each wallet's line under `vaultGuardKey`. `VaultSendTest` tests it on its own. Single-sig send is a different gate.

After a merge or a refactor that touched the covered functions without this check, use `/maintain-formal-verification-skill`.
