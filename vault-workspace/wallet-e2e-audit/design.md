# Testnet4 runner design

The examples share example-only Rust modules for case enumeration, descriptor construction, software account-key resolution, private records, and chain checks. No new workspace crate or public single-sig derivation API is added.

The Trezor example owns the serial coordinator. Hardware operations run as child invocations of the existing examples. Typed private worker requests carry enrollment, descriptor/index/signer address verification, or a real PSBT. Responses carry enrolled identities, verified addresses, or verified signatures. Child exit status and sanitized logs become evidence. Offline commands return before any hardware client is constructed.

Cases use canonical hot, Jade, and Safe 7 slots. A case fixes its account, script family or threshold, source composition, exact signing subset, and receive/change indices. Index allocation follows stable enumeration, with no source permutations generating additional funded cases.

Enrollment stores origins and account xpubs privately. Software mnemonics and pairing credentials live outside the evidence directory. The coordinator holds an operating-system file lock on its evidence root and reserves outpoints. The operating system releases the lock when its process exits.

Each case records address verification, confirmed funding, validated PSBT, designated signatures, finalized bytes, broadcast, and canonical confirmation. Before broadcasting, finalized bytes and their txid are saved atomically. Resume checks the expected transaction and every input's spending state. It can submit only those same bytes, with explicit broadcast authorization saved in the checkpoint.

The acceptance predicate is all 7,315 rows with verified BLE addresses where applicable, authentic previous transactions, exact designated signatures, and one canonical spend confirmation on testnet4. The implementation predicate is deterministic manifest generation, independent receive/change checks, successful real PSBT signing and finalization offline, mutation rejection, safe reservation/resume behavior, and working commands in both examples. Devices and funds remain a separate blocker.
