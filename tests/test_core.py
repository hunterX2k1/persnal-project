import os
import re
import subprocess
import sys
import time
from pathlib import Path

import pytest

from memexplorer import ai_explain
from memexplorer.classify import classify_bytes, entropy, find_artefacts, key_candidates
from memexplorer.processes import browser_role
from memexplorer.regions import Region
from memexplorer.search import build_pattern, extract_strings, hexdump

ROOT = Path(__file__).resolve().parents[1]


def test_entropy_bounds():
    assert entropy(b"\x00" * 100) == 0
    assert abs(entropy(bytes(range(256))) - 8.0) < 1e-9


def test_classify_labels():
    assert classify_bytes(b"\x00" * 4096).label == "zeros"
    assert classify_bytes(b"hello world, plain text " * 100).label == "text"
    assert classify_bytes("wide string text ".encode("utf-16le") * 50).label == "utf16-text"
    assert classify_bytes(os.urandom(4096)).label.startswith("high-entropy")
    assert classify_bytes(os.urandom(64), executable=True).label == "code"


def test_key_candidates_finds_embedded_key():
    key = os.urandom(32)
    buf = b"A" * 200 + key + b"B" * 200
    hits = key_candidates(buf)
    assert hits and any(off <= 200 < off + ln for off, ln, _ in hits)
    assert key_candidates(b"some ordinary text " * 50) == []


def test_artefacts():
    data = (b'xx https://test.lan/page?q=1 yy user@example.com '
            b'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.abcdefghijk {"name": 1}')
    kinds = {h.kind for h in find_artefacts(data)}
    assert {"url", "email", "jwt", "json"} <= kinds


def test_patterns_and_strings():
    data = b"..MARKER..." + "MARKER".encode("utf-16le")
    assert len(build_pattern("MARKER", utf16=True).findall(data)) == 2
    assert build_pattern("4d 41", "hex").search(data)
    assert {e for _, e, _ in extract_strings(data, 6)} == {"ascii", "utf16"}
    assert hexdump(b"AB", 0x10).startswith("0000000000000010  41 42")


def test_browser_role():
    assert browser_role("chrome.exe", ["chrome.exe", "--type=renderer", "--foo"]) == "renderer"
    assert browser_role("chrome.exe", ["chrome.exe"]) == "browser (main)"
    assert browser_role("firefox.exe", ["firefox.exe", "-contentproc", "--x", "7", "tab"]) == "tab (web content)"
    assert browser_role("notepad.exe", []) == ""


def test_payload_redacts():
    r = Region(0x1000, 0x1000, "RW-", "private")
    p = ai_explain.build_payload(1, r, 0x1000, b"mail me: bob@example.com " + b"a1" * 20)
    assert "bob@example.com" not in p["ascii_preview"]
    assert p["protection"] == "RW-" and p["redacted"]
    with pytest.raises(PermissionError):
        ai_explain.explain_claude(p, confirmed_test_data=False)
    assert ai_explain.parse_label("blah\nLABEL: `crypto-material`") == "crypto-material"


@pytest.mark.skipif(not sys.platform.startswith(("linux", "win")), reason="needs a backend")
def test_live_read_of_demo_target():
    """End-to-end: start the crypto demo, find marker + key in its memory, wipe, re-check."""
    proc = subprocess.Popen([sys.executable, str(ROOT / "demo" / "crypto_target.py")],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    try:
        lines = []
        while True:
            line = proc.stdout.readline()
            lines.append(line)
            if "key" in line and "@" in line:
                break
        out = "".join(lines)
        pid = int(re.search(r"PID (\d+)", out).group(1))
        key_addr = int(re.search(r"key\s+@ (0x[0-9a-f]+)", out).group(1), 16)

        from memexplorer.backends import open_process
        from memexplorer.search import search_process
        with open_process(pid) as mem:
            regions = [r for r in mem.regions() if r.writable]
            hits = list(search_process(mem, build_pattern("THESIS_MARKER_7f3a9c", utf16=True), regions))
            assert len(hits) >= 2  # ascii plaintext + utf16 copy at least
            key = mem.read(key_addr, 32)
            assert any(off == 0 or off <= 0 < off + ln for off, ln, _ in key_candidates(key, step=4))

            # stage 1 -> wipe
            for _ in range(8):  # drain remaining prompt lines
                if "Press Enter" in proc.stdout.readline():
                    break
            proc.stdin.write("\n")
            proc.stdin.flush()
            time.sleep(0.5)
            assert mem.read(key_addr, 32) == b"\x00" * 32
    finally:
        proc.kill()
        proc.wait()
