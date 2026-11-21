"""Storage / data-lifecycle demo (section 4 of the plan).

Shows the SAME fake secret in several places so you can photograph the whole
lifecycle for the "RAM vs ROM/persistence" chapter:

  1. In a program variable  -> RAM only, gone at exit (read it live with Track A).
  2. In a plaintext file     -> persists on disk, readable by anyone.
  3. In an encrypted file     -> ciphertext on disk; the key lives elsewhere:
       * Windows: DPAPI (CryptProtectData) — key managed by the OS/LSASS,
         optionally TPM-backed, NOT in this process's normal RAM.
       * other OS: AES-256 with the key written to a SEPARATE sidecar file, to
         illustrate "ciphertext and key stored apart". This is a teaching stand-in;
         only DPAPI/TPM actually keeps the key out of your process on Windows.
  4. pagefile.sys / hiberfil.sys -> RAM spilled to DISK by the OS. With BitLocker
     OFF these can be read offline and still hold secrets; with BitLocker ON they
     sit inside the encrypted volume. This script locates them and reports the
     BitLocker / swap state — it does NOT read them (do that offline, per the plan).

All data is synthetic. Run, then read this process's RAM and hexdump the files:

    python demo/storage_target.py --outdir out
    python -m memexplorer search <PID> THESIS_STORAGE_MARKER --writable
"""
from __future__ import annotations

import argparse
import ctypes
import os
import subprocess
import sys
from pathlib import Path

MARKER = "THESIS_STORAGE_MARKER_a81f"
SECRET = (f"{MARKER}: account=demo-user; password=Fake-Passw0rd!; "
          "note=synthetic test data for the storage chapter").encode()


# ---------------------------------------------------------------- disk layers
def write_plaintext(path: Path, secret: bytes) -> Path:
    path.write_bytes(secret)
    return path


def write_encrypted(path: Path, secret: bytes) -> dict:
    """Return {method, file, key_location, key_in_process}."""
    if sys.platform == "win32":
        blob = _dpapi_protect(bytearray(secret))
        path.write_bytes(blob)
        return {"method": "DPAPI (CryptProtectData)", "file": str(path),
                "key_location": "Windows DPAPI master key (user profile / LSASS, optionally TPM)",
                "key_in_process": False}
    # Portable teaching stand-in: AES-256 via Fernet, key in a separate sidecar.
    from cryptography.fernet import Fernet
    key = Fernet.generate_key()
    path.write_bytes(Fernet(key).encrypt(secret))
    key_path = path.with_suffix(path.suffix + ".key")
    key_path.write_bytes(key)
    return {"method": "AES-256 (Fernet) — teaching stand-in, not DPAPI",
            "file": str(path),
            "key_location": f"separate sidecar file {key_path} (on Windows, DPAPI/TPM holds this instead)",
            "key_in_process": True}


def _dpapi_protect(data: bytearray) -> bytes:  # pragma: no cover - Windows only
    from ctypes import wintypes

    class DATA_BLOB(ctypes.Structure):
        _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]

    crypt32 = ctypes.WinDLL("crypt32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.LocalFree.argtypes = [ctypes.c_void_p]
    arr = (ctypes.c_char * len(data)).from_buffer(data)
    src = DATA_BLOB(len(data), ctypes.cast(arr, ctypes.POINTER(ctypes.c_char)))
    out = DATA_BLOB()
    if not crypt32.CryptProtectData(ctypes.byref(src), "thesis-storage", None, None, None, 0,
                                    ctypes.byref(out)):
        raise OSError(ctypes.get_last_error())
    try:
        return ctypes.string_at(out.pbData, out.cbData)
    finally:
        kernel32.LocalFree(out.pbData)


# ---------------------------------------------------------------- swap / hibernation
def find_swap_files() -> list[dict]:
    """Locate RAM-spill files and whether their volume is encrypted. Read-only."""
    out = []
    if sys.platform == "win32":  # pragma: no cover - Windows only
        for name in ("pagefile.sys", "hiberfil.sys", "swapfile.sys"):
            for drive in (f"{c}:\\" for c in "CDEF"):
                p = Path(drive) / name
                try:
                    if p.exists():
                        out.append({"file": str(p), "bytes": p.stat().st_size,
                                    "volume_encrypted": _bitlocker_on(drive)})
                except OSError:
                    pass
    else:
        try:
            swaps = Path("/proc/swaps").read_text().splitlines()[1:]
            for line in swaps:
                f = line.split()
                if f:
                    out.append({"file": f[0], "bytes": int(f[2]) * 1024 if len(f) > 2 else 0,
                                "volume_encrypted": "unknown (check LUKS/dm-crypt)"})
        except OSError:
            pass
    return out


def _bitlocker_on(drive: str) -> str:  # pragma: no cover - Windows only
    try:
        r = subprocess.run(["manage-bde", "-status", drive], capture_output=True, text=True, timeout=30)
        txt = r.stdout.lower()
        if "protection on" in txt:
            return "yes (BitLocker protection on)"
        if "protection off" in txt or "fully decrypted" in txt:
            return "NO — readable offline; secrets in swap/hibernation are exposed"
        return "unknown"
    except (OSError, subprocess.SubprocessError):
        return "unknown (run `manage-bde -status` as admin)"


# ---------------------------------------------------------------- report
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--outdir", default="out", help="where to write the demo files")
    ap.add_argument("--no-wait", action="store_true", help="exit immediately (don't hold RAM)")
    args = ap.parse_args()

    outdir = Path(args.outdir)
    outdir.mkdir(parents=True, exist_ok=True)

    secret_in_ram = bytearray(SECRET)  # layer 1: RAM
    buf = (ctypes.c_char * len(secret_in_ram)).from_buffer(secret_in_ram)
    ram_addr = ctypes.addressof(buf)

    plain = write_plaintext(outdir / "secret_plaintext.txt", SECRET)  # layer 2
    enc = write_encrypted(outdir / "secret_encrypted.bin", SECRET)    # layer 3
    swaps = find_swap_files()                                          # layer 4

    pid = os.getpid()
    print(f"PID {pid}\n")
    print("Data-lifecycle of one secret (all layers hold the SAME fake secret):\n")
    rows = [
        ("1 RAM (variable)", f"addr {ram_addr:#x}", "no (gone at exit)", "no", "this process"),
        ("2 plaintext file", plain.name, "YES", "no", "anyone with file access"),
        ("3 encrypted file", Path(enc["file"]).name, "YES", "yes", "holder of the key"),
    ]
    print(f"  {'layer':<20} {'where':<26} {'persists':<17} {'encrypted':<10} readable by")
    for layer, where, persists, encd, who in rows:
        print(f"  {layer:<20} {where:<26} {persists:<17} {encd:<10} {who}")
    print(f"\n  Encryption: {enc['method']}")
    print(f"  Key location: {enc['key_location']}")
    print(f"  Key in this process's RAM? {'YES' if enc['key_in_process'] else 'NO'}")

    print("\n  On-disk contrast (first 48 bytes):")
    print(f"    plaintext : {plain.read_bytes()[:48]!r}")
    print(f"    encrypted : {Path(enc['file']).read_bytes()[:48].hex(' ')}")

    print("\n4 RAM-spilled-to-disk (read these OFFLINE, with BitLocker off vs on):")
    if swaps:
        for s in swaps:
            print(f"    {s['file']}  {s['bytes'] / 2**30:.2f} GiB  volume encrypted: {s['volume_encrypted']}")
    else:
        print("    none found (no pagefile/hiberfil/swap visible, or needs admin)")

    print(f"\nFind the live copy:  python -m memexplorer search {pid} {MARKER} --writable --utf16")
    print(f"Contrast on disk:    the plaintext file shows the marker; the encrypted file does not.")

    if not args.no_wait:
        input("\nSecret is in RAM and on disk. Press Enter to exit... ")
    ctypes.memset(buf, 0, len(secret_in_ram))


if __name__ == "__main__":
    main()
