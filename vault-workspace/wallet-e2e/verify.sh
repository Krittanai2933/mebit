#!/usr/bin/env bash
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"
artifacts=target/wallet-e2e/cli-verification
mkdir -p "$artifacts"
cargo build --locked -p trezor-ble -p jade-ble --examples
for device in trezor jade; do
    target/debug/examples/"$device"-hw-test --network testnet4 e2e matrix > "$artifacts/$device-matrix.txt"
    cp target/wallet-e2e/manifest.json "$artifacts/$device-manifest.json"
    target/debug/examples/"$device"-hw-test --network testnet4 e2e offline > "$artifacts/$device-offline.json"
    if target/debug/examples/"$device"-hw-test --network bitcoin e2e matrix > "$artifacts/$device-wrong-network.txt" 2>&1; then
        printf '%s\n' 'Wrong network was accepted.' >&2
        exit 1
    fi
done
target/debug/examples/trezor-hw-test --network testnet4 e2e report > "$artifacts/report.txt"
if target/debug/examples/trezor-hw-test --network testnet4 e2e prepare --case unknown > "$artifacts/unknown-case.txt" 2>&1; then
    printf '%s\n' 'Unknown case was accepted.' >&2
    exit 1
fi
python3 - "$artifacts" <<'PY'
import itertools
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
manifests = [json.loads((root / f"{d}-manifest.json").read_text()) for d in ("trezor", "jade")]
assert manifests[0] == manifests[1], "different processes generated different manifests"
cases = manifests[0]["cases"]
assert len(cases) == 7315
assert len({c["id"] for c in cases}) == 7315
assert [c["receive"]["index"] for c in cases] == list(range(7315))
assert all(c["account"] == 0 and c["receive"]["branch"] == 0 and c["change"]["branch"] == 1 for c in cases)
actual = set()
for case in cases:
    if case["family"] != "p2wsh":
        continue
    holders = [(s["source"], s["number"]) for s in case["holders"]]
    subset = frozenset((s["source"], s["number"]) for s in case["signing_subset"])
    counts = tuple(sum(s == source for s, _ in holders) for source in ("hot", "jade", "trezor"))
    assert len(subset) == case["threshold"]
    actual.add((counts, case["threshold"], subset))
expected = set()
for n in range(1, 8):
    for hot in range(n + 1):
        for jade in range(n - hot + 1):
            counts = (hot, jade, n - hot - jade)
            holders = [(source, i) for source, count in zip(("hot", "jade", "trezor"), counts) for i in range(1, count + 1)]
            for threshold in range(1, n + 1):
                for subset in itertools.combinations(holders, threshold):
                    expected.add((counts, threshold, frozenset(subset)))
assert actual == expected, "missing composition, threshold, or designated subset"
offline = [json.loads((root / f"{d}-offline.json").read_text()) for d in ("trezor", "jade")]
assert offline[0] == offline[1], "receive/change derivation or transaction results changed between processes"
assert offline[0]["status"] == "PASS" and offline[0]["signed_and_finalized_cases"] == 7315
assert offline[0]["live_pass"] == 0 and offline[0]["hardware_behavior"] == "UNVERIFIED"
for device in ("trezor", "jade"):
    failure = (root / f"{device}-wrong-network.txt").read_text()
    assert "e2e requires --network testnet4" in failure and "Scanning" not in failure
assert "unknown case" in (root / "unknown-case.txt").read_text()
report = json.loads(pathlib.Path("target/wallet-e2e/report.json").read_text())
assert report["required_cases"] == 7315 and len(report["cases"]) == 7315
print("PASS. Canonical matrix, all designated software subsets, repeated-process determinism, reporting, and CLI rejection checks.")
print(f"Recorded real-network PASS {report['live_pass']}/7315. Overall {report['overall']}.")
PY
