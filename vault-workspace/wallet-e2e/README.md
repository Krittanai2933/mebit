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

`offline` derives receive and change scripts independently at indices 0, 1, and 7. It signs and finalizes all 7,315 designated subsets with software fixtures at index 7. Fixtures representing hardware sources exercise transaction behavior; this offline check does not verify hardware behavior or live acceptance. Full real-network acceptance remains incomplete. See the [wallet verification report](../wallet-e2e-audit/verification.md) for the latest partial live results. Run `bash wallet-e2e/verify.sh` to check both executables, independent matrix expectations, and determinism across separate processes.

Enroll each independent test holder once. Canonical slots are `h1` through `h7`, `j1` through `j7`, and `t1` through `t7`. Hardware enrollment reads all five account xpubs through BLE. Device identity, fingerprint, origins, and xpubs bind every subsequent request. Duplicate physical identifiers, fingerprints, and account keys are refused.

Safe 7 enrollment uses the stable device ID from `GetFeatures`. Its Bluetooth address and advertised name can change between connections. Use scan identifiers only to select a device. A device wipe changes the stable ID and requires a new enrollment.

```sh
cargo run --locked -p trezor-ble --example trezor-hw-test -- --network testnet4 e2e enroll --slot h1
cargo run --locked -p jade-ble --example jade-hw-test -- --network testnet4 --name "Jade 123456" e2e enroll --slot j1 --jade-slot-empty
cargo run --locked -p trezor-ble --example trezor-hw-test -- --network testnet4 --device "Safe 7 selector from scan" e2e enroll --slot t1
```

For first Jade multisig use, manually verify that the `mebit_e2e` test registration slot is empty before passing `--jade-slot-empty`. Jade RPC hides records belonging to other seeds, so an absent response cannot prove the slot is empty. The flag records your declaration. The runner reads back every registration and refuses unrelated visible overwrites. It replaces a previous test registration only when readback matches the saved ownership descriptor. Before signing a resumed case, the coordinator restores that case's registration and verifies its address again. Firmware support still requires verification on your actual device. See the [Jade 1.0.41 RPC documentation](https://github.com/Blockstream/Jade/blob/1.0.41/docs/index.rst).

### Run a multisig with a remote Trezor

For a remote 3-of-3 using local hot key `h1`, local Jade `j1`, and your
friend's Safe 7 `t1`, use case
`multisig-p2wsh-3of3-a0-h1j1t1-s07-r0-74-c1-74`. A new Trezor seed means a
new `t1` identity and a different multisig address. Do not reuse an address or
funding outpoint from the previous Trezor enrollment.

Your friend enrolls the Safe 7 on their computer, then exports its public
identity. The mnemonic and BLE pairing credential stay on their computer:

```sh
cargo run --locked -p trezor-ble --example trezor-hw-test -- --network testnet4 e2e enroll --slot t1
cargo run --locked -p trezor-ble --example trezor-hw-test -- --network testnet4 e2e export-enrollment --slot t1 --out target/t1-public.json
```

Transfer `target/t1-public.json` to your computer and import it as `t1`. The
import command refuses to replace an existing different identity; preserve
that workspace and resolve the slot before proceeding. Prepare with
`--remote-slot t1`. The
coordinator writes one case-bound request for each Trezor address check. Send
each request to your friend; they approve the displayed address on the Safe 7,
then return the response. Import each response and repeat until the coordinator
prints the funding address.

```sh
cargo run --locked -p trezor-ble --example trezor-hw-test -- --network testnet4 e2e import-enrollment --slot t1 --file target/t1-public.json
cargo run --locked -p jade-ble --example jade-hw-test -- --network testnet4 --name "Jade BE0184" e2e prepare --case multisig-p2wsh-3of3-a0-h1j1t1-s07-r0-74-c1-74 --remote-slot t1
```

On your friend's computer, run the Trezor worker with the request file, approve
the operation on the device, and return the response file:

```sh
cargo run --locked -p trezor-ble --example trezor-hw-test -- --network testnet4 --device "Safe 7 selector" e2e remote-worker --request target/remote.request.json --response target/remote.response.json
```

On your computer, import the response using its local path. Repeat the same
worker/import exchange if `prepare` requests the second address check:

```sh
cargo run --locked -p trezor-ble --example trezor-hw-test -- --network testnet4 e2e import-remote --case multisig-p2wsh-3of3-a0-h1j1t1-s07-r0-74-c1-74 --response target/remote.response.json
```

After address checks complete, fund the new receive address with exactly
50,000 sats and wait for one confirmation. Start the spend with `--broadcast`.
The local hot key and Jade sign first; the coordinator then writes a request
for the Trezor signature. Your friend runs `remote-worker` for that request and
returns the response. Import it with `e2e import-remote`; the coordinator
validates the Trezor signatures against the saved PSBT, finalizes, broadcasts,
and waits for confirmation. No transaction is broadcast until your local run
command includes `--broadcast`.

This sequential remote-signing workflow was exercised by case
`multisig-p2wsh-3of3-a0-h1j1t1-s07-r0-74-c1-74`. It confirmed spend
`16ba3787cdcdef4dc85fa412cec081b73da174b4bb6efb8073b28314d0f7046c`
with a 414-sat fee. It demonstrates hot key + Jade signing locally followed by
the remote Trezor signature; it does not exercise independent PSBT copies
being signed separately and merged later. The case details and current report
status are in the [wallet verification report](../wallet-e2e-audit/verification.md).

### Supplemental check: independent copies of one unsigned PSBT

The separate protocol check is
`multisig-p2wsh-3of3-independent-a0-h1j1t1-s07-r1-74-c1-75`. It spends the
confirmed 44,586-sat change output from the sequential 3-of-3 transaction
above (`16ba3787cdcdef4dc85fa412cec081b73da174b4bb6efb8073b28314d0f7046c:1`).
It sends each signer the same saved unsigned PSBT, verifies each response
against that baseline, and merges only that signer's validated signatures.
The required manifest remains 7,315 cases; this supplemental result is tracked
separately in `report.json`.

Prepare the supplemental case using the already enrolled `h1`, `j1`, and `t1`.
The receive address is the previously verified multisig change address
`tb1qen9wltlce98h89j202l5x8lmdt6hg7r7ggpqt7q7lt69ag6t9rjsvgzz80`; its saved
proof is reused from the completed source case. The new change address at
branch 1/index 75 is checked during this preparation. Because `t1` is remote,
the friend handles that one address-verification request.

```sh
cargo run --locked -p jade-ble --example jade-hw-test -- \
  --network testnet4 --name "Jade BE0184" \
  e2e prepare \
  --case multisig-p2wsh-3of3-independent-a0-h1j1t1-s07-r1-74-c1-75 \
  --remote-slot t1
```

When `prepare` prints a private request path, send that file to the Trezor
holder. They approve the change address, run `e2e remote-worker`, and return
the response file. Import it using the printed case ID:

```sh
cargo run --locked -p trezor-ble --example trezor-hw-test -- \
  --network testnet4 --device "Safe 7 selector" \
  e2e remote-worker --request target/remote.request.json \
  --response target/remote.response.json

cargo run --locked -p jade-ble --example jade-hw-test -- \
  --network testnet4 --name "Jade BE0184" \
  e2e import-remote \
  --case multisig-p2wsh-3of3-independent-a0-h1j1t1-s07-r1-74-c1-75 \
  --response target/remote.response.json
```

Then run the pinned spend. The input must be exactly the still-unspent
44,586-sat output above. The transaction pays 5,000 sats to the same test
destination. The pre-sign conservative satisfaction-weight estimate was 438
sats (implying 39,148 sats of change), while the confirmed spend used a 414-sat
fee (207 vB at 2 sat/vB) and returned 39,172 sats to
`tb1q26wm7ccjtktcuw5eej2u4fdgphcmppqax6gjfmn9lj7v9yq5e70qqfn08j`. The spend
txid is `2f29b5e81d320db83ba8916ae42ab3c521814c9b556f6c7ec6d21ae097cf1c01`,
confirmed at block 156846. Jade and the hot signer each sign the original unsigned PSBT locally. The coordinator then
requests the Trezor signature on that same unsigned PSBT. Send the new private
request to your friend; they approve and return the signed response. Import it
with the command printed by the coordinator. Only then can the coordinator
merge, finalize, broadcast, and await confirmation.

```sh
cargo run --locked -p jade-ble --example jade-hw-test -- \
  --network testnet4 --name "Jade BE0184" \
  e2e run \
  --case multisig-p2wsh-3of3-independent-a0-h1j1t1-s07-r1-74-c1-75 \
  --funding-outpoint 16ba3787cdcdef4dc85fa412cec081b73da174b4bb6efb8073b28314d0f7046c:1 \
  --signing-mode independent --broadcast
```

The friend handles one remote address check during preparation and one remote
signing request during the spend. The public report records this result under
`supplemental_cases` and `supplemental_live_pass`; it never changes the required
matrix's `live_pass` or `remaining` counts.

`e2e report` freshly reconciles saved PASS checkpoints against testnet4. If the
host cannot reach mempool.space, the report keeps the earlier PASS count in
`recorded_live_pass`, reports zero successful fresh checks in `live_pass`, and
lists the network error as the blocker. Restore network access and rerun the
report before treating saved confirmations as current chain state.

Request and response files contain account xpubs and transaction details, but
not the mnemonic or pairing credential. Keep the files private and transfer
them over a channel you trust. The remote address check is attested by the
operator who reads the Safe 7 screen; the file itself is not a cryptographic
device attestation. The spend signatures are cryptographically verified by the
coordinator before finalization.

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
