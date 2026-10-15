#!/usr/bin/env python3
"""Pick the iOS runtime and iPhone device type for `verify.sh ios-start`.

  xcrun simctl list runtimes -j | ios_pick.py [RUNTIME_ID] [DEVICE_TYPE_ID]

Prints "RUNTIME_ID DEVICE_TYPE_ID". An empty argument means the default. The default runtime is the newest
available iOS runtime. The default device type is the first iPhone type that the chosen runtime supports, so
overriding only the runtime still gives a pair that simctl accepts.
"""
import json
import sys

runtime_id = sys.argv[1] if len(sys.argv) > 1 else ""
device_id = sys.argv[2] if len(sys.argv) > 2 else ""

runtimes = [r for r in json.load(sys.stdin)["runtimes"] if r.get("isAvailable") and ".iOS-" in r["identifier"]]
if not runtimes:
    sys.exit("no available iOS simulator runtime. Install one in Xcode > Settings > Components")
if runtime_id:
    runtime = next((r for r in runtimes if r["identifier"] == runtime_id), None)
    if runtime is None:
        sys.exit(f"runtime {runtime_id} is not installed. Available: " + ", ".join(r["identifier"] for r in runtimes))
else:
    runtime = max(runtimes, key=lambda r: [int(x) for x in r["version"].split(".")])

types = runtime["supportedDeviceTypes"]
if device_id:
    if device_id not in [t["identifier"] for t in types]:
        iphones = [t["identifier"] for t in types if t.get("productFamily") == "iPhone"]
        sys.exit(f"{device_id} is not supported by {runtime['identifier']}. iPhone types it supports include: "
                 + ", ".join(iphones[:4]))
    device = device_id
else:
    device = next((t["identifier"] for t in types if t.get("productFamily") == "iPhone"), "")
    if not device:
        sys.exit(f"{runtime['identifier']} supports no iPhone device type")
print(runtime["identifier"], device)
