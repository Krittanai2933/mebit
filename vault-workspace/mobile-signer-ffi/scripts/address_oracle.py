#!/usr/bin/env python3
"""Re-derives the golden multisig addresses in rust/src/lib.rs without vault-core, miniscript or rust-bitcoin.

Usage: python3 scripts/address_oracle.py        exit 0 when every address matches, 1 otherwise
"""
import hashlib
import hmac
import pathlib
import re
import sys

P = 2**256 - 2**32 - 977
N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
G = (0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798,
     0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8)
B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
BECH32 = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"


def b58check_decode(text):
    n = 0
    for c in text:
        n = n * 58 + B58.index(c)
    raw = n.to_bytes(82, "big")  # 78 payload bytes + 4 checksum bytes
    payload, checksum = raw[:-4], raw[-4:]
    assert hashlib.sha256(hashlib.sha256(payload).digest()).digest()[:4] == checksum, "bad base58 checksum"
    return payload


def point_add(a, b):
    if a is None:
        return b
    if b is None:
        return a
    if a[0] == b[0] and (a[1] + b[1]) % P == 0:
        return None
    if a == b:
        slope = 3 * a[0] * a[0] * pow(2 * a[1], -1, P) % P
    else:
        slope = (b[1] - a[1]) * pow(b[0] - a[0], -1, P) % P
    x = (slope * slope - a[0] - b[0]) % P
    return (x, (slope * (a[0] - x) - a[1]) % P)


def point_mul(k, point):
    result = None
    while k:
        if k & 1:
            result = point_add(result, point)
        point = point_add(point, point)
        k >>= 1
    return result


def decompress(key33):
    x = int.from_bytes(key33[1:], "big")
    y = pow((x**3 + 7) % P, (P + 1) // 4, P)
    return (x, y if y % 2 == key33[0] % 2 else P - y)


def compress(point):
    return bytes([2 + (point[1] & 1)]) + point[0].to_bytes(32, "big")


def derive_public(xpub, path):
    payload = b58check_decode(xpub)
    chain, key = payload[13:45], payload[45:78]
    for index in path:
        assert index < 2**31, "hardened child cannot be derived from a public key"
        digest = hmac.new(chain, key + index.to_bytes(4, "big"), hashlib.sha512).digest()
        tweak = int.from_bytes(digest[:32], "big")
        assert tweak < N
        key = compress(point_add(point_mul(tweak, G), decompress(key)))
        chain = digest[32:]
    return key


def bech32_polymod(values):
    generator = [0x3B6A57B2, 0x26508E6D, 0x1EA119FA, 0x3D4233DD, 0x2A1462B3]
    chk = 1
    for value in values:
        top = chk >> 25
        chk = (chk & 0x1FFFFFF) << 5 ^ value
        for i in range(5):
            chk ^= generator[i] if (top >> i) & 1 else 0
    return chk


def segwit_v0_address(hrp, program):
    data, acc, bits = [0], 0, 0
    for byte in program:
        acc = (acc << 8) | byte
        bits += 8
        while bits >= 5:
            bits -= 5
            data.append((acc >> bits) & 31)
    if bits:
        data.append((acc << (5 - bits)) & 31)
    expanded = [ord(c) >> 5 for c in hrp] + [0] + [ord(c) & 31 for c in hrp]
    polymod = bech32_polymod(expanded + data + [0] * 6) ^ 1  # witness v0 uses bech32, not bech32m
    checksum = [(polymod >> 5 * (5 - i)) & 31 for i in range(6)]
    return hrp + "1" + "".join(BECH32[d] for d in data + checksum)


def p2wsh_sortedmulti(xpubs, threshold, index, hrp):
    assert 1 <= threshold <= len(xpubs) <= 16, "0x50 + n encodes OP_1..OP_16 only"
    keys = sorted(derive_public(x, [0, index]) for x in xpubs)  # receive chain /0/*, BIP-67 lexicographic order
    script = bytes([0x50 + threshold]) + b"".join(b"\x21" + k for k in keys) + bytes([0x50 + len(keys), 0xAE])
    return segwit_v0_address(hrp, hashlib.sha256(script).digest())


def rust_constants(source):
    def array(name):
        body = re.search(r"const %s: \[&str; 3\] = \[(.*?)\];" % name, source, re.S).group(1)
        return re.findall(r'"([^"]+)"', body)

    def string(name):
        return re.search(r'const %s: &str = "([^"]+)";' % name, source).group(1)

    return array("TPUB"), array("XPUB"), string("TESTNET_2_OF_3"), string("MAINNET_2_OF_3")


def main():
    lib = pathlib.Path(__file__).resolve().parent.parent / "rust" / "src" / "lib.rs"
    tpub, xpub, want_testnet, want_mainnet = rust_constants(lib.read_text())
    cases = [
        ("2-of-3 testnet", p2wsh_sortedmulti(tpub, 2, 0, "tb"), want_testnet),
        ("2-of-3 testnet, keys rotated", p2wsh_sortedmulti(tpub[1:] + tpub[:1], 2, 0, "tb"), want_testnet),
        ("2-of-3 mainnet", p2wsh_sortedmulti(xpub, 2, 0, "bc"), want_mainnet),
    ]
    failed = 0
    for name, got, want in cases:
        ok = got == want
        failed += not ok
        print("MATCH   " if ok else "MISMATCH", name, got if ok else "oracle %s, lib.rs %s" % (got, want))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
