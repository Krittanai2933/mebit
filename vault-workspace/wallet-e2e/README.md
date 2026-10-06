# Verify wallets on testnet4

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

```sh
cargo run --locked -p trezor-ble --example trezor-hw-test -- --network testnet4 e2e enroll --slot h1
cargo run --locked -p jade-ble --example jade-hw-test -- --network testnet4 --name "Jade 123456" e2e enroll --slot j1 --jade-slot-empty
cargo run --locked -p trezor-ble --example trezor-hw-test -- --network testnet4 --device "Safe 7 selector from scan" e2e enroll --slot t1
```

For first Jade multisig use, manually verify that the `mebit_e2e` test registration slot is empty before passing `--jade-slot-empty`. Jade RPC hides records belonging to other seeds, so an absent response cannot prove the slot is empty. The flag records your declaration. The runner reads back every registration and refuses unrelated visible overwrites. It replaces a previous test registration only when readback matches the saved ownership descriptor. Before signing a resumed case, the coordinator restores that case's registration and verifies its address again. Firmware support still requires verification on your actual device. See the [Jade 1.0.41 RPC documentation](https://github.com/Blockstream/Jade/blob/1.0.41/docs/index.rst).

Choose a case ID from the manifest. Enroll every holder that row requires, including holders outside its signing subset. Complete coverage needs up to seven distinct Jades and seven distinct Safe 7 devices. `prepare` checks the API genesis against rust-bitcoin's testnet4 genesis, compares independent host derivations, and requests address display on each hardware holder for both receive and change. The expected genesis is `00000000da84f2bafbbc53dee25a72ae507ff4914b867c565be350b0da8bf043`.

```sh
cargo run --locked -p trezor-ble --example trezor-hw-test -- --network testnet4 e2e prepare --case <case-id>
cargo run --locked -p trezor-ble --example trezor-hw-test -- --network testnet4 e2e run --case <case-id> --funding-outpoint <txid:vout> --broadcast
cargo run --locked -p trezor-ble --example trezor-hw-test -- --network testnet4 e2e resume --case <case-id>
```

Fund exactly 50,000 sats to the prepared receiving address. The runner requires one canonical funding confirmation and an unspent outpoint. It fetches the full previous transaction, authenticates its txid and output, and builds a real PSBT with all account origins. The payment is exactly 5,000 sats to a separately generated test-owned BIP84 destination. Change goes to the recorded change address. The selected fee is 2 sat/vB using a satisfaction-weight estimate. The runner refuses fees above 5,000 sats or an effective rate above 10 sat/vB.

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
