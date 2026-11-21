"""Fetch one of YOUR OWN LAN test sites and keep the response in RAM (plan section 11).

Shows the TLS boundary: the wire (Wireshark) carries ciphertext for https://,
but this process holds the decrypted body -- find it with memexplorer.

    python demo/web_fetch_target.py http://192.168.1.50:8000/
    python demo/web_fetch_target.py https://test.lan/ --insecure   # self-signed test cert
"""
from __future__ import annotations

import argparse
import os
import ssl
import urllib.request


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("url")
    ap.add_argument("--insecure", action="store_true", help="accept a self-signed cert on your test site")
    ap.add_argument("--find", help="word from the page to suggest searching for")
    args = ap.parse_args()

    ctx = ssl._create_unverified_context() if args.insecure else None
    with urllib.request.urlopen(args.url, context=ctx, timeout=15) as r:
        headers = str(r.headers)
        body = r.read()

    print(f"PID {os.getpid()}  fetched {len(body)} bytes from {args.url}")
    print(headers)
    print(body[:300].decode("utf-8", "replace"), "...")
    word = args.find or "<title>"
    print(f"\n  python -m memexplorer search {os.getpid()} \"{word}\" --writable --context 128")
    print(f"  python -m memexplorer scan {os.getpid()} --writable")
    input("\nResponse is held in RAM. Press Enter to exit... ")
    del body


if __name__ == "__main__":
    main()
