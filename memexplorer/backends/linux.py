"""Linux backend (/proc/<pid>/maps + /proc/<pid>/mem).

Not part of the Windows thesis track, but lets the classifier/search/AI code be
developed and tested on any Linux box. Reading another process needs the same
ptrace permission as a debugger (same user + ptrace_scope 0, or root).
"""
from __future__ import annotations

import os

from ..regions import Region


class LinuxProcess:
    def __init__(self, pid: int):
        self.pid = pid
        try:
            self._mem = open(f"/proc/{pid}/mem", "rb", buffering=0)
        except OSError as e:
            raise PermissionError(f"cannot open /proc/{pid}/mem: {e}") from e

    def close(self) -> None:
        self._mem.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    def regions(self, committed_only: bool = True):
        with open(f"/proc/{self.pid}/maps") as f:
            for line in f:
                parts = line.split(maxsplit=5)
                lo, hi = (int(x, 16) for x in parts[0].split("-"))
                perms = parts[1]
                path = parts[5].strip() if len(parts) > 5 else ""
                prot = ("R" if perms[0] == "r" else "-") + ("W" if perms[1] == "w" else "-") + \
                       ("X" if perms[2] == "x" else "-")
                if path.startswith("/") and not path.startswith("/dev/"):
                    kind = "image" if (perms[2] == "x" or ".so" in path or os.access(path, os.X_OK)) else "mapped"
                elif path in ("[vvar]", "[vsyscall]") or path.startswith("/dev/"):
                    kind = "mapped"
                else:
                    kind = "private"  # anon, [heap], [stack]
                readable = perms[0] == "r" and path not in ("[vvar]", "[vsyscall]")
                yield Region(lo, hi - lo, prot, kind, path, readable)

    def read(self, addr: int, size: int) -> bytes:
        self._mem.seek(addr)
        return self._mem.read(size)
