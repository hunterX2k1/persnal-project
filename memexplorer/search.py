"""Hex dump, string extraction and pattern search over a process's regions."""
from __future__ import annotations

import re
from dataclasses import dataclass

CHUNK = 4 * 1024 * 1024  # read big regions in 4 MiB pieces
OVERLAP = 4096           # so matches across chunk borders are not lost


def hexdump(data: bytes, base: int = 0, width: int = 16) -> str:
    lines = []
    for off in range(0, len(data), width):
        row = data[off:off + width]
        hx = " ".join(f"{b:02x}" for b in row)
        asc = "".join(chr(b) if 0x20 <= b < 0x7F else "." for b in row)
        lines.append(f"{base + off:016x}  {hx:<{width * 3}} {asc}")
    return "\n".join(lines)


def extract_strings(data: bytes, min_len: int = 6, utf16: bool = True):
    """Yield (offset, encoding, text) for ASCII and UTF-16LE runs."""
    for m in re.finditer(rb"[\x20-\x7e\t]{%d,}" % min_len, data):
        yield m.start(), "ascii", m.group().decode("ascii")
    if utf16:
        for m in re.finditer(rb"(?:[\x20-\x7e]\x00){%d,}" % min_len, data):
            yield m.start(), "utf16", m.group().decode("utf-16le")


def build_pattern(text: str, mode: str = "text", utf16: bool = False, ignore_case: bool = False):
    """Return a compiled bytes regex from user input.

    mode: "text" (literal), "hex" ("de ad be ef" / "deadbeef"), "regex" (raw bytes regex).
    utf16=True additionally matches the UTF-16LE encoding of a text pattern.
    """
    flags = re.IGNORECASE if ignore_case else 0
    if mode == "hex":
        raw = bytes.fromhex(text.replace(" ", ""))
        return re.compile(re.escape(raw), flags)
    if mode == "regex":
        return re.compile(text.encode("latin-1"), flags)
    alts = [re.escape(text.encode("utf-8"))]
    if utf16:
        alts.append(re.escape(text.encode("utf-16le")))
    return re.compile(b"|".join(alts), flags)


@dataclass
class Match:
    address: int
    length: int
    region_kind: str
    region_protect: str
    region_path: str


def iter_region_chunks(proc, region):
    """Yield (address, bytes) for a region, chunked with overlap; skips unreadable pages."""
    addr = region.base
    while addr < region.end:
        n = min(CHUNK + OVERLAP, region.end - addr)
        try:
            data = proc.read(addr, n)
        except OSError:
            addr += 4096  # skip one page and keep going
            continue
        if not data:
            addr += 4096
            continue
        yield addr, data
        if addr + len(data) >= region.end or len(data) <= OVERLAP:
            addr += len(data)
        else:
            addr += len(data) - OVERLAP


def search_process(proc, pattern, regions, max_hits: int = 100):
    seen = set()
    for r in regions:
        if not r.readable:
            continue
        for addr, data in iter_region_chunks(proc, r):
            for m in pattern.finditer(data):
                a = addr + m.start()
                if a in seen:
                    continue
                seen.add(a)
                yield Match(a, m.end() - m.start(), r.kind, r.protect, r.path)
                if len(seen) >= max_hits:
                    return
