---
name: organize-vault-send
description: >-
  Use when a change touches the Organize page vault send, MultisigSendDraft,
  or the M1-M4 screens. Checks that a vault broadcast happens only after
  m distinct vault fingerprints have signed.
---

# Organize vault send

**Property.** A vault send is broadcast only when at least M distinct fingerprints from that vault have signed.

**Harm.** The app can report a broadcast txid for stand-in ids that are not vault keys.

The Penpot Organize page (`2067de56-df70-80bd-8008-da9f7127d68a`), section 5, is the screen map. Primary on `5.3.4` and `5.3.5` (`2 of 2 · confirmed`) goes to `5.4 Ready to Broadcast`. Primary on `5.3.6` Jade rejected, `5.3.7` Trezor connection lost, and `5.3.8` Face ID failed goes back to collect or retry. The Kotlin that exists is the Movie Page path, not those boards.

## When to run

Run this check when a change touches any of these:

- `vault-workspace/mobile-signer-ffi/app/shared/src/data/SendSession.kt` — `MultisigSendDraft.resetSignatures`, `markSigned`, `readyToBroadcast`, `broadcastMock`
- `vault-workspace/mobile-signer-ffi/app/shared/src/feature/send/MultisigSendScreens.kt` — `M1SendVaultScreen` (`cta_m1_collect` calls `resetSignatures`), `M2CollectSignaturesScreen` (soft sign calls `markSigned` after `policyAllowsSign`; `cta_sign_demo_1` calls `markSigned("DEMO0001")`; `cta_sign_demo_2` calls `markSigned("DEMO0002")`; `cta_m2_ready` uses `readyToBroadcast`), `M3SignOnJadeScreen` (live success calls `markSigned` with the device fingerprint; `cta_m3_signed` calls `markSigned("JADEDEMO")`), `M4ReadyToBroadcastScreen` (`cta_m4_broadcast` calls `broadcastMock`)
- `vault-workspace/mobile-signer-ffi/app/shared/test/OrganizeVaultSendSpec.kt`

Leave the check alone when the diff does not touch those functions. `policyAllowsSign` is the soft-sign guard on the M2 CTA. It is not a step in this check. The default destination and amount pass it.

## Run

From `vault-workspace/mobile-signer-ffi/app`:

```bash
./kotlin test -m shared -p android --include-classes com.mebit.app.data.OrganizeVaultSendSpec
```

One command. It does not call UniFFI, so it does not need the Rust library. The class walks every sequence itself. There is no separate model file.

## Bounds

- Vault key set size 1, 2, and 3. The keys are `IPHONE`, `JADE`, `TREZOR`, standing in for the three Organize devices.
- `m` runs from 1 through that size.
- Each sequence has length 0 through 5.
- The alphabet is one `resetSignatures()` plus `markSigned` of each vault key and of `DEMO0001`, `DEMO0002`, and `JADEDEMO`. Those three strings are the demo CTAs in `M2CollectSignaturesScreen` and `M3SignOnJadeScreen`.

After every prefix, `readyToBroadcast(m)` and `broadcastMock(m)` may be true only when the signed set contains at least `m` of that vault's fingerprints. A repeated fingerprint counts once.

The walk count is checked before the property. `sequence walk stopped early` means the walker is wrong. That is a modeling error, not a product bug.

## What a failure means

The test prints one line per violating sequence, then fails with `violations=`, `shortest=`, `demoPair=`, and `duplicateVault=`.

Replay the sequence as calls on `MultisigSendDraft`. A step that is not `resetSignatures` or `markSigned` of a vault key or of those three demo strings is a modeling error. Fix the spec, not the property. A sequence whose every step is one of those calls, and whose `broadcastMock(m)` is true while fewer than `m` vault fingerprints are in the signed set, is a bug in the draft. Do not weaken the property or drop a demo string from the alphabet to make the test pass.

## Finding on the current code

Confirmed at commit `b53593cf3a316a7885015af1804fcade4ead5ff2`. Product code was not changed.

`readyToBroadcast` and `broadcastMock` compare `signedFingerprints.size` with `m`. They do not require the fingerprints to belong to the vault. Shortest counterexample: `markSigned("DEMO0001")`, then `broadcastMock(1)` returns true, with vault keys `IPHONE`, `JADE`, `TREZOR` and `vaultSigned=0`. The same hole at M=2: `markSigned("DEMO0001")`; `markSigned("DEMO0002")`; `broadcastMock(2)` returns true. On this commit `duplicateVault=absent`, because `markSigned` refuses a fingerprint it already holds.

Leave `SendSession.kt` and `MultisigSendScreens.kt` as they are unless the user asks for a product fix. A green run that was bought by deleting `DEMO0001` or `JADEDEMO` from the alphabet is a weakened check.

## Proving the check can still fail

On a scratch copy of `markSigned`, append the fingerprint even when it is already present (remove the `fp !in signedFingerprints` guard). Rerun. `duplicateVault` must name a sequence of the same vault key twice, with `broadcastMock(2)=true` and `vaultSigned=1`. Put the guard back. Rerun. `duplicateVault` must be `absent` again, and the demo-fingerprint lines must still be there. If the scratch run does not grow a `duplicateVault` line, the spec no longer watches distinct fingerprints. Fix the spec.

Move the sync commit below only after you have read the covered functions at the new commit and confirmed each alphabet step is still one call in those functions. A rename or a new `markSigned` call site means the spec must change first. Then rerun, and for a spec change repeat the scratch break above before moving the commit.

## Coverage

| | |
|---|---|
| Sync commit | `b53593cf3a316a7885015af1804fcade4ead5ff2` |
| Spec | `vault-workspace/mobile-signer-ffi/app/shared/test/OrganizeVaultSendSpec.kt` — `broadcastRequiresDistinctVaultFingerprints` |
| Draft | `SendSession.kt` — `resetSignatures`, `markSigned`, `readyToBroadcast`, `broadcastMock` |
| Call sites | `MultisigSendScreens.kt` — M1 collect CTA, M2 soft sign, M2 demo CTAs, M2 ready CTA, M3 live success, M3 demo CTA, M4 broadcast CTA |

## Left out

Organize boards `5.3.1` through `5.3.8`, `5.4.1`, and `5.5` have no Kotlin step yet. When one of those screens is added, its step has to be a real function the screen calls. Do not record a failed sign as a no-op written inside the spec. The route catalog can open M4 with an empty signature list. That jump is not a step here. The broadcast button still calls `broadcastMock`. `m` of 0, vaults larger than 3, and sequences longer than 5 are outside the bound. Single-sig send is a different gate.

After a merge or a refactor that touched the covered functions without this check, use `/maintain-formal-verification-skill`.
