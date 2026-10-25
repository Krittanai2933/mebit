# jade-ble

**Owner**: @phoovich. This is wallet-first pivot work (Task No.4), outside the original 4-way split. See `docs/05-progress-and-next-steps.md`.

**Depends on**: `vault-core` (`hw::jade::JadeSession` does all the protocol work), plus `btleplug`, `tokio` and `ureq`.

**Not in the workspace's `default-members`.** btleplug needs `libdbus-1-dev` + `pkg-config` on Linux and pulls in WinRT on Windows, and nothing depends on this crate yet. Build and test it explicitly with `-p jade-ble`.

## Responsibilities

Talks to a Blockstream Jade over Bluetooth LE: connect, unlock, read xpubs, and sign PSBTs. It is the production driver: mobile-signer-ffi wraps it with UniFFI in Phase 1, because Rust owns BLE on mobile too (decided 2026-09-30, `docs/08-multisig-wallet-spec.md` §5.1).

It only moves bytes. Every protocol decision lives in `vault-core::hw::jade`:
- request ids
- reassembling replies
- fetching a signed PSBT piece by piece
- the pinserver hand-off
- checking that the returned PSBT differs from the request only by valid signatures from the expected key

What this crate adds is what needs a radio and a clock:
- scanning
- GATT
- timeouts
- noticing a dropped link
- relaying the pinserver request over HTTPS

```rust
let mut jade = JadeBle::connect(Network::Testnet, None, Duration::from_secs(5)).await?;
jade.unlock().await?;                                     // PIN on the device
let master = jade.xpub(&DerivationPath::master()).await?; // when adding the key: record master.fingerprint()
let signed = jade.sign_psbt(&psbt, recorded_fingerprint).await?; // user confirms on the device
jade.disconnect().await?;
```

A device error (declined, wrong PIN, locked) leaves the connection usable. After a timeout or a dropped link, reconnect before the next call.

`JadeBle::scan(Duration)` lists the Jades in range without connecting (`FoundJade { name, id }`), and `connect` takes the `name` of one. A `scan` or `connect` whose future is dropped, or that fails after the link is up, stops its scan and disconnects the link it had started. btleplug does neither on its own, and CoreBluetooth keeps a dropped connect queued with no timeout. A finished `connect` is the caller's to disconnect.

## How the Jade behaves over BLE

Checked against firmware 1.0.41 source, and on a Jade Core with that firmware.

- **Protocol.** It's CBOR-RPC, the same as over USB; there is no QR and no JSON.
  - Service `6e400001-…`.
  - The host writes to `6e400002-…` with response, in 509-byte chunks sent back to back. The Jade drops a partial request if the next chunk is more than 2 s late.
  - Replies arrive as indications on `6e400003-…`.
- **Pairing.** The first connection pairs by numeric comparison. Confirm on the Jade and on the host, but only if the 6 digits match.
- **Unlock is per connection.** A PIN wallet locks again when BLE drops, though not always at once (`docs/04-open-items.md` item 25), so treat every new connection as needing the PIN. Unlocking a PIN wallet relays an encrypted request to Blockstream's pinserver, so the host must be online.
  - If the pinserver can't be reached, or answers with something that isn't JSON, `unlock()` fails with the relay error and sends the Jade `cancel`, which returns it to its home screen. Call `unlock()` again once online.
  - After the pinserver answers, the Jade may still ask for a BIP39 passphrase, or run a PIN change requested on the device. `unlock()` waits for the user through both.
- **Replies outlive the link.** The Jade sends replies per transport, not per connection: a reply it finishes while a later connection is up goes out on that one (seen 2026-10-01: an unlock left at the passphrase screen, then confirmed during the next unlock). Request ids start at random per connection, so such a reply is refused rather than taken for an answer. The call it lands on fails; reconnect. A reply finished while no host is connected, say a signing confirmed after the host timed out, did not show up on the next connection.
- **Network pin.** The first unlock pins the wallet to mainnet or to the test networks. A Jade Core cannot switch back: that setting only exists in QR mode, which needs a camera. To change it, factory-reset and restore. `unlock()` checks the pin before anyone is asked for a PIN.
- **Jade Core** (board `JADE_V2C`) has no camera and **no battery**: keep it on USB power while using BLE.

## Hardware checklist

The harness is `examples/jade-hw-test`, a test tool rather than product code. Run it from `vault-workspace/`:

```
cargo run -q -p jade-ble --example jade-hw-test -- info
cargo run -q -p jade-ble --example jade-hw-test -- xpub --verify-against <phrase-file>
cargo run -q -p jade-ble --example jade-hw-test -- roundtrip --inputs 16 --out-dir target
cargo run -q -p jade-ble --example jade-hw-test -- sign --psbt <file> [--out <file>]
cargo run -q -p jade-ble --example jade-hw-test -- --network bitcoin xpub   # global flags go before the command
```

**Test wallet.** Use a test wallet only:
1. Restore the public phrase `abandon` ×11 + `about` (no passphrase) on the Jade: Begin Setup → Restore Wallet → 12 Words.
2. Choose Bluetooth when asked.
3. Let the first `xpub --network testnet` unlock set the PIN. That pins the wallet to testnet.
4. For `--verify-against`, put the phrase in a file under `target/` (which is gitignored).
5. Never fund that phrase on mainnet.

The expected fingerprint is `73c5da0a`, and the expected xpub is pinned in `vault-core`'s `hw::jade::device_vectors` test.

**Record each run**: firmware, host OS, Rust toolchain and btleplug version.

Runs so far, all with firmware 1.0.41 on macOS: 2026-09-30 and 2026-10-01 with btleplug 0.11.8, then 2026-10-01 again with btleplug 0.13.3 (macOS 26.6.2, Rust 1.98.1), which this crate uses now. "Last run" names the btleplug version: a run on 0.11.8 does not vouch for 0.13.3.

| Case | Do | Expect | Last run |
|---|---|---|---|
| Connect + protocol | `info` | board `JADE_V2C`, config `BLE`, the firmware version | ✅ 2026-10-01, btleplug 0.13.3 |
| xpub | `xpub --verify-against …` | fingerprint `73c5da0a`, two ✓ lines | ✅ 2026-10-01, btleplug 0.13.3 |
| Sign | `roundtrip --inputs 2`, `16`, `100`; compare the Jade's screen with the printout | `signed N/N … verified`, `Finalized 2-of-3` | ✅ 2026-10-01, btleplug 0.13.3: 100 inputs in 28.6 s including confirming (28.8 s on 0.11.8) |
| Decline | `roundtrip`, reject on the Jade | `Jade: declined on the device` | ✅ 2026-10-01, btleplug 0.13.3 |
| Passphrase | set the Jade to ask for a BIP39 passphrase; `xpub --verify-against …`, wait 30 s before confirming an empty one | unlocks; two ✓ lines | ✅ 2026-10-01, btleplug 0.13.3 |
| Wrong PIN, retry at once | `xpub` with a wrong PIN, then `xpub` again before pressing Continue on "Incorrect PIN!" | the second run waits for Continue, then asks for the PIN and unlocks | ✅ 2026-10-01, btleplug 0.13.3: the second run started 13 s after the wrong PIN, waited through Continue and the PIN, and unlocked (38 s, against 15–17 s for a plain unlock) |
| Pinserver unreachable | `ALL_PROXY=http://127.0.0.1:9` before the command, then `xpub` | `pinserver relay failed…`; the Jade drops the unlock at once | ✅ 2026-10-01, harness form, btleplug 0.13.3 and 0.11.8 side by side: the relay error, and the Jade back on its home screen at once with both |
| Pinserver answers with something that isn't JSON | Use the opt-in debug-only injection command below; it simulates the response at the relay boundary and bypasses HTTPS | `pinserver relay failed: …`; the Jade back on its home screen at once, like the unreachable case | ✅ 2026-10-11, Jade Core 1.0.41: injected `not-json`; expected relay error and home screen confirmed; HTTPS bypassed |
| Drop | pull the Jade's USB while it waits for you | `the Jade disconnected` at once | ✅ 2026-10-01, btleplug 0.13.3: the error 4 s after the unlock began |
| Reconnect | re-run after a drop | connects; asks for the PIN again | ✅ 2026-10-01, btleplug 0.13.3 |
| Network pin | `--network bitcoin xpub` on the test wallet | network mismatch, before any PIN prompt | ✅ 2026-10-01, btleplug 0.13.3 |
| Not found | turn the Jade's Bluetooth off, then `info` | `no Jade among the N devices seen…` | ✅ 2026-10-01, btleplug 0.13.3, with the Jade's Bluetooth off |
| Nothing to sign | Temporary Signer with another phrase (e.g. `zoo` ×11 + `wrong`), then `sign --psbt target/unsigned.psbt` | `the PSBT came back without any new signature` | not run yet |
| Fresh pairing | forget the Jade in the host's Bluetooth settings ("Reset Pairings" on the Jade if needed), `info`, wait 20 s before confirming the 6 digits on both | pairs; `info` prints | ✅ 2026-10-01, btleplug 0.13.3: connecting took 21 s, the wait included |
| Pairing rejected | forget the Jade in the host's Bluetooth settings, `info`, reject on the Jade | an error within 60 s, no hang | ✅ 2026-10-01, btleplug 0.13.3: `Bluetooth: Runtime Error: Device disconnected` 3 s after the scan |
| Pairing declined on iOS | forget the Jade on the iPhone, run `jade` through `mobile-signer-ffi`. (a) Cancel on the iPhone's Pairing Request, (b) reject on the Jade, (c) touch nothing | an error within 60 s, no hang, with a pairing hint | ✅ 2026-10-24, iPhone 15, after `docs/04-open-items.md` item 39. (a) `Timeout: timed out discovering services. If a pairing request was waiting…` after 19.5 s, (b) `Disconnected: the Jade dropped the link while connecting. If a pairing request was waiting…` after 9.4 s, (c) the first text again after 20.0 s. On 2026-10-23, before item 39, the same runs gave the bare `Timeout: timed out discovering services` (19.5 s), `Link: Runtime Error: Device disconnected` (18.5 s) and the same raw text (35.2 s) |
| Pairing rejected on macOS, after item 39 | forget the Jade in macOS Bluetooth, `info`, reject on the Jade | the same hint | ✅ 2026-10-24: `error: the Jade dropped the link while connecting. If a pairing request was waiting…` 11 s after the start (2026-10-01: raw `Bluetooth: Runtime Error: Device disconnected` 3 s after the scan) |
| Pairing rejected on Android, in the first request | forget the Jade on the phone, run `jade` through `mobile-signer-ffi`, reject on the Jade | the same hint, from the first request | ✅ 2026-10-25, POCO X3 NFC (Android 11): `Disconnected: the Jade dropped the link while connecting…` 15.3 s after `connected` (2026-10-24: the plain `the Jade disconnected` in two runs) |
| Pairing left unanswered on Android | the same, touch nothing | the same hint | ✅ 2026-10-25: the hint 31.2 s after `connected`, when the bond went from BONDING to NONE. The 60 s first-write timeout was not reached |
| Jade switched off on Android | unplug the Jade (a) while the pairing is pending, (b) bonded, between two requests, (c) bonded, after `connect` and before the first request, (d) before the run | (a) the hint, (b) the plain `Disconnected`, (c) the hint, (d) `NotFound` | ✅ 2026-10-25, one run each, all as expected. (a) took 16.7 s. (c) is a false positive: a bonded Jade has no pairing to wait for, and the link cannot say so |
| Repeats of the Android and iOS decline runs | POCO: reject on the Jade three times (5.2, 6.3, 6.1 s after `connected`) and hands off three times (31.4, 31.2, 30.9 s). iPhone: hands off three times (19.5 s each, the discovery-timeout hint) | the hint every time | ✅ 2026-10-25, 12 runs with the hint, 4 launches that were not results (see `docs/04` item 39). The hands-off path on iOS varies between rounds, and every path carries the hint |
| Several Jades | two Jades in range, `info` | `several Jades in range (…)`; `--name` picks one | unit-tested only (one device) |

To exercise the non-JSON cancellation path on a real Jade without changing
production TLS behavior, opt into the debug-only test feature and provide an
invalid response body. This injects the body after the Jade requests pinserver
relay; it does not test HTTPS or a real pinserver response:

```sh
JADE_TEST_PINSERVER_RESPONSE='not-json' cargo run --locked -p jade-ble --features test-pinserver-response --example jade-hw-test -- --network testnet xpub
```

The command should fail with a pinserver relay error after sending `cancel` to
the Jade. Check that the device returns to its home screen. Builds without the
feature, and all release builds, always use the normal HTTPS relay.

## Troubleshooting

- **`no Bluetooth devices seen at all`**: the terminal has no Bluetooth permission. Grant it in System Settings → Privacy & Security → Bluetooth.
- **`the Jade no longer knows this host`**: the Jade evicted this host's bond (it keeps few). Forget the Jade in the host's Bluetooth settings, then connect again and confirm the pairing on the Jade. Measured on a Mac after a POCO paired.
- **`the Bluetooth pairing was not completed`**: CoreBluetooth reported `Encryption is insufficient`. It did so for a Safe 7 whose pairing request was not confirmed in about 30 seconds on an iPhone. With a Jade on an iPhone it did not appear in three runs (Cancel, reject on the Jade, hands off). They ended as `Timeout: timed out discovering services` or `Link: Runtime Error: Device disconnected` (`docs/04-open-items.md` item 39).
- **`the Jade dropped the link while connecting`**: the link dropped, or iOS reported `Device disconnected`, before the connect steps finished (connecting, discovering services, subscribing). Measured on 2026-10-24 with a Jade rejected on the Jade, on an iPhone and on a Mac. It is a hint, not a diagnosis, because a Jade that is switched off at that moment gives it too. On Android it comes from the first write instead (next bullet), because the Jade holds the pairing request there. Confirm the pairing on this device and on the Jade, then connect again.
- **`timed out writing to the Jade. If a pairing request was waiting…`**: on Android the first write on a new link did not finish within 60 s (`FirstWriteTimedOut`). It is a hint, like the others. The Jade ended the link at about 30 s in every Android run of 2026-10-25, so this text never appeared on hardware, and only a unit test makes it. Later writes keep the plain `timed out writing to the Jade`.
- **`timed out discovering services. If a pairing request was waiting…`**: service discovery did not finish in 15 s. iOS holds discovery behind its pairing request, so this is what a request cancelled on the iPhone, or left unanswered, looks like. Measured on an iPhone twice (Cancel, hands off). A timeout in any other step (`connecting`, `subscribing`, a reply) keeps its plain text. The remedy is the same.
- **`no Jade among the N devices seen`**: the Jade is off (no battery on the Core), or its Bluetooth is off (Options → Device → Settings → Bluetooth), or it is connected to another host.
- **Pairing fails after a reset.** A factory reset also erases the Jade's pairings and turns Bluetooth off. To pair fresh:
  1. Forget the Jade on the host.
  2. On the Jade, use "Reset Pairings" if needed.
- **Network mismatch.** See "Network pin" above.

## Known issues

- **Firmware edge case, found by reading the source; not observed.** If a signed PSBT is exactly a multiple of 3008 bytes long, firmware 1.0.41 asks for one piece too many and asserts. The Jade reboots, and the host reports `the Jade disconnected`. It hits about 1 in 3008 signings of PSBTs over 3 KB. Retrying the same PSBT hits it again, so change its length (e.g. add a proprietary field). Tracked as `docs/04-open-items.md` item 15, to be reported upstream.
- **btleplug 0.13.3, CoreBluetooth backend**, checked against its source when this crate moved from 0.11.8:
  - The notification stream still never ends on disconnect, so this crate watches the adapter's `DeviceDisconnected` instead.
  - A lagging notification consumer still drops data silently; `JadeSession`'s checks turn that into an error, never into a wrong result.
  - Unlike 0.11.8, `connect()` no longer discovers services itself: `discover_services()` does, under its own timeout. A failed write, a refused subscription and a call on a vanished peripheral now resolve at once where 0.11.8 left them waiting for this crate's timeouts. Every operation still has one.
- **Android ran on two phones.** A Galaxy S25 FE (Android 16, 2026-10-21) and a POCO X3 NFC (Android 11, MIUI 12.5, 2026-10-23) scanned, paired, connected, read `version_info`, and ran a cancelled scan and a cancelled connect against a Jade Core through `mobile-signer-ffi`. On the POCO a missed pairing notification ended as `Disconnected: Not connected` after about 30 s, and the Jade was seen again 12 s after a cancelled connect, where the Galaxy needed 4 s. No other Android phone or version ran. See `docs/04-open-items.md` items 14 and 30.
- **On Android the Jade's pairing wait sits in the first request, not in `connect`.** On the POCO X3 NFC (2026-10-24) `connect` finished 6 s after the scan while the OS pairing was still open, and then `version_info` waited. When the pairing failed, the Jade ended the link after about 35 s and the error was the plain `Disconnected: the Jade disconnected`, with no pairing hint (two runs). A pairing that was confirmed in time passed (`info` returned after 17 s). The earlier README text called the connect-phase hint "measured" on Android. It never was: the only Android text measured before was the bare `Disconnected: Not connected`. Since 2026-10-25 the first write on a new Android link gets the hint (`PairingWait` in `src/lib.rs`): the write's `Disconnected` becomes `DisconnectedWhileConnecting` and its `Timeout` becomes `FirstWriteTimedOut`, until the first chunk is acknowledged. iOS and macOS never start in that state. Measured on the POCO: a reject, a hands-off run and a Jade unplugged during the pairing gave the hint, and a second request on the same link gave the plain error. A write that fails with a raw Android status (`Unable to write characteristic, status: N`, a `Ble` error) is not hinted, and no other Android version was measured.
- **A Jade keeps few bonds.** On 2026-10-23, after the POCO paired, the Mac's `Jade info` failed with the raw `Bluetooth: Runtime Error: Peer removed pairing information` while the iPhone still connected. Since round four it is a typed `JadeBleError::BondRemoved` with advice (measured on the Mac, `docs/04-open-items.md` item 37). Forget the Jade in macOS Bluetooth, then connect again.
- **iOS ran on an iPhone 15 (2026-10-21)** through `mobile-signer-ffi`: scan, connect, `version_info`, disconnect, a cancelled scan and a cancelled connect, on a Jade Core with firmware 1.0.41 (`docs/05-progress-and-next-steps.md`).
- **A declined pairing on iOS** (iPhone 15) ended on 2026-10-23 as the bare `Timeout: timed out discovering services` after Cancel on the iPhone, and as `Link: Runtime Error: Device disconnected` after a reject on the Jade or with nothing touched. On 2026-10-24 the same three runs gave the pairing hints (`docs/04-open-items.md` item 39). One run each. The Jade held at least two bonds at once (the iPhone's survived the POCO's pairing).
- **`mod cleanup` has a twin in `trezor-ble`.** Keep the two in step. They are separate on purpose (`docs/04-open-items.md` item 32).

## Real testnet4 wallet verification

The example also has an `e2e` command group. Offline commands return before opening BLE. Live commands forward to the serial coordinator in `trezor-hw-test`.

```sh
cargo run --locked -p jade-ble --example jade-hw-test -- --network testnet4 e2e matrix
cargo run --locked -p jade-ble --example jade-hw-test -- --network testnet4 e2e offline
cargo run --locked -p jade-ble --example jade-hw-test -- --network testnet4 --name "Jade 123456" e2e enroll --slot j1 --jade-slot-empty
```

Manually verify that the `mebit_e2e` test slot is empty before declaring `--jade-slot-empty`. Jade RPC can hide registrations belonging to another seed. Read the [testnet4 instructions](../wallet-e2e/README.md) for safe registration reuse, complete coverage, and checkpointed spending. Actual firmware and BLE acceptance remain unverified until you run those cases on your devices.
