# trezor-ble

**Owner**: @phoovich. This is wallet-first pivot work (Task No.5), outside the original 4-way split. See `docs/05-progress-and-next-steps.md`.

**Depends on**:
- `vault-core` with its `trezor` feature (`hw::trezor::TrezorSession` does all the protocol work)
- `btleplug` 0.13, `tokio`

**Not in the workspace's `default-members`.** btleplug needs `libdbus-1-dev` + `pkg-config` on Linux and pulls in WinRT on Windows, and nothing depends on this crate yet. Build and test it explicitly with `-p trezor-ble`.

## Responsibilities

Talks to a **Trezor Safe 7** over Bluetooth LE, and to no other device. It connects, pairs, reads account xpubs, and co-signs vault PSBTs. It is the production driver: mobile-signer-ffi wraps it with UniFFI in Phase 1, as with `jade-ble` (`docs/08-multisig-wallet-spec.md` §5.1).

It only moves bytes. Every protocol decision lives in `vault-core::hw::trezor`:
- the Trezor-Host Protocol (THP), on Trezor's own `trezor-thp` crate
- code-entry pairing and the pairing credential
- both checks that the device is a Safe 7
- which PSBTs may be signed, and checking the signatures that come back

What this crate adds is what needs a radio and a clock:
- scanning
- GATT
- the MTU check
- timeouts and retransmission timing
- noticing a dropped link
- asking the user for the pairing code

```rust
let codes = || async { Some(ask_the_user_for_the_six_digits().await) };
let Connection { device: mut trezor, new_credential } =
    TrezorBle::connect(options, &codes).await?;             // pairs the first time; keep new_credential (a secret)
let account = trezor.xpub(&path, XpubOptions::default()).await?; // record account.master_fingerprint and account.xpub
let signed = trezor.sign_psbt(&psbt, recorded_fingerprint).await?; // the user confirms on the Safe 7
trezor.disconnect().await?;
```

**Errors.**
- A request that fails on the device's side leaves the connection usable. That covers declined, cancelled (`trezor.canceller().cancel()` from any thread), or refused.
- After a timeout or a dropped link, reconnect before the next call.
- `TrezorBleError::kind()` gives a stable, flat category for a UI or the FFI layer.

## Exactly a Safe 7

The firmware tree also has a second BLE-capable model, `T3T2`, so "it's a Trezor over Bluetooth" proves nothing. A connection succeeds only after two checks, and both run again on every connection:

1. **Before the handshake.** The channel allocation response carries `ThpDeviceProperties.internal_model`, which must be `T3W1`.
   - Anything else is refused before any pairing prompt or PIN.
   - Those bytes are also the Noise handshake's prologue, so the handshake only succeeds if they are what the device sent.
2. **After pairing.** `GetFeatures` must report all of the following:
   - vendor `trezor.io`, internal model `T3W1`, model `Safe 7`
   - not bootloader mode
   - firmware ≥ 2.9.3
   - an initialized wallet, with Bitcoin support

The advertised name, and the model code in the advertisement, only narrow the scan. A device advertising another model's code is left out. Neither is ever taken as proof.

## How the Safe 7 behaves over BLE

Sources:
- trezor-firmware `c33f81554a51` (2026-09-30): `nordic/trezor/trezor-ble/src/ble/{ble_internal.h, service.c, advertising.c, connection.c}`, the THP specification `docs/common/thp/specification.md`, and trezorlib `python/src/trezorlib/transport/ble.py` and `thp/`
- trezor-suite `transport-bluetooth`, `transport-native-bluetooth`
- `trezor-thp` 0.1.1 itself is published from trezor-firmware `7105338e`. Its THP message numbers match `c33f815`'s, except one value since removed.

**GATT.** Service `8c000001-a59b-4d58-a9ad-073df69fa1b1`. Both characteristics require an encrypted link.
- The host writes to `8c000002-…` (write, or write without response).
- The device notifies on `8c000003-…`. A push characteristic `8c000004-…` exists and is unused here.

**Packets.** Exactly one THP packet per write or notification, 244 bytes, so the link's ATT MTU must be at least 247.
- `connect()` refuses a smaller MTU (`LinkUnsuitable`) rather than hang.
- Writes are always **with response**. Upstream btleplug gives CoreBluetooth no flow control for writes without response (Trezor Suite ships a btleplug fork for exactly that), and trezorlib writes with response on Apple platforms too.

**Two pairings.**
- The OS pairs the Bluetooth link: LE Secure Connections, numeric comparison. The same six digits show on the Safe 7 and on the host; confirm both.
- Then THP pairs the app. The Safe 7 asks "Allow mebit on <host> to pair?" and shows a six-digit code, which the user types on the host. That code is not the PIN.
- After that the host holds a **pairing credential**. It is a secret: it contains the host's static key. Keep it in Keychain or Keystore.
- The Safe 7 may say it already knows this host only when this host presented a credential that Safe 7 issued: the handshake shows whether our stored credential matched the device. A peer that claims a pairing without one is refused (THP specification, host state HH3). Otherwise any peripheral could skip pairing, or pass for the paired Safe 7.
- With the credential, a reconnection skips the code. Autoconnect credentials are never requested, so the Safe 7 asks "Allow … to connect with this Trezor?". The exception is when it still holds an open channel from this host's last connection: it then replaces that channel without asking. That is "channel replacement": THP specification, section of that name, and `core/embed/rust/src/thp/mod.rs`, `core/src/apps/thp/pairing.py`. Channels outlive a dropped link, so on 2026-10-01 no reconnection asked, not even one made just after turning the Safe 7 off and on (HW-13).

**Advertising.** The service UUID and Trezor's company id `0x0F29` (followed by flags, color, and a model code: 6 for the Safe 7) sit in the **scan response**, so scanning is unfiltered.
- A bonded Safe 7 advertises with a whitelist: only hosts it is bonded with see it. To pair a new host, choose **Pair new device** on the Safe 7.
- **Asleep, it can't be found.** A Safe 7 that goes to sleep with no host connected turns its Bluetooth radio off (`ble_suspend` in `core/embed/io/ble/stm32/ble.c`). The user must wake it before a scan, so an app should say so. On hardware, both scans made while it slept found nothing.
- The Safe 7 rotates its Bluetooth address (LE privacy), and iOS shows apps only a per-app id. So a device is never identified by its address: identity is the two checks above, and the credential.
- The advertised name isn't stable either. Its suffix changed on 12 of 13 reconnections (`Trezor Safe 7 (0R4)`, `(4B6)`, `(2G8)`…). `ConnectOptions::device` can tell apart the devices of one scan, nothing more.

**Busy.** When the Safe 7 won't take a connection, its BLE chip answers in the old codec v1 with `Failure("Device locked or busy")`. That is reported as `DeviceBusy`.

**PIN and passphrase are entered on the Safe 7 only.** If it is locked, the handshake is refused and retried with unlock, as trezorlib does, and the Safe 7 asks for its PIN. A BIP-39 passphrase, when wanted, is entered on the Safe 7 too (`SessionPassphrase::OnDevice`). No passphrase text crosses this API.

**Waiting on the user.** The Safe 7 acknowledges our ButtonAck *after* showing a prompt. So the last packet doesn't tell whether the user is being waited on: `TrezorSession::awaiting_user()` does, and the driver allows 5 minutes then, 15 seconds otherwise. (On hardware, timing by the last packet gave the user 15 seconds. Since the fix, a signing screen left 30 seconds before confirming signs normally.)

**A host that goes away mid-prompt.** The Safe 7 keeps showing the prompt until the user answers it or the device locks itself. On hardware it locked after about a minute, both when the harness was killed and when the Mac's Bluetooth was switched off. An app that wants the prompt gone must send Cancel (`Canceller`) while still connected.

## Signing

The PSBT must be what mebit builds: a P2WSH `sortedmulti` vault spend in which this Safe 7 holds exactly one key of every input, at a BIP-48 P2WSH vault path on the session's network (`m/48'/coin'/account'/2'/{0,1}/i`, coin 0 on mainnet and 1 elsewhere). Anything else is refused before anything is sent. That includes:
- inputs from two vaults
- one output spent twice
- the Safe 7's key at another path, such as mainnet's coin type in a testnet session
- a missing previous transaction or witness script
- sighashes other than ALL
- OP_RETURN outputs

The client checks the PSBT's shape, not its intent: it takes the PSBT's own global xpubs as "the vault". Before signing, a caller must check the PSBT against the vault the user registered, and run `vault-core`'s `policy`.

The vault's account xpubs must be in the PSBT as global xpubs (BIP-174 `PSBT_GLOBAL_XPUB`): Trezor needs them to rebuild each input's script.

**Change.** Change back to the same vault, at the signer's own vault path (chain 0 or 1), goes to the Safe 7 as change. The Safe 7 re-derives it and doesn't show it. Every other output is shown for the user to confirm.

**What comes back.** It is accepted only as the PSBT that was sent plus valid signatures from the signer's keys, verified against sighashes computed here (`vault-core::hw::psbt_check`, shared with the Jade).

## Hardware checklist

The harness is `examples/trezor-hw-test`, a test tool rather than product code. Run it from `vault-workspace/`:

```
cargo run -q -p trezor-ble --example trezor-hw-test -- scan
cargo run -q -p trezor-ble --example trezor-hw-test -- --credential-file target/safe7.cred info
cargo run -q -p trezor-ble --example trezor-hw-test -- --credential-file target/safe7.cred xpub --verify-against target/phrase.txt
cargo run -q -p trezor-ble --example trezor-hw-test -- --credential-file target/safe7.cred xpub --path "m/84'/1'/0'" --slip132
cargo run -q -p trezor-ble --example trezor-hw-test -- --credential-file target/safe7.cred roundtrip --inputs 2
```

`--code-file target/code.txt` reads the pairing code from a file instead of the terminal: the harness waits for it to appear, then deletes it.

**Before each run:** wake the Safe 7 (asleep, it isn't found), and quit Trezor Suite (one app at a time holds the link). While the Safe 7 shows its pairing code, don't touch it: the screen's only button cancels pairing.

**Ctrl-C** once connected does what an app's Cancel button would: it asks the Safe 7 to drop its prompt. A second Ctrl-C quits. To keep a log, use `2>&1 | tee -i target/hw-N.log`: without `-i`, Ctrl-C stops `tee` too.

**Test wallet.** Use a test wallet only:
1. Restore the public phrase `abandon` ×11 + `about` (no passphrase) on the Safe 7.
2. Put the phrase in a file under `target/` (gitignored) for `--verify-against`.
3. Never fund that phrase on mainnet.

Its expected fingerprint is `73c5da0a`. Its BIP-48 testnet account xpub is the one pinned in `vault-core`'s `hw::jade::device_vectors`, which a Jade Core produced from the same phrase.

**Record each run.** Firmware, host OS, Rust toolchain, crate versions, and which confirmations the Safe 7 asked for.

Runs so far: 2026-10-01, Safe 7 firmware 2.12.5 with the test phrase, macOS 26.6.2, Rust 1.98.1, btleplug 0.13.3, trezor-thp 0.1.1.

**Re-run after the audit fixes of 2026-10-01** (pairing-state check, cancel, signing paths): HW-4 (reconnecting with the existing credential), HW-7 (2 inputs, finalized), and HW-2 (a fresh pairing into a new credential file) all passed again. See `docs/04-open-items.md` item 23.

| # | Case | Expect | Last run |
|---|---|---|---|
| HW-1 | `scan`, then `info` | the Safe 7 found; GATT and MTU ≥ 247; gate 1 passes | ✅ MTU 247; `T3W1` before the handshake |
| HW-2 | first `info` with `--credential-file` | OS numeric comparison, "Allow … to pair?" on the Safe 7, its code typed; credential saved | ✅ on the third run. The first was cancelled by a tap on the code screen. In the second, the Safe 7 sat on its lock screen and never answered channel allocation; allocation is now retried. |
| HW-3 | `info` output | model `Safe 7`, internal model `T3W1`, firmware ≥ 2.9.3 | ✅ `Safe 7`, `T3W1`, 2.12.5 |
| HW-4 | `info` again, with the credential | no code; no prompt either while the Safe 7 holds this host's channel | ✅ no code and no prompt, on every later run |
| HW-5 | `xpub --verify-against …` | fingerprint `73c5da0a`; xpub = vault-core's = the Jade vector | ✅ `73c5da0a`, `tpubDFH9dg…RWZEheQ`: all three equal |
| HW-6 | `xpub --path "m/84'/1'/0'" --slip132 --verify-against …` | the `vpub` Trezor Suite shows for that account; key = vault-core's | ✅ key = vault-core's; `vpub5Y6cjg…qvZVnsc` decodes to the same key and chain code. Not compared with Suite's screen. |
| HW-7 | `roundtrip --inputs 2`, then `16` | the screen matches the printout; `signatures verified`; `Finalized 2-of-3` | ✅ 2 inputs: 18.0 s including confirming, finalized (306 vB). 16 inputs: 50.4 s including confirming, finalized (1763 vB). |
| HW-8 | `roundtrip`, decline on the Safe 7 | `cancelled on the Trezor`; the connection stays usable | ✅ |
| HW-9 | `roundtrip`, Ctrl-C once while the Safe 7 waits | the Safe 7 drops the prompt; `cancelled on the Trezor` | ✅ Ctrl-C sent Cancel and the Safe 7 answered `ActionCancelled`. With the harness killed instead, the Safe 7 kept the prompt about a minute, until it locked. |
| HW-10 | drop the link while the Safe 7 waits (the host's Bluetooth off, or the Safe 7 off); then re-run | `the Trezor disconnected` at once; the re-run connects | ✅ with the Mac's Bluetooth off: reported within seconds; the Safe 7 kept the prompt until it locked. The re-run connected, then hit the 15-second wait bug (fixed since). |
| HW-11 | lock the Safe 7, wake it to its lock screen, then `info` | PIN asked on the Safe 7, then connected | ✅ the Safe 7 asked for its PIN, then connected (24.7 s including the 10 s scan). A first try while it slept found nothing. |
| HW-12 | forget the Safe 7 in the host's Bluetooth settings (or vice versa), `info` | the `BondRemoved` guidance, or OS pairing again | ✅ forgotten on the Mac: OS numeric comparison again, and the THP credential still accepted (no code). That run's signing ended `cancelled on the Trezor`, cause unknown; a repeat signed and finalized. |
| HW-13 | restart the Safe 7, unlock it, then `info` | "Allow … to connect with this Trezor?" if the restart emptied its channel cache | ❔ connected with no prompt. Inconclusive: whether the restart was a full power-off isn't confirmed, and the spec doesn't say whether the cache survives one. |

**Not verifiable here:**
- iOS and Android: they need the Phase 1 UniFFI wrapper and an app.
- A second BLE-capable Trezor model (refusal is covered by unit tests).
- Several Safe 7s in range (covered by unit tests).

## Troubleshooting

- **`no Bluetooth devices seen at all`.** The terminal has no Bluetooth permission. On macOS: System Settings → Privacy & Security → Bluetooth.
- **`no Trezor Safe 7 among the N devices seen`.** Wake the Safe 7: asleep, it turns Bluetooth off. For a host it isn't bonded with, open Bluetooth on the Safe 7 and choose **Pair new device**: a bonded Safe 7 is visible only to hosts it is bonded with.
- **`cancelled on the Trezor` during pairing, without declining.** The code screen was touched: its only button cancels pairing.
- **`BondRemoved`, or pairing that keeps failing after a wipe.**
  1. Forget the Safe 7 in the host's Bluetooth settings.
  2. Choose Pair new device on the Safe 7.
  3. Retry.
  4. Delete a stale `--credential-file`; it is only worth keeping while the Safe 7 keeps its pairings.

## Mobile notes (Phase 1)

- **iOS.** Add `NSBluetoothAlwaysUsageDescription` to `Info.plist`. CoreBluetooth negotiates the MTU itself. `connect()` checks it is at least 247. Untested on a phone.
- **Android.**
  - Permissions: `BLUETOOTH_SCAN` (`neverForLocation`) and `BLUETOOTH_CONNECT` on API 31+; location on API ≤ 30.
  - btleplug's droidplug needs its Java side bundled, `btleplug::platform::init(env)` called through JNI, and ProGuard rules (btleplug's README).
  - btleplug 0.13 requests MTU 517 when connecting. **0.11 doesn't**, which is why `jade-ble` moved to 0.13 as well: one Android build can't carry two btleplug versions (`docs/04-open-items.md` item 18).
- **Lifecycle.** A link may drop when the app goes to the background. Every call reports `Disconnected` or `Timeout`. Reconnect with the stored credential; the user then confirms on the Safe 7.

## Known issues

- **btleplug 0.13.3** is pinned for its CoreBluetooth fixes: `discover_services()` hangs, event-thread panics, operations that never resolve (#486–#489), and Android JNI crashes. It also requests MTU 517 on Android.
- **Device authenticity is not checked.** A fake peripheral claiming to be a `T3W1` passes both model checks, and could hand over its own xpub when a vault is set up. Only Trezor's `AuthenticateDevice` (certificate chains from the device's secure elements) could tell. It is required before real funds, so use the Safe 7 on test networks with the test phrase until then: `docs/04-open-items.md` item 19.
- **Dropping a call's future midway** leaves the device out of step. The next call says so (`Abandoned`): reconnect.
