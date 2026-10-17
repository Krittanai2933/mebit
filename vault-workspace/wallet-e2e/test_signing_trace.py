import base64
import copy
import hashlib
import hmac
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
from contextlib import redirect_stdout

import verify_signing_trace as v


def varint(n):
    result = bytearray()
    while n >= 128:
        result.append((n & 127) | 128)
        n >>= 7
    return bytes(result) + bytes([n])


def pb(fields):
    data = b""
    for number, value in fields:
        if isinstance(value, int):
            data += varint(number << 3) + varint(value)
        else:
            data += varint((number << 3) | 2) + varint(len(value)) + value
    return data


def b58(data):
    alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    encoded = data + v.sha2(data)[:4]
    n = int.from_bytes(encoded, "big")
    result = ""
    while n:
        n, index = divmod(n, 58)
        result = alphabet[index] + result
    return "1" * (len(encoded) - len(encoded.lstrip(b"\0"))) + result


def address(script):
    alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    bits = accumulator = 0
    values = [0]
    for byte in script[2:]:
        accumulator = (accumulator << 8) | byte
        bits += 8
        while bits >= 5:
            bits -= 5
            values.append((accumulator >> bits) & 31)
    if bits:
        values.append((accumulator << (5 - bits)) & 31)
    checksum = 1
    for value in [3, 3, 0, 20, 2] + values + [0] * 6:
        top = checksum >> 25
        checksum = ((checksum & 0x1ffffff) << 5) ^ value
        for i, generator in enumerate([0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]):
            if (top >> i) & 1:
                checksum ^= generator
    checksum ^= 1
    return "tb1" + "".join(alphabet[n] for n in values + [(checksum >> (5 * (5 - i))) & 31 for i in range(6)])


def psbt_bytes(global_fields, inputs, outputs):
    def encode_map(fields):
        return b"".join(v.compact(len(k)) + k + v.compact(len(value)) + value for k, value in fields.items()) + b"\0"
    return b"psbt\xff" + b"".join(encode_map(fields) for fields in [global_fields] + inputs + outputs)


def private_child(secret, chain, path):
    for index in path:
        digest = hmac.new(chain, v.compressed(v.multiply(secret)) + index.to_bytes(4, "big"), hashlib.sha512).digest()
        secret = (secret + int.from_bytes(digest[:32], "big")) % v.N
        chain = digest[32:]
    return secret


def sign(secret, digest, high_s=False):
    nonce = 11
    r = v.multiply(nonce)[0] % v.N
    s = ((int.from_bytes(digest, "big") + r * secret) * pow(nonce, -1, v.N)) % v.N
    s = max(s, v.N - s) if high_s else min(s, v.N - s)
    def integer(n):
        data = n.to_bytes((n.bit_length() + 7) // 8, "big")
        if data[0] & 128:
            data = b"\0" + data
        return b"\x02" + bytes([len(data)]) + data
    body = integer(r) + integer(s)
    return b"\x30" + bytes([len(body)]) + body


def fixture(alternate=False, high_s=False):
    fingerprint = bytes.fromhex("12345678")
    chain = bytes(range(32))
    secret = 7
    raw_xpub = bytes.fromhex("043587cf04aabbccdd80000002") + chain + v.compressed(v.multiply(secret))
    node = v.xpub(b58(raw_xpub))
    child = v.derive(node, [0, 12])
    change_child = v.derive(node, [1, 12])
    witness = b"\x51\x21" + child + b"\x51\xae"
    change_witness = b"\x51\x21" + change_child + b"\x51\xae"
    script = b"\0\x20" + v.sha(witness)
    change_script = b"\0\x20" + v.sha(change_witness)
    payment = b"\0\x14" + b"\x33" * 20
    previous = {"version": 2, "lock": 19,
                "inputs": [{"hash": b"\x22" * 32, "index": 3, "script": b"\x01\x51", "sequence": 0xfffffffe}],
                "outputs": [{"amount": 35000, "script": script}, {"amount": 1000, "script": payment}]}
    previous_raw = v.serialize_tx(previous)
    previous_id = v.sha2(previous_raw)[::-1]
    tx = {"version": 2, "lock": 23,
          "inputs": [{"hash": previous_id[::-1], "index": 0, "script": b"", "sequence": 0xfffffffd}],
          "outputs": [{"amount": 5000, "script": payment}, {"amount": 29600, "script": change_script}]}
    tx_raw = v.serialize_tx(tx)
    def origin(suffix):
        return fingerprint + b"".join(struct.pack("<I", n) for n in v.ACCOUNT_PATH + suffix)
    globals_map = {b"\0": tx_raw, b"\x01" + raw_xpub: origin([])}
    input_map = {b"\0": previous_raw, b"\x01": v.output_bytes(previous["outputs"][0]),
                 b"\x03": struct.pack("<I", 1), b"\x05": witness, b"\x06" + child: origin([0, 12])}
    output_maps = [{}, {b"\x01": change_witness, b"\x02" + change_child: origin([1, 12])}]
    raw_psbt = psbt_bytes(globals_map, [input_map], output_maps)
    slot = {"source": "trezor", "number": 0}
    identity = {"firmware": "2.12.5", "fingerprint": fingerprint.hex(), "physical_id": "synthetic",
                "accounts": {"p2wsh": {"origin": "m/48'/1'/0'/2'", "xpub": b58(raw_xpub)}}}
    checkpoint = {"schema": 1, "case": {"family": "p2wsh", "account": 0, "threshold": 1,
                  "holders": [slot], "signing_subset": [slot], "receive": {"branch": 0, "index": 12},
                  "change": {"branch": 1, "index": 12}}, "enrolled": [{"slot": slot, "identity": identity}],
                  "receive_address": address(script), "change_address": address(change_script),
                  "destination": address(payment), "progress": {"Signing": {"completed": [],
                  "unsigned_psbt": base64.b64encode(raw_psbt).decode(), "signed_psbt": base64.b64encode(raw_psbt).decode(),
                  "funding": {"outpoint": previous_id.hex() + ":0", "transaction_hex": previous_raw.hex(), "amount_sats": 35000}}}}
    node_fields = [(1, 4), (2, 0xaabbccdd), (3, 0x80000002), (4, chain), (6, node["key"])]
    def multisig(suffix):
        return pb([(3, 1), (4, pb(node_fields))] + [(5, i) for i in suffix] + [(6, 1)])
    current_input = [(1, n) for n in v.ACCOUNT_PATH + [0, 12]] + [
        (2, previous_id), (3, 0), (5, 0xfffffffd), (6, 3), (7, multisig([0, 12])), (8, 35000)]
    current_outputs = [[(1, address(payment).encode()), (3, 5000), (4, 0)],
                       [(2, n) for n in v.ACCOUNT_PATH + [1, 12]] + [(3, 29600), (4, 4), (5, multisig([1, 12]))]]
    frames = []
    def frame(outbound, kind, payload):
        frames.append({"sequence": len(frames), "outbound": outbound, "session": 1,
                       "message_type": kind, "payload": payload.hex()})
    frame(True, 15, pb([(1, 2), (2, 1), (3, b"Testnet"), (4, 2), (5, 23), (13, 0)]))
    def roundtrip(kind, index, ack, previous=False):
        details = [(1, index)] + ([(2, previous_id)] if previous else [])
        frame(False, 21, pb([(1, kind), (2, pb(details))]))
        frame(True, 22, pb([(1, ack)]))
    roundtrip(0, 0, pb([(2, pb(current_input))]))
    roundtrip(2, 0, pb([(1, 2), (4, 19), (6, 1), (7, 2)]), True)
    roundtrip(0, 0, pb([(2, pb([(2, b"\x22" * 32), (3, 3), (4, b"\x01\x51"), (5, 0xfffffffe)]))]), True)
    for index, output in enumerate(previous["outputs"]):
        roundtrip(1, index, pb([(3, pb([(1, output["amount"]), (2, output["script"])]))]), True)
    for _ in range(2):
        for index, output in enumerate(current_outputs):
            roundtrip(1, index, pb([(5, pb(output))]))
        roundtrip(0, 0, pb([(2, pb(current_input))]))
    signature_script = b"\x76\xa9\x14" + v.hash160(child) + b"\x88\xac" if alternate else witness
    signature = sign(private_child(secret, chain, [0, 12]), v.bip143(tx, 35000, signature_script), high_s)
    frame(False, 21, pb([(1, 3), (3, pb([(1, 0), (2, signature)]))]))
    trace = {"schema": 1, "transaction_id": v.sha2(tx_raw)[::-1].hex(), "psbt_sha256": v.sha(raw_psbt).hex(),
             "firmware": identity["firmware"], "fingerprint": identity["fingerprint"], "physical_id": identity["physical_id"],
             "build_source_sha256": "a" * 64, "executable_sha256": "b" * 64, "build_source_scope": v.SOURCE_SCOPE,
             "runtime_git_revision": None, "handoff": "THP plaintext acceptance, not radio delivery",
             "incomplete": False, "end": "Rejected" if alternate else "Completed", "frames": frames}
    return checkpoint, trace


def replace_field(data, number, replacement, occurrence=0):
    reader = v.Reader(data)
    result = b""
    found = 0
    while reader.pos < len(data):
        start = reader.pos
        tag = reader.varint()
        if tag & 7 == 0:
            reader.varint()
        else:
            reader.take(reader.varint())
        if tag >> 3 == number and found == occurrence:
            if replacement is not None:
                result += pb([(number, replacement)])
            found += 1
        else:
            result += data[start:reader.pos]
            if tag >> 3 == number:
                found += 1
    return result


class SigningTraceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.checkpoint, cls.trace = fixture()

    def assert_rejected(self, checkpoint, trace):
        with self.assertRaises(v.EvidenceError):
            v.validate(checkpoint, trace)

    def test_valid_and_wrong_hash_outcomes(self):
        self.assertEqual(v.validate(self.checkpoint, self.trace), {
            "request_valid": True, "expected_p2wsh_signature_valid": True,
            "alternate_p2wpkh_signature_valid": False, "signing_pass": True})
        checkpoint, trace = fixture(alternate=True)
        self.assertEqual(v.validate(checkpoint, trace), {
            "request_valid": True, "expected_p2wsh_signature_valid": False,
            "alternate_p2wpkh_signature_valid": True, "signing_pass": False})

    def test_high_s_signature_cannot_report_signing_pass(self):
        checkpoint, trace = fixture(high_s=True)
        trace["end"] = "Rejected"
        with tempfile.TemporaryDirectory() as directory:
            checkpoint_path = Path(directory) / "checkpoint.json"
            trace_path = Path(directory) / "trace.json"
            checkpoint_path.write_text(json.dumps(checkpoint))
            trace_path.write_text(json.dumps(trace))
            stdout = io.StringIO()
            with redirect_stdout(stdout):
                status = v.main(["--checkpoint", str(checkpoint_path), "--trace", str(trace_path)])
            self.assertEqual(status, 1)
            self.assertEqual(json.loads(stdout.getvalue()), {
                "request_valid": True, "expected_p2wsh_signature_valid": False,
                "alternate_p2wpkh_signature_valid": False, "signing_pass": False})

    def test_missing_repeated_input_rounds_are_incomplete_evidence(self):
        trace = copy.deepcopy(self.trace)
        trace["frames"] = [frame for i, frame in enumerate(trace["frames"]) if i not in {15, 16, 21, 22}]
        for sequence, frame in enumerate(trace["frames"]):
            frame["sequence"] = sequence
        self.assert_rejected(self.checkpoint, trace)

    def test_provenance_identity_and_scope_mutations(self):
        for field, value in [("schema", 2), ("psbt_sha256", "c" * 64), ("transaction_id", "d" * 64),
                             ("firmware", "2.12.4"), ("fingerprint", "87654321"), ("physical_id", "other"),
                             ("build_source_sha256", "runtime git"), ("executable_sha256", ""),
                             ("build_source_scope", "runtime"), ("incomplete", True), ("end", "Interrupted"),
                             ("handoff", "radio delivered")]:
            with self.subTest(field=field):
                trace = copy.deepcopy(self.trace)
                trace[field] = value
                self.assert_rejected(self.checkpoint, trace)
        for field in ["build_source_sha256", "executable_sha256", "firmware", "fingerprint", "physical_id"]:
            trace = copy.deepcopy(self.trace)
            del trace[field]
            with self.assertRaises(KeyError):
                v.validate(self.checkpoint, trace)
        for path, value in [("family", "bip84"), ("threshold", 2), ("account", 1),
                            ("receive", {"branch": 0, "index": 11}), ("holders", [])]:
            checkpoint = copy.deepcopy(self.checkpoint)
            checkpoint["case"][path] = value
            self.assert_rejected(checkpoint, self.trace)

    def test_sign_tx_mutations(self):
        for field, value in [(1, 1), (2, 2), (3, b"Bitcoin"), (4, 1), (5, 0), (13, 1)]:
            trace = copy.deepcopy(self.trace)
            trace["frames"][0]["payload"] = replace_field(bytes.fromhex(trace["frames"][0]["payload"]), field, value).hex()
            self.assert_rejected(self.checkpoint, trace)

    def test_every_ack_field_and_repeated_round_are_checked(self):
        for frame_index in range(2, len(self.trace["frames"]) - 1, 2):
            payload = bytes.fromhex(self.trace["frames"][frame_index]["payload"])
            ack = v.proto(payload, v.schema(blobs=(1,)))[1]
            previous_meta = frame_index == 4
            wrapper_field = 2 if frame_index in (2, 6, 16, 22) else (3 if frame_index in (8, 10) else 5)
            body = ack if previous_meta else v.proto(ack, v.schema(blobs=(wrapper_field,)))[wrapper_field]
            reader = v.Reader(body)
            fields = []
            while reader.pos < len(body):
                tag = reader.varint()
                number = tag >> 3
                value = reader.varint() if tag & 7 == 0 else reader.take(reader.varint())
                fields.append((number, value))
            for occurrence, (field, value) in enumerate(fields):
                same_before = sum(1 for previous_field, _ in fields[:occurrence] if previous_field == field)
                mutated_value = value ^ 1 if isinstance(value, int) else (value[:-1] + bytes([value[-1] ^ 1]) if value else b"x")
                for replacement in (None, mutated_value):
                    changed = replace_field(body, field, replacement, same_before)
                    changed_ack = changed if previous_meta else pb([(wrapper_field, changed)])
                    trace = copy.deepcopy(self.trace)
                    trace["frames"][frame_index]["payload"] = pb([(1, changed_ack)]).hex()
                    with self.subTest(frame=frame_index, field=field, deletion=replacement is None):
                        self.assert_rejected(self.checkpoint, trace)

    def test_full_node_metadata_and_multisig_shape(self):
        frame = self.trace["frames"][2]
        ack = v.proto(bytes.fromhex(frame["payload"]), v.schema(blobs=(1,)))[1]
        body = v.proto(ack, v.schema(blobs=(2,)))[2]
        ms = v.proto(body, v.schema((3, 5, 6, 8), (2, 7), (1,)))[7]
        node = v.proto(ms, v.schema((3, 6), repeated_blobs=(4,), repeated_integers=(5,)))[4][0]
        parsed_node = v.proto(node, v.schema((1, 2, 3), (4, 6)))
        for field, value in parsed_node.items():
            altered = value ^ 1 if isinstance(value, int) else value[:-1] + bytes([value[-1] ^ 1])
            for replacement in (None, altered):
                changed_node = replace_field(node, field, replacement)
                changed_ms = replace_field(ms, 4, changed_node)
                changed_body = replace_field(body, 7, changed_ms)
                trace = copy.deepcopy(self.trace)
                trace["frames"][2]["payload"] = pb([(1, pb([(2, changed_body)]))]).hex()
                self.assert_rejected(self.checkpoint, trace)
        for field, value in [(3, 2), (4, node + pb([(5, b"secret")])), (5, 1), (6, 0), (1, b"")]:
            changed = replace_field(ms, field, value) if field != 1 else ms + pb([(1, value)])
            trace = copy.deepcopy(self.trace)
            trace["frames"][2]["payload"] = pb([(1, pb([(2, replace_field(body, 7, changed))]))]).hex()
            self.assert_rejected(self.checkpoint, trace)
        trace = copy.deepcopy(self.trace)
        trace["frames"][2]["payload"] = pb([(1, pb([(2, replace_field(body, 7, ms + pb([(4, node)])))]))]).hex()
        self.assert_rejected(self.checkpoint, trace)

    def test_request_correlation_order_and_completeness(self):
        mutations = []
        for index in [0, 1, 2, 4, -1]:
            frames = copy.deepcopy(self.trace["frames"])
            del frames[index]
            mutations.append(frames)
        frames = copy.deepcopy(self.trace["frames"])
        frames[1], frames[2] = frames[2], frames[1]
        mutations.append(frames)
        frames = copy.deepcopy(self.trace["frames"])
        frames += frames[-1:]
        mutations.append(frames)
        for frames in mutations:
            trace = copy.deepcopy(self.trace)
            for sequence, frame in enumerate(frames):
                frame["sequence"] = sequence
            trace["frames"] = frames
            self.assert_rejected(self.checkpoint, trace)
        for field, value in [("sequence", 99), ("outbound", True), ("session", 0), ("message_type", 1001), ("payload", "zz")]:
            trace = copy.deepcopy(self.trace)
            trace["frames"][1][field] = value
            self.assert_rejected(self.checkpoint, trace)
        for payload in [pb([(1, 9)]), pb([(1, 0)]), pb([(1, 0), (2, pb([(1, 1)]))]),
                        pb([(1, 0), (2, pb([(1, 0), (2, b"x" * 32)]))])]:
            trace = copy.deepcopy(self.trace)
            trace["frames"][1]["payload"] = payload.hex()
            self.assert_rejected(self.checkpoint, trace)

    def test_signature_validation_does_not_erase_request_proof(self):
        final = bytes.fromhex(self.trace["frames"][-1]["payload"])
        serialized = v.proto(final, v.schema((1,), (3,)))[3]
        for signature in [b"", b"\x30\x01\0", b"invalid", b"\x30\x06\x02\x01\x01\x02\x01\x01"]:
            trace = copy.deepcopy(self.trace)
            trace["frames"][-1]["payload"] = replace_field(final, 3, replace_field(serialized, 2, signature)).hex()
            result = v.validate(self.checkpoint, trace)
            self.assertTrue(result["request_valid"])
            self.assertFalse(result["signing_pass"])
        for changed in [replace_field(serialized, 1, 1), replace_field(serialized, 1, None),
                        replace_field(serialized, 2, None)]:
            trace = copy.deepcopy(self.trace)
            trace["frames"][-1]["payload"] = replace_field(final, 3, changed).hex()
            self.assert_rejected(self.checkpoint, trace)
        trace = copy.deepcopy(self.trace)
        trace["frames"][1]["payload"] += pb([(3, serialized)]).hex()
        self.assert_rejected(self.checkpoint, trace)
        trace = copy.deepcopy(self.trace)
        trace["frames"][-1]["payload"] += pb([(2, pb([(1, 0)]))]).hex()
        self.assertTrue(v.validate(self.checkpoint, trace)["signing_pass"])
        trace["frames"][-1]["payload"] = replace_field(bytes.fromhex(trace["frames"][-1]["payload"]), 2, pb([(1, 1)])).hex()
        self.assert_rejected(self.checkpoint, trace)

    def test_bounds_are_enforced_before_decoding(self):
        for count, payload_size in [(4097, 0), (2, 1024 * 1024 + 1), (5, 1024 * 1024)]:
            trace = copy.deepcopy(self.trace)
            trace["frames"] = [{"sequence": i, "outbound": True, "session": 1, "message_type": 15,
                                "payload": "00" * payload_size} for i in range(count)]
            self.assert_rejected(self.checkpoint, trace)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "oversized.json"
            path.write_bytes(b" " * (v.LIMIT + 1))
            with self.assertRaises(v.EvidenceError):
                v.load_json(path)

    def test_raw_parsers_reject_ambiguity_and_truncation(self):
        for payload in [b"\x08\x80", b"\x08\x80\0", b"\0", b"\x0b", b"\x0a\x02x",
                        b"\x08\x01\x08\x01", b"\x10\x01", b"\x08" + b"\xff" * 10]:
            with self.assertRaises(v.EvidenceError):
                v.proto(payload, v.schema((1,)))
        raw = base64.b64decode(self.checkpoint["progress"]["Signing"]["signed_psbt"])
        for bad in [raw[:-1], raw + b"x", b"psbt\xff\x01\0\x01x\x01\0\x01x\0", raw.replace(b"psbt\xff", b"psbt\0", 1)]:
            with self.assertRaises(v.EvidenceError):
                v.psbt(base64.b64encode(bad).decode())
        with self.assertRaises(v.EvidenceError):
            v.unique_object([("x", 1), ("x", 2)])
        self.assertEqual(v.proto(pb([(1, b"\x01\x02")]), v.schema(repeated_integers=(1,))), {1: [1, 2]})

    def test_checkpoint_psbt_binding_and_funding(self):
        for field, value in [("amount_sats", 35001), ("outpoint", "00" * 32 + ":0"), ("transaction_hex", "00")]:
            checkpoint = copy.deepcopy(self.checkpoint)
            checkpoint["progress"]["Signing"]["funding"][field] = value
            self.assert_rejected(checkpoint, self.trace)
        for field in ["destination", "receive_address", "change_address"]:
            checkpoint = copy.deepcopy(self.checkpoint)
            checkpoint[field] = address(b"\0\x14" + b"\x44" * 20)
            self.assert_rejected(checkpoint, self.trace)
        raw, global_map, tx, inputs, outputs = v.psbt(self.checkpoint["progress"]["Signing"]["signed_psbt"])
        for changed_inputs in [{**inputs[0], b"\x05": b"\x51"}, {**inputs[0], b"\x03": struct.pack("<I", 2)}]:
            checkpoint = copy.deepcopy(self.checkpoint)
            checkpoint["progress"]["Signing"]["signed_psbt"] = base64.b64encode(psbt_bytes(global_map, [changed_inputs], outputs)).decode()
            self.assert_rejected(checkpoint, self.trace)

    def test_cli_status_and_sanitized_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            checkpoint_path = Path(directory) / "checkpoint.json"
            trace_path = Path(directory) / "trace.json"
            for alternate, status in [(False, 0), (True, 1)]:
                checkpoint, trace = fixture(alternate)
                checkpoint_path.write_text(json.dumps(checkpoint))
                trace_path.write_text(json.dumps(trace))
                stdout = io.StringIO()
                with redirect_stdout(stdout):
                    self.assertEqual(v.main(["--checkpoint", str(checkpoint_path), "--trace", str(trace_path)]), status)
                result = json.loads(stdout.getvalue())
                self.assertTrue(result["request_valid"])
                self.assertEqual(result["signing_pass"], not alternate)
            trace_path.write_text('{"private-secret":"PAYLOAD", "private-secret":null}')
            stdout = io.StringIO()
            with redirect_stdout(stdout):
                self.assertEqual(v.main(["--checkpoint", str(checkpoint_path), "--trace", str(trace_path)]), 1)
            self.assertNotIn("PAYLOAD", stdout.getvalue())
            self.assertNotIn("private-secret", stdout.getvalue())
            self.assertFalse(json.loads(stdout.getvalue())["request_valid"])


if __name__ == "__main__":
    unittest.main()
