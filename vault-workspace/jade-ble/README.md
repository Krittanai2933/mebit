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

## How the Jade behaves over BLE

Checked against firmware 1.0.41 source, and on a Jade Core with that firmware.

- **Protocol.** It's CBOR-RPC, the same as over USB; there is no QR and no JSON.
  - Service `6e400001-…`.
  - The host writes to `6e400002-…` with response, in 509-byte chunks sent back to back. The Jade drops a partial request if the next chunk is more than 2 s late.
  - Replies arrive as indications on `6e400003-…`.
- **Pairing.** The first connection pairs by numeric comparison. Confirm on the Jade and on the host, but only if the 6 digits match.
- **Unlock is per connection.** A PIN wallet locks again whenever BLE drops, so every new connection needs the PIN. Unlocking a PIN wallet relays an encrypted request to Blockstream's pinserver, so the host must be online.
  - If the pinserver can't be reached, `unlock()` fails with the relay error and sends the Jade `cancel`, which returns it to its home screen. Call `unlock()` again once online.
  - After the pinserver answers, the Jade may still ask for a BIP39 passphrase, or run a PIN change requested on the device. `unlock()` waits for the user through both.
- **Replies outlive the link.** The Jade queues replies per transport, not per connection. A reply it finishes after the link drops (say, the user confirms after the host timed out) goes out on the next connection. Request ids start at random per connection, so such a reply is refused rather than taken for an answer. The call it lands on fails; reconnect.
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

| Case | Do | Expect | Last run |
|---|---|---|---|
| Connect + protocol | `info` | board `JADE_V2C`, config `BLE`, the firmware version | ✅ 2026-10-01 |
| xpub | `xpub --verify-against …` | fingerprint `73c5da0a`, two ✓ lines | ✅ 2026-10-01 |
| Sign | `roundtrip --inputs 2`, `16`, `100`; compare the Jade's screen with the printout | `signed N/N … verified`, `Finalized 2-of-3` | ✅ 2026-10-01 |
| Decline | `roundtrip`, reject on the Jade | `Jade: declined on the device` | ✅ 2026-10-01 |
| Passphrase | set the Jade to ask for a BIP39 passphrase; `xpub --verify-against …`, wait 30 s before confirming an empty one | unlocks; two ✓ lines | ✅ 2026-10-01 |
| Wrong PIN, retry at once | `xpub` with a wrong PIN, then `xpub` again before pressing Continue on "Incorrect PIN!" | the second run waits for Continue, then asks for the PIN and unlocks | ✅ 2026-10-01 |
| Pinserver unreachable | `ALL_PROXY=http://127.0.0.1:9` before the command, then `xpub` | `pinserver relay failed…`; the Jade drops the unlock at once | ✅ 2026-10-01 via a throwaway tool that then unlocked again over the same connection; the harness form not run |
| Drop | pull the Jade's USB while it waits for you | `the Jade disconnected` at once | ✅ 2026-09-30 |
| Reconnect | re-run after a drop | connects; asks for the PIN again | ✅ 2026-09-30 |
| Network pin | `--network bitcoin xpub` on the test wallet | network mismatch, before any PIN prompt | ✅ 2026-09-30 |
| Not found | turn the Jade's Bluetooth off, then `info` | `no Jade among the N devices seen…` | ✅ 2026-09-30 with the Jade powered off, under the error's earlier wording; not re-run since |
| Nothing to sign | Temporary Signer with another phrase (e.g. `zoo` ×11 + `wrong`), then `sign --psbt target/unsigned.psbt` | `the PSBT came back without any new signature` | not run yet |
| Pairing rejected | forget the Jade in the host's Bluetooth settings, `info`, reject on the Jade | an error within 60 s, no hang | not run yet |
| Several Jades | two Jades in range, `info` | `several Jades in range (…)`; `--name` picks one | unit-tested only (one device) |

## Troubleshooting

- **`no Bluetooth devices seen at all`**: the terminal has no Bluetooth permission. Grant it in System Settings → Privacy & Security → Bluetooth.
- **`no Jade among the N devices seen`**: the Jade is off (no battery on the Core), or its Bluetooth is off (Options → Device → Settings → Bluetooth), or it is connected to another host.
- **Pairing fails after a reset.** A factory reset also erases the Jade's pairings and turns Bluetooth off. To pair fresh:
  1. Forget the Jade on the host.
  2. On the Jade, use "Reset Pairings" if needed.
- **Network mismatch.** See "Network pin" above.

## Known issues

- **Firmware edge case, found by reading the source; not observed.** If a signed PSBT is exactly a multiple of 3008 bytes long, firmware 1.0.41 asks for one piece too many and asserts. The Jade reboots, and the host reports `the Jade disconnected`. It hits about 1 in 3008 signings of PSBTs over 3 KB. Retrying the same PSBT hits it again, so change its length (e.g. add a proprietary field). Tracked as `docs/04-open-items.md` item 15, to be reported upstream.
- **btleplug 0.11.8, CoreBluetooth backend:**
  - The notification stream never ends on disconnect, so this crate watches the adapter's `DeviceDisconnected` instead.
  - A lagging notification consumer drops data silently; `JadeSession`'s checks turn that into an error, never into a wrong result.
  - 0.13.x fixes some CoreBluetooth hangs, but 0.11.8 is the version observed working with our Jade. Every operation here has a timeout anyway.
- **Android is untested.** btleplug needs a Rust+Java build there. See `docs/04-open-items.md` item 14.
