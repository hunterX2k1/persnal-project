"""Backend-neutral description of one memory region."""
from __future__ import annotations

from dataclasses import dataclass, asdict


@dataclass
class Region:
    base: int
    size: int
    protect: str          # e.g. "RW-", "R-X", "RWX", "R--"
    kind: str             # "image" (code/modules), "mapped" (file/section), "private" (heap/stack)
    path: str = ""        # backing module / file, if any
    readable: bool = True
    raw_protect: int = 0  # native protection flags (Windows PAGE_*), 0 on Linux

    @property
    def end(self) -> int:
        return self.base + self.size

    @property
    def writable(self) -> bool:
        return "W" in self.protect

    @property
    def executable(self) -> bool:
        return "X" in self.protect

    def contains(self, addr: int) -> bool:
        return self.base <= addr < self.end

    def to_dict(self) -> dict:
        d = asdict(self)
        d["base"] = hex(self.base)
        return d
