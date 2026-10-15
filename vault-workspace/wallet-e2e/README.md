# Verify wallets on testnet4

For the wallet test inventory, code-path audit, and latest results, see the [wallet verification report](../wallet-e2e-audit/verification.md).

Run commands from `vault-workspace`. Build both existing examples before hardware work.

```sh
cargo build --locked -p trezor-ble -p jade-ble --examples
cargo run --locked -p trezor-ble --example trezor-hw-test -- --network testnet4 e2e matrix
cargo run --locked -p trezor-ble --example trezor-hw-test -- --network testnet4 e2e offline
cargo run --locked -p trezor-ble --example trezor-hw-test -- --network testnet4 e2e report
```

`matrix` writes `target/wallet-e2e/manifest.json`. It contains 12 single-sig cases and 7,303 P2WSH cases, including every threshold and exact signing subset through seven holders. Each case has fixed receive and change indices. Source permutations do not create additional funded rows.

`offline` derives receive and change scripts independently at indices 0, 1, and 7. It signs and finalizes all 7,315 designated subsets with software fixtures at index 7. Fixtures representing hardware sources exercise transaction behavior. Hardware behavior and real-network acceptance remain unverified. Run `bash wallet-e2e/verify.sh` to check both executables, independent matrix expectations, and determinism across separate processes.

Enroll each independent test holder once. Canonical slots are `h1` through `h7`, `j1` through `j7`, and `t1` through `t7`. Hardware enrollment reads all five account xpubs through BLE. Device identity, fingerprint, origins, and xpubs bind every subsequent request. Duplicate physical identifiers, fingerprints, and account keys are refused.

Safe 7 enrollment uses the stable device ID from `GetFeatures`. Its Bluetooth address and advertised name can change between connections. Use scan identifiers only to select a device. A device wipe changes the stable ID and requires a new enrollment.

```sh
cargo run --locked -p trezor-ble --example trezor-hw-test -- --network testnet4 e2e enroll --slot h1
cargo run --locked -p jade-ble --example jade-hw-test -- --network testnet4 --name "Jade 123456" e2e enroll --slot j1 --jade-slot-empty
cargo run --locked -p trezor-ble --example trezor-hw-test -- --network testnet4 --device "Safe 7 selector from scan" e2e enroll --slot t1
```

For first Jade multisig use, manually verify that the `mebit_e2e` test registration slot is empty before passing `--jade-slot-empty`. Jade RPC hides records belonging to other seeds, so an absent response cannot prove the slot is empty. The flag records your declaration. The runner reads back every registration and refuses unrelated visible overwrites. It replaces a previous test registration only when readback matches the saved ownership descriptor. Before signing a resumed case, the coordinator restores that case's registration and verifies its address again. Firmware support still requires verification on your actual device. See the [Jade 1.0.41 RPC documentation](https://github.com/Blockstream/Jade/blob/1.0.41/docs/index.rst).

Choose a case ID from the manifest. Enroll every holder that row requires, including holders outside its signing subset. Complete coverage needs up to seven distinct Jades and seven distinct Safe 7 devices. `prepare` checks the API genesis against rust-bitcoin's testnet4 genesis, compares independent host derivations, and requests address display on each hardware holder for both receive and change. The expected genesis is `00000000da84f2bafbbc53dee25a72ae507ff4914b867c565be350b0da8bf043`.

Jade BLE addresses can rotate between sessions. The harness identifies a Jade
using its advertised name together with its fingerprint and all five
origin-bound account xpubs; it does not treat the current BLE address as a
stable device identity.

```sh
cargo run --locked -p trezor-ble --example trezor-hw-test -- --network testnet4 e2e prepare --case <case-id>
cargo run --locked -p trezor-ble --example trezor-hw-test -- --network testnet4 e2e run --case <case-id> --funding-outpoint <txid:vout> --broadcast
cargo run --locked -p trezor-ble --example trezor-hw-test -- --network testnet4 e2e resume --case <case-id>
cargo run --locked -p trezor-ble --example trezor-hw-test -- --network testnet4 e2e diagnose-sign --case <case-id>
```

Use the Trezor-only `diagnose-sign` command after a case stops at `SIGNING`. It retries the next Trezor signature without changing the checkpoint. The command verifies and discards a successful PSBT. It never finalizes or broadcasts. On ECDSA rejection, the worker writes a JSON diagnostic with the raw signature, expected keys, and hashes to `target/wallet-e2e/private/workers/`. Keep that file private.

To check an existing ECDSA rejection without reconnecting the device, run:

```sh
cargo run --locked -p trezor-ble --example trezor-signature-check -- \
  target/wallet-e2e/private/cases/<case-id>.json \
  target/wallet-e2e/private/workers/<response>.signature-rejection.json
```

The command authenticates the saved P2WSH prevout and checks the signature against both the required P2WSH digest and an alternate P2WPKH digest. A `false` P2WSH result and `true` P2WPKH result identify the wrong script code observed on Safe 7 firmware 2.12.5. The command prints no signature bytes, performs no device or network operations, and writes no files. See [Trezor firmware issue 8057](https://github.com/trezor/trezor-firmware/issues/8057).

To check the actual signing request, reconnect and wake the Safe 7, then capture one diagnostic attempt:

```sh
cargo run --locked -p trezor-ble --example trezor-hw-test -- --network testnet4 e2e diagnose-sign --case <case-id> --trace-signing
```

Approve the saved transaction on the device. The worker saves a private `signing-trace.json` sidecar beside its response, including on signature rejection. It records outbound `SignTx` and `TxAck` payloads accepted by THP and decrypted inbound `TxRequest` payloads. It excludes pairing credentials and PIN or passphrase prompts. THP acceptance alone does not prove Bluetooth delivery. Keep the trace private because signing messages contain wallet public keys, paths, and transaction details. The diagnostic command does not change the checkpoint or broadcast.

Verify the trace without reconnecting the device:

```sh
python3 wallet-e2e/verify_signing_trace.py --checkpoint target/wallet-e2e/private/cases/<case-id>.json --trace target/wallet-e2e/private/workers/<response>.signing-trace.json
```

The independent checker uses literal protobuf fields and a separate Bitcoin parser. Its initial scope is the saved single-input, native 1-of-1 P2WSH index-12 case. It checks request fields, repeated input rounds, previous transactions, and payment and change scripts against the checkpoint. It separately verifies the returned signature. Incomplete traces and mismatched requests fail. A valid request with an invalid signature remains a signing failure. The checker makes no device or network requests and prints no private values.

Fund exactly 50,000 sats to the prepared receiving address. For case `multisig-p2wsh-1of1-a0-h0j0t1-s01-r0-12-c1-12`, fund exactly 221,956 sats instead. The runner requires one canonical funding confirmation and an unspent outpoint. It fetches the full previous transaction, authenticates its txid and output, and builds a real PSBT with all account origins. The payment is exactly 5,000 sats to a separately generated test-owned BIP84 destination. Change goes to the recorded change address. The selected fee is 2 sat/vB using a satisfaction-weight estimate. The runner refuses fees above 5,000 sats or an effective rate above 10 sat/vB.

Signing uses only the designated subset. ECDSA inputs use ALL. BIP86 key-path inputs use DEFAULT, the no-tree tweak, and authenticated previous transactions. Taproot script paths are excluded. Every returned signature must verify against the original PSBT, and every other field and earlier signature must stay identical. Miniscript verifies the complete spend during finalization. The [BIP86 specification](https://github.com/bitcoin/bips/blob/master/bip-0086.mediawiki) describes the internal-key tweak.

Without `--broadcast`, `run` stops after saving finalized bytes. To authorize those saved bytes later, use `e2e resume --case <case-id> --broadcast`. Existing authorization persists across interruptions. Before submission, the runner atomically saves the finalized transaction, txid, and authorization. Resume reconciles the expected transaction and input outspends. An ambiguous submission can retry only the identical transaction bytes. The runner checks the returned txid, fetched contents, spending input index, and canonical block hash. See the [Esplora API](https://github.com/Blockstream/esplora/blob/master/API.md).

Funding and spend confirmation waits poll every 15 seconds and stop after 60 minutes per checkpoint. An existing unconfirmed spend is `PENDING_CONFIRMATION`. Resume repeats genesis verification and refreshes chain evidence. A recorded `PASS` requires one canonical spend confirmation. Reporting rechecks every recorded PASS against its private checkpoint, testnet4 genesis, full funding and spend contents, input spending state, and current canonical confirmations. It rebuilds PASS evidence from that validated checkpoint. Unavailable or changed chain evidence keeps the aggregate BLOCKED. Reporting uses no devices, submits no transactions, and leaves checkpoints untouched. If no PASS has been recorded, reporting requires no HTTP.

Public result JSON and sanitized command logs are under gitignored `target/wallet-e2e/`. Origin-bearing descriptors, xpubs, PSBTs, and raw checkpoint details are in its `private/` directory. Mnemonics and pairing credentials are outside evidence in `target/wallet-e2e-secrets/`. Directories use mode 0700 and files use mode 0600 on Unix. Keep the secret directory for funded tests. The software mnemonic and destination mnemonic are required to recover these test funds.

One coordinator holds an operating-system file lock and owns outpoint reservations. Hardware workers hold a separate lock while connected, including if their parent exits. The coordinator runs device operations serially and records child commands, sanitized logs, and exit statuses. Jade live commands forward to the Trezor example's coordinator before opening BLE. Hot enrollment, matrix generation, offline checks, and reporting open no BLE sessions.

Overall `PASS` requires all 7,315 rows to reach confirmed real-network `PASS`. Missing holders, funding, firmware capabilities, and incomplete checkpoints remain visible blockers. Offline readiness never increments live acceptance. Default workspace tests make no network requests and use no hardware. Run the following checks after changes.

```sh
cargo test --workspace --locked
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo build --locked -p trezor-ble -p jade-ble --examples
bash wallet-e2e/verify.sh
```

## Replace a legacy Safe 7 enrollment

Older enrollments stored the Safe 7's BLE identifier as `physical_id`. Those records fail the identity check after a reconnect. Repeating `e2e enroll` also checks the existing record, so archive it before enrolling again. There is no automatic migration.

Use this procedure only for an unfunded `Prepared` case. Keep funded or later checkpoints for separate recovery.

1. Stop the coordinator and hardware workers.
2. Move the affected `private/enrollment/<slot>.json` into the backup's `enrollment/` subdirectory, outside `private/enrollment/`. These paths are under `target/wallet-e2e/`. Keep backup directories at mode 0700 and their files at mode 0600 on Unix.
3. Move `private/cases/<case-id>.json` into the backup's `cases/` subdirectory. Move `results/<case-id>.json` into its `results/` subdirectory. The checkpoint contains a copy of the old enrollment. Preserve unrelated cases and logs.
4. Keep `target/wallet-e2e-secrets/` unchanged. Reuse the slot's pairing credential and the destination mnemonic.
5. Reconnect the Safe 7 and run `e2e enroll --slot <slot>` with `--network testnet4`. Omit an obsolete BLE selector. With multiple devices, use a current selector from `scan`.
6. Reconnect before running `e2e prepare --case <case-id>`. Compare the new fingerprint, account origins, xpubs, descriptors, addresses, and destination against the backup before funding. Only `physical_id` should change.

Each hardware worker disconnects when its operation ends. Keep the device awake for the next connection. `prepare` verifies receive and change addresses in separate worker sessions.
