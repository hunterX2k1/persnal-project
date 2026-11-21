"""Track B tests: offline analysis of a synthetic flat memory dump."""
import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

from memexplorer.dumpfile import DumpFile
from memexplorer.search import build_pattern, search_process
from memexplorer import volatility

ROOT = Path(__file__).resolve().parents[1]
MARKER = b"THESIS_MARKER_7f3a9c"


@pytest.fixture
def dump(tmp_path):
    """Build a fake physical image: zeros, text, a marker, high-entropy key material,
    and recognisable artefacts — one of each thing the analyser should find."""
    p = tmp_path / "mem.raw"
    blob = bytearray()
    blob += b"\x00" * (2 << 20)                                   # zero page block
    blob += (b"plain readable text sentence. " * 40000)          # text block (~1.1 MiB)
    blob += b"PAD" + MARKER + b"PAD"                              # planted marker (ascii)
    blob += MARKER.decode().encode("utf-16le")                   # planted marker (utf16)
    blob += b"visit https://test.lan/p and mail root@test.lan "  # artefacts
    blob += b'{"token":"x"} '
    blob += os.urandom(2 << 20)                                  # high-entropy block
    p.write_bytes(bytes(blob))
    return p


def test_read_and_regions(dump):
    with DumpFile(str(dump)) as d:
        assert d.size == os.path.getsize(dump)
        assert d.read(2 << 20, 5) == b"plain"
        regs = list(d.regions())
        assert len(regs) == 1 and regs[0].size == d.size


def test_memory_map_labels(dump):
    with DumpFile(str(dump)) as d:
        totals = d.label_totals(1 << 20)
    assert "zeros" in totals
    assert "text" in totals
    assert any(l.startswith("high-entropy") for l in totals)


def test_marker_search_by_offset(dump):
    with DumpFile(str(dump)) as d:
        pat = build_pattern("THESIS_MARKER_7f3a9c", utf16=True)
        hits = list(search_process(d, pat, list(d.regions())))
    assert len(hits) >= 2  # ascii + utf16


def test_carve_artefacts(dump):
    with DumpFile(str(dump)) as d:
        kinds = {kind for _, kind, _ in d.carve_artefacts()}
    assert {"url", "email", "json"} <= kinds


def test_carve_strings_no_duplicates_across_blocks(dump):
    with DumpFile(str(dump)) as d:
        offsets = [off for off, _, _ in d.carve_strings(min_len=8, block=64 * 1024)]
    assert offsets == sorted(offsets)
    assert len(offsets) == len(set(offsets))


def test_volatility_absence_is_reported(monkeypatch):
    monkeypatch.setattr(volatility.shutil, "which", lambda _: None)
    monkeypatch.setitem(sys.modules, "volatility3", None)
    with pytest.raises(volatility.VolatilityNotFound) as e:
        volatility.find_volatility()
    assert "pip install volatility3" in str(e.value)


def test_cli_analyse_dump_end_to_end(dump, tmp_path):
    out = tmp_path / "report.json"
    r = subprocess.run(
        [sys.executable, "-m", "memexplorer", "analyse-dump", str(dump),
         "--marker", "THESIS_MARKER_7f3a9c", "--json", str(out)],
        capture_output=True, text=True, cwd=ROOT)
    assert r.returncode == 0, r.stderr
    assert "marker hit" in r.stdout
    report = json.loads(out.read_text())
    assert report["artefacts"].get("url", 0) >= 1
    assert "zeros" in report["labels"]
