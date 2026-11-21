"""Classify regions and byte buffers.

Two levels, as in the plan:
  * region level  - from the OS: image (code/modules) / mapped / private (heap/stack),
                    plus protection (R-X = code, RW- = data).
  * content level - from the bytes: zeros / text / utf16-text / high-entropy
                    (key, ciphertext or compressed) / code / binary-struct.
Also finds "key-like" windows (short, near-maximal-entropy runs) and labels
recognisable artefacts (URLs, e-mails, JWTs, PEM blocks, JSON, hex/base64 blobs).
"""
from __future__ import annotations

import math
import re
from collections import Counter
from dataclasses import dataclass, field

from .regions import Region

PRINTABLE = set(range(0x20, 0x7F)) | {0x09, 0x0A, 0x0D}


def entropy(data: bytes) -> float:
    """Shannon entropy in bits per byte (0..8)."""
    if not data:
        return 0.0
    n = len(data)
    return -sum(c / n * math.log2(c / n) for c in Counter(data).values())


def printable_ratio(data: bytes) -> float:
    return sum(b in PRINTABLE for b in data) / len(data) if data else 0.0


def zero_ratio(data: bytes) -> float:
    return data.count(0) / len(data) if data else 0.0


def utf16_ratio(data: bytes) -> float:
    """Share of 2-byte units that look like ASCII UTF-16LE (Windows wide strings)."""
    if len(data) < 2:
        return 0.0
    units = len(data) // 2
    hits = sum(1 for i in range(0, units * 2, 2) if data[i + 1] == 0 and data[i] in PRINTABLE)
    return hits / units


@dataclass
class BufferStats:
    size: int
    entropy: float
    printable_ratio: float
    zero_ratio: float
    utf16_ratio: float
    label: str

    def to_dict(self) -> dict:
        return {k: (round(v, 3) if isinstance(v, float) else v) for k, v in self.__dict__.items()}


def classify_bytes(data: bytes, executable: bool = False) -> BufferStats:
    e, p, z, u = entropy(data), printable_ratio(data), zero_ratio(data), utf16_ratio(data)
    if not data:
        label = "empty"
    elif z > 0.95:
        label = "zeros"
    elif executable:
        label = "code"
    elif p > 0.85:
        label = "text"
    elif u > 0.6:
        label = "utf16-text"
    elif len(data) >= 32 and e / min(8.0, math.log2(len(data))) > 0.9:
        # normalised: a buffer of n bytes can reach at most log2(n) bits/byte
        label = "high-entropy (key/cipher/compressed)"
    else:
        label = "binary-struct"
    return BufferStats(len(data), e, p, z, u, label)


def region_category(r: Region) -> str:
    """Region-level category from OS metadata only."""
    if r.kind == "image":
        return "code (module)" if r.executable else "module data"
    if r.kind == "mapped":
        return "mapped file/section"
    if r.executable:
        return "private executable (JIT?)"
    if r.writable:
        return "heap/stack/private data"
    return "private read-only"


# ---------------------------------------------------------------- key-like windows

def key_candidates(data: bytes, window: int = 32, step: int = 8, min_entropy: float | None = None,
                   max_hits: int = 50):
    """Offsets of windows whose entropy is near the maximum possible for that size.

    For a 32-byte window the ceiling is log2(32)=5 bits; random AES-256 keys sit
    around 4.8-4.9. Text and pointers score far lower. Overlapping hits are merged.
    """
    if min_entropy is None:
        min_entropy = math.log2(window) - 0.35
    hits, last_end = [], -1
    for off in range(0, max(0, len(data) - window + 1), step):
        chunk = data[off:off + window]
        if chunk.count(0) > 2:
            continue
        e = entropy(chunk)
        if e >= min_entropy:
            if off < last_end:
                hits[-1] = (hits[-1][0], off + window - hits[-1][0], max(hits[-1][2], e))
            else:
                hits.append((off, window, e))
            last_end = off + window
            if len(hits) >= max_hits:
                break
    return hits


# ---------------------------------------------------------------- artefact patterns

ARTEFACTS = {
    "url": re.compile(rb"https?://[\x21-\x7e]{4,200}"),
    "email": re.compile(rb"[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\.[A-Za-z]{2,10}"),
    "jwt": re.compile(rb"eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}"),
    "pem": re.compile(rb"-----BEGIN [A-Z ]{3,40}-----"),
    "json": re.compile(rb"\{\s*\"[A-Za-z0-9_]{1,40}\"\s*:"),
    "cookie_header": re.compile(rb"(?:Set-)?Cookie: [\x20-\x7e]{3,200}"),
    "http_header": re.compile(rb"(?:GET|POST|PUT|DELETE|HTTP/1\.[01]) [\x20-\x7e]{1,200}"),
    "hex_blob": re.compile(rb"\b[0-9a-fA-F]{32,128}\b"),
    "base64_blob": re.compile(rb"(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{40,}={0,2}"),
}


@dataclass
class ArtefactHit:
    kind: str
    offset: int
    text: str


def find_artefacts(data: bytes, kinds=None, max_per_kind: int = 20) -> list[ArtefactHit]:
    out = []
    for kind, rx in ARTEFACTS.items():
        if kinds and kind not in kinds:
            continue
        for i, m in enumerate(rx.finditer(data)):
            if i >= max_per_kind:
                break
            out.append(ArtefactHit(kind, m.start(), m.group().decode("latin-1")[:120]))
    return sorted(out, key=lambda h: h.offset)


@dataclass
class RegionReport:
    region: Region
    category: str
    stats: BufferStats | None
    artefact_counts: dict = field(default_factory=dict)
    key_like: int = 0
    error: str = ""

    def to_dict(self) -> dict:
        return {
            **self.region.to_dict(),
            "category": self.category,
            "content": self.stats.to_dict() if self.stats else None,
            "artefacts": self.artefact_counts,
            "key_like_windows": self.key_like,
            "error": self.error,
        }
