#!/usr/bin/env python3
"""Offline v1 proof for the saved index-12, one-key native P2WSH attempt."""

import argparse
import base64
import hashlib
import hmac
import json
import re
import struct
import sys


LIMIT = 16 * 1024 * 1024
P = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F
N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
G = (0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798,
     0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8)
ACCOUNT_PATH = [0x80000030, 0x80000001, 0x80000000, 0x80000002]
SOURCE_SCOPE = "length-framed names and bytes: session.rs,signing_trace.rs,bitcoin.rs,thp.rs,psbt_check.rs,trezor-ble/lib.rs,main.rs,coordinator.rs"


class EvidenceError(Exception):
    def __init__(self):
        super().__init__("evidence rejected")


def require(condition):
    if not condition:
        raise EvidenceError()


def sha(data):
    return hashlib.sha256(data).digest()


def sha2(data):
    return sha(sha(data))


def hash160(data):
    return hashlib.new("ripemd160", sha(data)).digest()


def add(a, b):
    if a is None:
        return b
    if b is None:
        return a
    x, y = a
    u, v = b
    if x == u and (y + v) % P == 0:
        return None
    slope = ((3 * x * x) * pow(2 * y, -1, P) if a == b
             else (v - y) * pow((u - x) % P, -1, P)) % P
    nx = (slope * slope - x - u) % P
    return nx, (slope * (x - nx) - y) % P


def multiply(k, point=G):
    result = None
    while k:
        if k & 1:
            result = add(result, point)
        point = add(point, point)
        k >>= 1
    return result


def public_key(data):
    require(len(data) == 33 and data[0] in (2, 3))
    x = int.from_bytes(data[1:], "big")
    require(x < P)
    y = pow((x * x * x + 7) % P, (P + 1) // 4, P)
    require(y * y % P == (x * x * x + 7) % P)
    if y & 1 != data[0] & 1:
        y = P - y
    return x, y


def compressed(point):
    require(point is not None)
    return bytes([2 | (point[1] & 1)]) + point[0].to_bytes(32, "big")


def derive(node, path):
    key, chain = node["key"], node["chain"]
    for index in path:
        require(type(index) is int and 0 <= index < 0x80000000)
        digest = hmac.new(chain, key + index.to_bytes(4, "big"), hashlib.sha512).digest()
        tweak = int.from_bytes(digest[:32], "big")
        require(0 < tweak < N)
        key = compressed(add(public_key(key), multiply(tweak)))
        chain = digest[32:]
    return key


def base58check(text):
    alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    require(isinstance(text, str) and 1 <= len(text) <= 128)
    n = 0
    for char in text:
        require(char in alphabet)
        n = n * 58 + alphabet.index(char)
    decoded = b"\0" * (len(text) - len(text.lstrip("1")))
    decoded += n.to_bytes((n.bit_length() + 7) // 8, "big")
    require(len(decoded) >= 5 and sha2(decoded[:-4])[:4] == decoded[-4:])
    return decoded[:-4]


def xpub(text):
    raw = base58check(text)
    require(len(raw) == 78 and raw[:4] == bytes.fromhex("043587cf"))
    public_key(raw[45:])
    return {"raw": raw, "depth": raw[4], "parent": int.from_bytes(raw[5:9], "big"),
            "child": int.from_bytes(raw[9:13], "big"), "chain": raw[13:45], "key": raw[45:]}


def address_script(address):
    require(isinstance(address, str) and len(address) <= 128)
    if address.lower().startswith("tb1"):
        require(address == address.lower())
        alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        values = [alphabet.index(c) for c in address[3:]]
        require(len(values) >= 7)
        chk = 1
        for value in [3, 3, 0, 20, 2] + values:
            top = chk >> 25
            chk = ((chk & 0x1ffffff) << 5) ^ value
            for bit, generator in enumerate([0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]):
                if (top >> bit) & 1:
                    chk ^= generator
        version = values[0]
        require(version <= 16 and chk == (1 if version == 0 else 0x2bc830a3))
        accumulator = bits = 0
        program = bytearray()
        for value in values[1:-6]:
            accumulator = (accumulator << 5) | value
            bits += 5
            if bits >= 8:
                bits -= 8
                program.append((accumulator >> bits) & 255)
        require(bits < 5 and (accumulator & ((1 << bits) - 1)) == 0)
        require(2 <= len(program) <= 40 and (version != 0 or len(program) in (20, 32)))
        return bytes([0 if version == 0 else 0x50 + version, len(program)]) + program
    data = base58check(address)
    require(len(data) == 21 and data[0] in (111, 196))
    return (b"\x76\xa9\x14" + data[1:] + b"\x88\xac" if data[0] == 111
            else b"\xa9\x14" + data[1:] + b"\x87")


def compact(value):
    if value < 253:
        return bytes([value])
    if value <= 65535:
        return b"\xfd" + struct.pack("<H", value)
    if value <= 0xffffffff:
        return b"\xfe" + struct.pack("<I", value)
    return b"\xff" + struct.pack("<Q", value)


class Reader:
    def __init__(self, data):
        require(isinstance(data, bytes) and len(data) <= LIMIT)
        self.data = data
        self.pos = 0

    def take(self, count):
        require(0 <= count <= len(self.data) - self.pos)
        result = self.data[self.pos:self.pos + count]
        self.pos += count
        return result

    def integer(self, size):
        return int.from_bytes(self.take(size), "little")

    def compact(self):
        first = self.integer(1)
        if first < 253:
            return first
        size = {253: 2, 254: 4, 255: 8}[first]
        value = self.integer(size)
        require(value >= {2: 253, 4: 65536, 8: 0x100000000}[size])
        return value

    def blob(self):
        return self.take(self.compact())

    def varint(self):
        value = 0
        for shift in range(0, 70, 7):
            byte = self.integer(1)
            require(shift != 63 or byte <= 1)
            value |= (byte & 127) << shift
            if byte < 128:
                require(shift == 0 or byte != 0)
                return value
        raise EvidenceError()

    def done(self):
        require(self.pos == len(self.data))


def transaction(raw, unsigned=False):
    reader = Reader(raw)
    version = reader.integer(4)
    witness = reader.data[reader.pos:reader.pos + 2] == b"\0\1"
    if witness:
        reader.take(2)
    require(not unsigned or not witness)
    count = reader.compact()
    require(0 < count <= 10000)
    inputs = []
    for _ in range(count):
        inputs.append({"hash": reader.take(32), "index": reader.integer(4),
                       "script": reader.blob(), "sequence": reader.integer(4)})
    count = reader.compact()
    require(0 < count <= 10000)
    outputs = [{"amount": reader.integer(8), "script": reader.blob()} for _ in range(count)]
    require(all(output["amount"] <= 21000000 * 100000000 for output in outputs))
    if witness:
        any_witness = False
        for _ in inputs:
            stack_size = reader.compact()
            require(stack_size <= 10000)
            any_witness |= stack_size > 0
            for _ in range(stack_size):
                reader.blob()
        require(any_witness)
    lock = reader.integer(4)
    reader.done()
    if unsigned:
        require(all(not item["script"] for item in inputs))
    tx = {"version": version, "lock": lock, "inputs": inputs, "outputs": outputs}
    tx["stripped"] = serialize_tx(tx)
    tx["txid"] = sha2(tx["stripped"])[::-1]
    return tx


def input_bytes(item):
    return item["hash"] + struct.pack("<I", item["index"]) + compact(len(item["script"])) + item["script"] + struct.pack("<I", item["sequence"])


def output_bytes(item):
    return struct.pack("<Q", item["amount"]) + compact(len(item["script"])) + item["script"]


def serialize_tx(tx):
    return (struct.pack("<I", tx["version"]) + compact(len(tx["inputs"]))
            + b"".join(map(input_bytes, tx["inputs"])) + compact(len(tx["outputs"]))
            + b"".join(map(output_bytes, tx["outputs"])) + struct.pack("<I", tx["lock"]))


def psbt_map(reader):
    result = {}
    while True:
        key = reader.blob()
        if not key:
            return result
        require(key not in result)
        result[key] = reader.blob()


def psbt(text):
    raw = base64.b64decode(text, validate=True)
    reader = Reader(raw)
    require(reader.take(5) == b"psbt\xff")
    global_map = psbt_map(reader)
    require(b"\0" in global_map and b"\xfb" not in global_map)
    tx = transaction(global_map[b"\0"], unsigned=True)
    inputs = [psbt_map(reader) for _ in tx["inputs"]]
    outputs = [psbt_map(reader) for _ in tx["outputs"]]
    reader.done()
    return raw, global_map, tx, inputs, outputs


def origin(data):
    require(len(data) >= 4 and len(data) % 4 == 0)
    return data[:4], list(struct.unpack("<" + "I" * ((len(data) - 4) // 4), data[4:]))


def proto(data, schema):
    reader = Reader(data)
    fields = {}
    while reader.pos < len(data):
        tag = reader.varint()
        number, wire = tag >> 3, tag & 7
        require(number in schema)
        expected_wire, repeated = schema[number]
        require(wire == expected_wire or (repeated and expected_wire == 0 and wire == 2))
        values = []
        if wire == 0:
            values.append(reader.varint())
        else:
            value = reader.take(reader.varint())
            if expected_wire == 0:
                packed = Reader(value)
                while packed.pos < len(value):
                    values.append(packed.varint())
            else:
                values.append(value)
        if repeated:
            fields.setdefault(number, []).extend(values)
        else:
            require(number not in fields and len(values) == 1)
            fields[number] = values[0]
    return fields


def schema(integers=(), blobs=(), repeated_integers=(), repeated_blobs=()):
    return {**{key: (0, False) for key in integers}, **{key: (2, False) for key in blobs},
            **{key: (0, True) for key in repeated_integers}, **{key: (2, True) for key in repeated_blobs}}


def multisig(data, node, suffix):
    fields = proto(data, schema((3, 6), repeated_integers=(5,), repeated_blobs=(1, 2, 4)))
    require(set(fields) == {3, 4, 5, 6})
    require(fields[3] == 1 and fields[6] == 1 and fields[5] == suffix and len(fields[4]) == 1)
    parsed = proto(fields[4][0], schema((1, 2, 3), (4, 5, 6)))
    require(parsed == {1: node["depth"], 2: node["parent"], 3: node["child"],
                       4: node["chain"], 6: node["key"]})


def bip143(tx, amount, script):
    item = tx["inputs"][0]
    preimage = (struct.pack("<I", tx["version"])
                + sha2(b"".join(i["hash"] + struct.pack("<I", i["index"]) for i in tx["inputs"]))
                + sha2(b"".join(struct.pack("<I", i["sequence"]) for i in tx["inputs"]))
                + item["hash"] + struct.pack("<I", item["index"])
                + compact(len(script)) + script + struct.pack("<Q", amount)
                + struct.pack("<I", item["sequence"])
                + sha2(b"".join(map(output_bytes, tx["outputs"])))
                + struct.pack("<II", tx["lock"], 1))
    return sha2(preimage)


def verify_signature(der, key, digest):
    try:
        reader = Reader(der)
        require(reader.take(1) == b"\x30" and reader.integer(1) == len(der) - 2)
        values = []
        for _ in range(2):
            require(reader.take(1) == b"\x02")
            data = reader.take(reader.integer(1))
            require(data and not data[0] & 128 and (len(data) == 1 or data[0] != 0 or data[1] & 128))
            value = int.from_bytes(data, "big")
            require(0 < value < N)
            values.append(value)
        reader.done()
        r, s = values
        require(s <= N // 2)
        inverse = pow(s, -1, N)
        point = add(multiply(int.from_bytes(digest, "big") * inverse % N),
                    multiply(r * inverse % N, public_key(key)))
        return point is not None and point[0] % N == r
    except (EvidenceError, ValueError, IndexError):
        return False


def validate(checkpoint, trace):
    require(type(checkpoint["schema"]) is int and checkpoint["schema"] == 1)
    require(type(trace["schema"]) is int and trace["schema"] == 1)
    case = checkpoint["case"]
    require(case["family"] == "p2wsh" and case["account"] == 0 and case["threshold"] == 1)
    require(case["receive"] == {"branch": 0, "index": 12} and case["change"] == {"branch": 1, "index": 12})
    require(len(case["holders"]) == 1 and case["holders"] == case["signing_subset"])
    slot = case["holders"][0]
    require(slot["source"] == "trezor")
    require(set(checkpoint["progress"]) == {"Signing"})
    signing = checkpoint["progress"]["Signing"]
    require(signing["completed"] == [])
    enrolled = [e for e in checkpoint["enrolled"] if e["slot"] == slot]
    require(len(enrolled) == 1)
    identity = enrolled[0]["identity"]
    for field in ("firmware", "fingerprint", "physical_id"):
        require(isinstance(trace[field], str) and trace[field] == identity[field] and bool(trace[field].strip()))
    fingerprint = bytes.fromhex(identity["fingerprint"])
    require(len(fingerprint) == 4)
    for field in ("build_source_sha256", "executable_sha256", "psbt_sha256", "transaction_id"):
        require(isinstance(trace[field], str) and re.fullmatch(r"[0-9a-f]{64}", trace[field]))
    require(trace["build_source_scope"] == SOURCE_SCOPE)
    require(trace["handoff"] == "THP plaintext acceptance, not radio delivery")
    require(trace["incomplete"] is False and trace["end"] in ("Completed", "Rejected"))
    account = identity["accounts"]["p2wsh"]
    require(account["origin"] == "m/48'/1'/0'/2'")
    node = xpub(account["xpub"])
    require(node["depth"] == 4 and node["child"] == 0x80000002)
    raw, global_map, tx, inputs, outputs = psbt(signing["signed_psbt"])
    require(sha(raw).hex() == trace["psbt_sha256"] and tx["txid"].hex() == trace["transaction_id"])
    _, original_global, original_tx, original_inputs, original_outputs = psbt(signing["unsigned_psbt"])
    require(original_global == global_map and original_tx == tx and original_outputs == outputs)
    require(len(inputs) == 1 and len(outputs) == 2)
    require(inputs == original_inputs)
    xpubs = [(key[1:], value) for key, value in global_map.items() if key[0] == 1]
    require(set(global_map) == {b"\0", b"\x01" + node["raw"]})
    require(xpubs == [(node["raw"], fingerprint + b"".join(struct.pack("<I", i) for i in ACCOUNT_PATH))])
    child = derive(node, [0, 12])
    witness = b"\x51\x21" + child + b"\x51\xae"
    funded_script = b"\0\x20" + sha(witness)
    input_map = inputs[0]
    require(set(input_map) == {b"\0", b"\x01", b"\x03", b"\x05", b"\x06" + child})
    require(input_map[b"\x05"] == witness and input_map[b"\x03"] == struct.pack("<I", 1))
    derivations = [(key[1:], origin(value)) for key, value in input_map.items() if key[0] == 6]
    require(derivations == [(child, (fingerprint, ACCOUNT_PATH + [0, 12]))])
    previous = transaction(input_map[b"\0"])
    funding = signing["funding"]
    require(transaction(bytes.fromhex(funding["transaction_hex"])) == previous)
    item = tx["inputs"][0]
    require(item["hash"][::-1] == previous["txid"] and item["index"] < len(previous["outputs"]))
    require(funding["outpoint"] == previous["txid"].hex() + ":" + str(item["index"]))
    spent = previous["outputs"][item["index"]]
    require(spent["script"] == funded_script and spent["amount"] == funding["amount_sats"])
    require(input_map[b"\x01"] == output_bytes(spent))
    require(address_script(checkpoint["receive_address"]) == funded_script)
    change_key = derive(node, [1, 12])
    change_witness = b"\x51\x21" + change_key + b"\x51\xae"
    change_script = b"\0\x20" + sha(change_witness)
    require(tx["outputs"][0]["script"] == address_script(checkpoint["destination"]))
    require(tx["outputs"][1]["script"] == change_script == address_script(checkpoint["change_address"]))
    require(outputs[1][b"\x01"] == change_witness)
    require(not outputs[0] and set(outputs[1]) == {b"\x01", b"\x02" + change_key})
    changes = [(key[1:], origin(value)) for key, value in outputs[1].items() if key[0] == 2]
    require(changes == [(change_key, (fingerprint, ACCOUNT_PATH + [1, 12]))])
    require(not any(key[0] == 2 for key in outputs[0]))
    require(all(output["amount"] > 0 for output in tx["outputs"]))
    require(sum(output["amount"] for output in tx["outputs"]) < spent["amount"])
    frames = trace["frames"]
    require(isinstance(frames, list) and 1 < len(frames) <= 4096)
    payloads = []
    for sequence, frame in enumerate(frames):
        require(type(frame["sequence"]) is int and frame["sequence"] == sequence)
        require(type(frame["outbound"]) is bool and type(frame["session"]) is int and frame["session"] == 1)
        require(type(frame["message_type"]) is int)
        require(isinstance(frame["payload"], str) and re.fullmatch(r"(?:[0-9a-f]{2})*", frame["payload"]))
        data = bytes.fromhex(frame["payload"])
        require(len(data) <= 1024 * 1024)
        payloads.append(data)
    require(sum(map(len, payloads)) <= 4 * 1024 * 1024)
    require(frames[0]["outbound"] and frames[0]["message_type"] == 15)
    require(proto(payloads[0], schema((1, 2, 4, 5, 13), (3,))) == {
        1: len(outputs), 2: len(inputs), 3: b"Testnet", 4: tx["version"], 5: tx["lock"], 13: 0})
    seen = {}
    signature = None
    cursor = 1
    finished = False
    while cursor < len(frames):
        request_frame = frames[cursor]
        require(not request_frame["outbound"] and request_frame["message_type"] == 21)
        request = proto(payloads[cursor], schema((1,), (2, 3)))
        require(1 in request)
        if 3 in request:
            serialized = proto(request[3], schema((1,), (2, 3)))
            require((1 in serialized) == (2 in serialized) and not serialized.get(3, b""))
            if 1 in serialized:
                require(serialized[1] == 0 and signature is None)
                signature = serialized[2]
        kind = request[1]
        if kind == 3:
            require(cursor == len(frames) - 1)
            terminal_details = proto(request.get(2, b""), schema((1,)))
            require(terminal_details in ({}, {1: 0}))
            finished = True
            break
        require(kind in (0, 1, 2) and 2 in request and cursor + 1 < len(frames))
        details = proto(request[2], schema((1,), (2,)))
        index = details.get(1, 0)
        require(index <= 0xffffffff)
        previous_hash = details.get(2)
        require(previous_hash is None or previous_hash == previous["txid"])
        ack_frame = frames[cursor + 1]
        require(ack_frame["outbound"] and ack_frame["message_type"] == 22)
        ack = proto(payloads[cursor + 1], schema(blobs=(1,)))
        require(set(ack) == {1})
        if previous_hash is not None and kind == 2:
            require(index == 0)
            require(proto(ack[1], schema((1, 4, 6, 7))) == {
                1: previous["version"], 4: previous["lock"], 6: len(previous["inputs"]), 7: len(previous["outputs"])})
        elif previous_hash is not None:
            wrapper_field = 2 if kind == 0 else 3
            wrapper = proto(ack[1], schema(blobs=(wrapper_field,)))
            require(set(wrapper) == {wrapper_field})
            if kind == 0:
                require(index < len(previous["inputs"]))
                expected = previous["inputs"][index]
                require(proto(wrapper[2], schema((3, 5), (2, 4))) == {
                    2: expected["hash"][::-1], 3: expected["index"], 4: expected["script"], 5: expected["sequence"]})
            else:
                require(index < len(previous["outputs"]))
                expected = previous["outputs"][index]
                require(proto(wrapper[3], schema((1,), (2,))) == {1: expected["amount"], 2: expected["script"]})
        elif kind == 0:
            require(index == 0)
            wrapper = proto(ack[1], schema(blobs=(2,)))
            require(set(wrapper) == {2})
            fields = proto(wrapper[2], schema((3, 5, 6, 8), (2, 7), (1,)))
            require(set(fields) == {1, 2, 3, 5, 6, 7, 8})
            require({k: v for k, v in fields.items() if k != 7} == {
                1: ACCOUNT_PATH + [0, 12], 2: item["hash"][::-1], 3: item["index"],
                5: item["sequence"], 6: 3, 8: spent["amount"]})
            multisig(fields[7], node, [0, 12])
        elif kind == 1:
            require(index < len(outputs))
            wrapper = proto(ack[1], schema(blobs=(5,)))
            require(set(wrapper) == {5})
            fields = proto(wrapper[5], schema((3, 4), (1, 5), (2,)))
            expected = tx["outputs"][index]
            if index == 0:
                require(set(fields) == {1, 3, 4} and fields[3] == expected["amount"] and fields[4] == 0)
                require(address_script(fields[1].decode("ascii")) == expected["script"])
            else:
                require(set(fields) == {2, 3, 4, 5})
                require(fields[2] == ACCOUNT_PATH + [1, 12] and fields[3] == expected["amount"] and fields[4] == 4)
                multisig(fields[5], node, [1, 12])
        else:
            raise EvidenceError()
        request_key = (previous_hash is not None, kind, index)
        seen[request_key] = seen.get(request_key, 0) + 1
        cursor += 2
    require(finished and signature is not None)
    required = {(False, 0, 0), (False, 1, 0), (False, 1, 1), (True, 2, 0)}
    required.update((True, 0, i) for i in range(len(previous["inputs"])))
    required.update((True, 1, i) for i in range(len(previous["outputs"])))
    require(required <= seen.keys() and seen[(False, 0, 0)] >= 3)
    expected = verify_signature(signature, child, bip143(tx, spent["amount"], witness))
    alternate_script = b"\x76\xa9\x14" + hash160(child) + b"\x88\xac"
    alternate = verify_signature(signature, child, bip143(tx, spent["amount"], alternate_script))
    return {"request_valid": True, "expected_p2wsh_signature_valid": expected,
            "alternate_p2wpkh_signature_valid": alternate, "signing_pass": expected}


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result)
        result[key] = value
    return result


def load_json(path):
    with open(path, "rb") as stream:
        data = stream.read(LIMIT + 1)
    require(len(data) <= LIMIT)
    return json.loads(data, object_pairs_hook=unique_object,
                      parse_constant=lambda _: (_ for _ in ()).throw(EvidenceError()))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--trace", required=True)
    args = parser.parse_args(argv)
    try:
        result = validate(load_json(args.checkpoint), load_json(args.trace))
    except (EvidenceError, ValueError, KeyError, TypeError, IndexError, OverflowError, OSError, RecursionError):
        result = {"request_valid": False, "expected_p2wsh_signature_valid": False,
                  "alternate_p2wpkh_signature_valid": False, "signing_pass": False,
                  "error": "malformed, incomplete, or mismatched evidence"}
    print(json.dumps(result, sort_keys=True))
    return 0 if result["request_valid"] and result["expected_p2wsh_signature_valid"] else 1


if __name__ == "__main__":
    sys.exit(main())
