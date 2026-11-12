---
name: organize-vault-send
description: >-
  Use when a change touches a several-key wallet's send: VaultSend, keySigners,
  the 5.1, 5.2 or review screens, or hot_wallet/vault_spend.rs. Checks that a vault
  payment is finished and posted only after m distinct fingerprints of that
  wallet signed it, as Rust read the signatures from the PSBT.
---

# Organize vault send

**Property.** A several-key wallet's send finishes and posts a payment only while at least m distinct fingerprints of that wallet have a valid signature on every input of it, as Rust read them from the PSBT it returned, and only the payment the last prepare made.

**Harm.** The app posts, or offers to post, a payment that fewer of the wallet's owners approved than its threshold, or counts a key that never signed this payment.

Two gates hold it. Kotlin's `VaultSend.readyToBroadcast` counts the distinct fingerprints of the wallet among the key positions Rust returned in `VaultPsbt.signedKeys`, and `broadcast` refuses below m. Rust's `vault_finalize_psbt` refuses fewer than m keys with a valid signature on every input (`vault signatures: k of m`). This skill checks the Kotlin gate. The Rust gate has its own tests in `vault-workspace/mobile-signer-ffi/rust/src/hot_wallet/vault_spend.rs`, among them `finishing_succeeds_exactly_when_m_distinct_keys_signed`.

The Penpot Organize page (`2067de56-df70-80bd-8008-da9f7127d68a`), section 5, is the screen map: 5.1 is the form, 5.2 collects signatures, and the review after it, which the app draws as Penpot 4.5 in place of 5.8, posts and ends on 5.9's slip. Hardware signing (5.3 to 5.7) has no Kotlin step yet.

## When to run

Run this check when a change touches any of these:

- `vault-workspace/mobile-signer-ffi/app/shared/src/data/VaultSend.kt`: `VaultSend.prepare`, `sign`, `broadcast`, `signedFingerprints`, `readyToBroadcast`, and `keySigners`.
- `vault-workspace/mobile-signer-ffi/app/shared/src/feature/send/VaultSendScreens.kt`: `VaultSendScreen` (5.1, `cta_m1_review` calls `prepare`), `CollectSignaturesScreen` (5.2, each `cta_m2_sign_<i>` calls `sign` with the owner check, and `cta_m2_review`, on only at `readyToBroadcast`, opens the review) and `VaultReviewScreen` (the review, whose `cta_m8_send` calls `broadcast` and is on only at `readyToBroadcast`).
- `vault-workspace/mobile-signer-ffi/app/shared/src/navigation/NavGraph.kt`: the `confirmSign` the 5.2 route passes, and every way that makes a `VaultSend`.
- `vault-workspace/mobile-signer-ffi/app/shared/test/OrganizeVaultSendSpec.kt`.
- A new call of `sign` or `broadcast` anywhere.

Leave the check alone when the diff does not touch those.

## Run

From `vault-workspace/mobile-signer-ffi/app`:

```bash
./kotlin test -m shared -p android --include-classes com.mebit.app.data.OrganizeVaultSendSpec
```

On Windows the launcher is `.\kotlin.bat` with the same arguments. One command. Every Rust call is a fake, so it does not need the Rust library. The class walks every sequence itself. There is no separate model file.

## Bounds

- Wallets of 1, 2 and 3 keys over `IPHONE`, `JADE`, `TREZOR`, standing in for the three Organize devices, with every m from 1 to n, and `[IPHONE, IPHONE, JADE]` at m = 2: two keys of one seed, which share a fingerprint.
- Sequences of length 0 through 5. The alphabet is one call a screen makes each, with the answer the fake Rust gives:
  - `Prepare` is `prepare()`, answered with a new payment no key signed.
  - `Sign(i)`, one for each key, is `sign` on key i's row with the owner check passing, answered with the held payment and key i added.
  - `SignRefused` is `sign` on key 0's row with the owner check failing. Rust must not hear of it.
  - `LieOtherTxid` is `sign` on key 0's row, answered with key 0 added to a payment of another txid.
  - `LieOutOfRange` is `sign` on key 0's row, answered with key 0 and a position past the wallet's keys.
  - `Broadcast` is `broadcast()`.

The fakes keep the model: the keys whose signature Rust truly added to the payment the last `Prepare` made. After every prefix, a call of the fake finalize or the fake post is a violation unless that set holds at least m distinct fingerprints and the payment is the last prepared one, `readyToBroadcast()` is a violation unless the same holds, and `SignRefused` reaching the fake sign is a violation.

The walk count is checked before the property. `sequence walk stopped early` means the walker is wrong. That is a modeling error, not a product bug.

## What a failure means

The test prints one line per violating sequence, then fails with `violations=`, `shortest=`, `duplicateFingerprint=` (the shortest on the two-key fingerprint wallet with no lie in it) and `lie=` (the shortest with a lie).

Replay the sequence as calls on `VaultSend`. A step that is not one of the calls above is a modeling error. Fix the spec, not the property. A sequence whose every step is one of those calls, and whose fake finalize or post ran below m distinct fingerprints or on another payment, is a bug in the draft. Do not weaken the property, or drop a lie or the two-key fingerprint wallet from the alphabet, to make the test pass.

## Proving the check can still fail

On a scratch copy of `VaultSend.readyToBroadcast`, count `signedKeys` in place of `signedFingerprints`: `(state as? VaultSendState.Collecting)?.let { !it.stale && it.psbt.signedKeys.size >= vault.m } == true`. Rerun. `duplicateFingerprint` must name `Prepare; Sign(0); Sign(1)` on `[IPHONE, IPHONE, JADE]` at m = 2, ready to post at 1 distinct fingerprint, and `lie` must name a `LieOutOfRange` sequence. Put it back. Then, on a scratch copy of `VaultSend.sign`, drop `next.txid != held.txid ||` from the check of Rust's answer. Rerun. `lie` must name a `LieOtherTxid` sequence. Put it back and rerun green. If either scratch run stays green, the spec no longer watches that rule. Fix the spec.

Move the sync commit below only after you have read the covered functions at the new commit and confirmed each alphabet step is still one call in them. A rename or a new call site of `sign` or `broadcast` means the spec changes first. Then rerun, and for a spec change repeat the scratch breaks above before moving the commit.

## Coverage

| | |
|---|---|
| Sync commit | the commit that adds the review route, `M8ReviewVault` (the first line of `git log --reverse --format=%h -S M8ReviewVault -- vault-workspace/mobile-signer-ffi/app/shared/src/navigation/Routes.kt`) |
| Spec | `vault-workspace/mobile-signer-ffi/app/shared/test/OrganizeVaultSendSpec.kt`, `broadcastRequiresDistinctVaultFingerprints` |
| Draft | `VaultSend.kt`: `prepare`, `sign`, `broadcast`, `signedFingerprints`, `readyToBroadcast` |
| Call sites | `VaultSendScreens.kt`: 5.1 `cta_m1_review`, 5.2 `cta_m2_sign_<i>` per key, the review's `cta_m8_send`. `NavGraph.kt`: the 5.2 route's `confirmSign` |
| Rust gate | `rust/src/hot_wallet/vault_spend.rs`: `assembled` and its tests |

## Left out

Hardware signing (Jade, Trezor) has no Kotlin step: a hardware key's 5.2 row offers no signing. When it lands, its step is a real function a screen calls, and its answer goes through the same `VaultPsbt.signedKeys`. A wallet whose two keys share a fingerprint can never post with only those two, which fails closed. Wallets above 3 keys and sequences above 5 are outside the bound. The pay-twice guard around the post is the one-key `PendingPayments`, tested on its own. Single-sig send is a different gate.

After a merge or a refactor that touched the covered functions without this check, use `/maintain-formal-verification-skill`.
