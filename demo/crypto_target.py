"""Crypto demo target (Exercise 1 + section 5 of the plan).

Holds FAKE data in RAM: a marker string, a plaintext, an AES-256 key, IV and the
ciphertext. Prints its PID and where each buffer lives, then waits so you can
read it with memexplorer. Press Enter to wipe key + plaintext (the
SecureZeroMemory equivalent), then read again and see zeros.

Thesis caveat worth writing up: Python makes hidden copies (immutable `bytes`
temporaries, the crypto library's own buffers). Wiping *our* bytearrays does
not guarantee every copy is gone -- run `search` for the key after the wipe
and count the leftovers. In C/C++ with SecureZeroMemory you control this; in
managed languages you largely don't.

    python demo/crypto_target.py            # AES-256-CBC
    python demo/crypto_target.py --gcm      # AES-256-GCM
"""
from __future__ import annotations

import argparse
import ctypes
import os

from cryptography.hazmat.primitives import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MARKER = "THESIS_MARKER_7f3a9c"
PLAINTEXT = (f"{MARKER}: account=demo-user; password=Fake-Passw0rd!; "
             "note=this is synthetic test data for the memory thesis").encode()


def addr_of(buf: bytearray) -> int:
    return ctypes.addressof((ctypes.c_char * len(buf)).from_buffer(buf))


def wipe(buf: bytearray) -> None:
    ctypes.memset(addr_of(buf), 0, len(buf))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gcm", action="store_true")
    args = ap.parse_args()

    key = bytearray(os.urandom(32))
    iv = bytearray(os.urandom(12 if args.gcm else 16))
    plaintext = bytearray(PLAINTEXT)
    wide_marker = bytearray((MARKER + "_UTF16").encode("utf-16le"))  # like a Windows wchar_t string

    if args.gcm:
        ciphertext = bytearray(AESGCM(bytes(key)).encrypt(bytes(iv), bytes(plaintext), None))
    else:
        padder = padding.PKCS7(128).padder()
        padded = padder.update(bytes(plaintext)) + padder.finalize()
        enc = Cipher(algorithms.AES(bytes(key)), modes.CBC(bytes(iv))).encryptor()
        ciphertext = bytearray(enc.update(padded) + enc.finalize())

    print(f"PID {os.getpid()}  mode={'AES-256-GCM' if args.gcm else 'AES-256-CBC'}")
    for name, buf in [("plaintext", plaintext), ("key", key), ("iv", iv),
                      ("ciphertext", ciphertext), ("utf16 marker", wide_marker)]:
        print(f"  {name:<13} @ {addr_of(buf):#018x}  len={len(buf):<4} {bytes(buf[:32]).hex()}")
    print("\nTry:")
    print(f"  python -m memexplorer search {os.getpid()} {MARKER} --utf16 --writable")
    print(f"  python -m memexplorer search {os.getpid()} {bytes(key).hex()} --hex")
    print(f"  python -m memexplorer keys {os.getpid()} --address {addr_of(key):#x} --radius 256")

    input("\n[stage 1] everything is in RAM. Press Enter to WIPE key + plaintext... ")
    wipe(key)
    wipe(plaintext)
    print("  key now:      ", bytes(key).hex())
    print("  plaintext now:", bytes(plaintext[:32]).hex(), "...")
    print("Search again for the key hex / marker and count remaining copies (Python's hidden copies).")
    input("\n[stage 2] wiped. Press Enter to exit... ")
    del ciphertext, iv, wide_marker


if __name__ == "__main__":
    main()
