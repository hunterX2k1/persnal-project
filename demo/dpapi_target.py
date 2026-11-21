"""DPAPI contrast target (Windows only, plan section 4/5).

Encrypts FAKE secret data with CryptProtectData. The master key that protects
it is managed by Windows (LSASS / user profile, optionally TPM-backed), never
held in this process. After protecting, the plaintext is wiped; this process's
RAM then contains only the opaque DPAPI blob -- compare with crypto_target.py,
where the AES key sits right next to the ciphertext.

    python demo/dpapi_target.py
    python demo/dpapi_target.py --save secret.dpapi   # also write the blob to disk
"""
from __future__ import annotations

import argparse
import ctypes
import os
import sys
from ctypes import wintypes

if sys.platform != "win32":
    sys.exit("DPAPI demo runs on Windows only")

MARKER = "THESIS_DPAPI_MARKER_51e2"
SECRET = f"{MARKER}: api_token=FAKE-0000-1111-2222; synthetic test data".encode()


class DATA_BLOB(ctypes.Structure):
    _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]


crypt32 = ctypes.WinDLL("crypt32", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
kernel32.LocalFree.argtypes = [ctypes.c_void_p]


def _blob(buf: bytearray) -> DATA_BLOB:
    arr = (ctypes.c_char * len(buf)).from_buffer(buf)
    return DATA_BLOB(len(buf), ctypes.cast(arr, ctypes.POINTER(ctypes.c_char)))


def protect(data: bytearray) -> bytes:
    out = DATA_BLOB()
    if not crypt32.CryptProtectData(ctypes.byref(_blob(data)), "thesis-demo", None, None, None, 0,
                                    ctypes.byref(out)):
        raise OSError(ctypes.get_last_error())
    try:
        return ctypes.string_at(out.pbData, out.cbData)
    finally:
        kernel32.LocalFree(out.pbData)


def unprotect(blob: bytes) -> bytearray:
    src = bytearray(blob)
    out = DATA_BLOB()
    if not crypt32.CryptUnprotectData(ctypes.byref(_blob(src)), None, None, None, None, 0,
                                      ctypes.byref(out)):
        raise OSError(ctypes.get_last_error())
    try:
        res = bytearray(ctypes.string_at(out.pbData, out.cbData))
        ctypes.memset(out.pbData, 0, out.cbData)
        return res
    finally:
        kernel32.LocalFree(out.pbData)


def wipe(buf: bytearray) -> None:
    ctypes.memset((ctypes.c_char * len(buf)).from_buffer(buf), 0, len(buf))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--save")
    args = ap.parse_args()

    plaintext = bytearray(SECRET)
    blob = bytearray(protect(plaintext))
    wipe(plaintext)
    if args.save:
        with open(args.save, "wb") as f:
            f.write(blob)

    pid = os.getpid()
    print(f"PID {pid}. DPAPI blob ({len(blob)} bytes) in RAM; plaintext wiped; no key in this process.")
    print(f"  python -m memexplorer search {pid} {MARKER} --writable   # expect 0 (or only stale copies)")
    print(f"  python -m memexplorer search {pid} {bytes(blob[:24]).hex()} --hex   # the blob itself")
    input("\n[stage 1] Press Enter to decrypt (plaintext comes back into RAM)... ")
    pt = unprotect(bytes(blob))
    print("  decrypted:", pt.decode())
    print("  -> search for the marker again: it is now readable in RAM until wiped.")
    input("\n[stage 2] Press Enter to wipe and exit... ")
    wipe(pt)


if __name__ == "__main__":
    main()
