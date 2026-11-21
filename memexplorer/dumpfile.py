"""Offline analysis of a flat physical memory dump (.raw / .mem / .dmp).

Track B of the plan: after capturing all physical RAM with WinPMEM / DumpIt /
Magnet RAM Capture, this runs the *same* classifier, string extractor, artefact
detector and marker search used on live processes (Track A) directly over the
dump file. It reuses `classify.py` and `search.py` rather than duplicating them.

A flat dump has no region metadata, so we walk it in fixed-size blocks and build
a "memory map": per-block entropy + content label. That gives the categorised
picture the thesis wants (how much of physical RAM is code / text / zeros /
high-entropy) and locates planted markers by file offset.

This is pure Python on a file — no admin, no OS, works on any platform, and on a
dump you captured from any machine. For process lists / command lines / DLLs,
use the Volatility 3 driver (`memexplorer/volatility.py`); those need the
structured plugins, which this does not reimplement.
"""
from __future__ import annotations

import os
from collections import Counter
from dataclasses import dataclass

from .classify import classify_bytes, entropy, find_artefacts
from .search import extract_strings

DEFAULT_BLOCK = 1 << 20  # 1 MiB map granularity


@dataclass
class Block:
    offset: int
    size: int
    entropy: float
    label: str


class DumpFile:
    """A flat memory image exposed with the same read() surface as a live process,
    so `search.search_process` and friends work on it unchanged."""

    def __init__(self, path: str):
        self.path = path
        self.size = os.path.getsize(path)
        self._f = open(path, "rb", buffering=0)

    def close(self) -> None:
        self._f.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    # --- live-process-compatible surface -------------------------------------
    def read(self, addr: int, size: int) -> bytes:
        self._f.seek(addr)
        return self._f.read(size)

    def regions(self, committed_only: bool = True):
        """One synthetic region spanning the whole image, so Track-A search code
        (which iterates regions) can run over the dump directly."""
        from .regions import Region
        yield Region(0, self.size, "RW-", "private", self.path, True)

    # --- dump-specific analysis ---------------------------------------------
    def iter_blocks(self, block: int = DEFAULT_BLOCK):
        self._f.seek(0)
        off = 0
        while True:
            data = self._f.read(block)
            if not data:
                break
            yield off, data
            off += len(data)

    def memory_map(self, block: int = DEFAULT_BLOCK) -> list[Block]:
        out = []
        for off, data in self.iter_blocks(block):
            out.append(Block(off, len(data), entropy(data), classify_bytes(data).label))
        return out

    def label_totals(self, block: int = DEFAULT_BLOCK) -> Counter:
        totals: Counter = Counter()
        for b in self.memory_map(block):
            totals[b.label] += b.size
        return totals

    def carve_artefacts(self, kinds=None, block: int = 8 << 20, overlap: int = 4096,
                        max_per_kind: int = 500):
        """Scan the whole image for URLs / e-mails / JWTs / cookies / JSON / PEM /
        hex & base64 blobs. Yields (file_offset, kind, text)."""
        counts: Counter = Counter()
        self._f.seek(0)
        off = 0
        tail = b""
        while True:
            chunk = self._f.read(block)
            if not chunk:
                break
            data = tail + chunk
            base = off - len(tail)
            for h in find_artefacts(data, kinds=kinds, max_per_kind=max_per_kind):
                if counts[h.kind] >= max_per_kind:
                    continue
                counts[h.kind] += 1
                yield base + h.offset, h.kind, h.text
            off += len(chunk)
            tail = data[-overlap:]

    def carve_strings(self, min_len: int = 8, block: int = 8 << 20, overlap: int = 256):
        self._f.seek(0)
        off = 0
        tail = b""
        last_abs = -1
        while True:
            chunk = self._f.read(block)
            if not chunk:
                break
            data = tail + chunk
            base = off - len(tail)
            for soff, enc, text in extract_strings(data, min_len):
                abs_off = base + soff
                if abs_off <= last_abs:
                    continue  # already emitted from the previous chunk's tail
                last_abs = abs_off
                yield abs_off, enc, text
            off += len(chunk)
            tail = data[-overlap:]
