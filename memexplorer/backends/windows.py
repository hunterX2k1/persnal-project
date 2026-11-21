"""Windows backend: OpenProcess / VirtualQueryEx / ReadProcessMemory via ctypes.

Same-user processes usually open without admin. For other users' or SYSTEM
processes, run as Administrator; `enable_debug_privilege()` turns on
SeDebugPrivilege for the current token (it must already be granted, i.e. admin).
Protected processes (PPL) and VBS-isolated memory still refuse — that is the
expected result and worth recording in the thesis, not something to bypass.
"""
from __future__ import annotations

import ctypes
import sys
from ctypes import wintypes

from ..regions import Region

if sys.platform != "win32":  # pragma: no cover - imported only on Windows
    raise ImportError("Windows backend requires Windows")

kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
advapi32 = ctypes.WinDLL("advapi32", use_last_error=True)

PROCESS_QUERY_INFORMATION = 0x0400
PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
PROCESS_VM_READ = 0x0010

MEM_COMMIT = 0x1000
MEM_IMAGE = 0x1000000
MEM_MAPPED = 0x40000
MEM_PRIVATE = 0x20000

PAGE_NOACCESS = 0x01
PAGE_GUARD = 0x100

_PROT = {
    0x01: "---", 0x02: "R--", 0x04: "RW-", 0x08: "RC-",
    0x10: "--X", 0x20: "R-X", 0x40: "RWX", 0x80: "RCX",
}


class MEMORY_BASIC_INFORMATION(ctypes.Structure):
    if ctypes.sizeof(ctypes.c_void_p) == 8:
        _fields_ = [
            ("BaseAddress", ctypes.c_void_p),
            ("AllocationBase", ctypes.c_void_p),
            ("AllocationProtect", wintypes.DWORD),
            ("PartitionId", wintypes.WORD),
            ("RegionSize", ctypes.c_size_t),
            ("State", wintypes.DWORD),
            ("Protect", wintypes.DWORD),
            ("Type", wintypes.DWORD),
        ]
    else:
        _fields_ = [
            ("BaseAddress", ctypes.c_void_p),
            ("AllocationBase", ctypes.c_void_p),
            ("AllocationProtect", wintypes.DWORD),
            ("RegionSize", ctypes.c_size_t),
            ("State", wintypes.DWORD),
            ("Protect", wintypes.DWORD),
            ("Type", wintypes.DWORD),
        ]


kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
kernel32.OpenProcess.restype = wintypes.HANDLE
kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
kernel32.VirtualQueryEx.argtypes = [wintypes.HANDLE, ctypes.c_void_p,
                                    ctypes.POINTER(MEMORY_BASIC_INFORMATION), ctypes.c_size_t]
kernel32.VirtualQueryEx.restype = ctypes.c_size_t
kernel32.ReadProcessMemory.argtypes = [wintypes.HANDLE, ctypes.c_void_p, ctypes.c_void_p,
                                       ctypes.c_size_t, ctypes.POINTER(ctypes.c_size_t)]
kernel32.ReadProcessMemory.restype = wintypes.BOOL
kernel32.K32GetMappedFileNameW.argtypes = [wintypes.HANDLE, ctypes.c_void_p,
                                           wintypes.LPWSTR, wintypes.DWORD]
kernel32.K32GetMappedFileNameW.restype = wintypes.DWORD


def _prot_str(p: int) -> str:
    s = _PROT.get(p & 0xFF, "?")
    if p & PAGE_GUARD:
        s += "G"
    return s


def enable_debug_privilege() -> bool:
    """Enable SeDebugPrivilege on our own token (needs an elevated process)."""
    TOKEN_ADJUST_PRIVILEGES, TOKEN_QUERY, SE_PRIVILEGE_ENABLED = 0x20, 0x8, 0x2

    class LUID(ctypes.Structure):
        _fields_ = [("LowPart", wintypes.DWORD), ("HighPart", wintypes.LONG)]

    class LUID_AND_ATTRIBUTES(ctypes.Structure):
        _fields_ = [("Luid", LUID), ("Attributes", wintypes.DWORD)]

    class TOKEN_PRIVILEGES(ctypes.Structure):
        _fields_ = [("PrivilegeCount", wintypes.DWORD), ("Privileges", LUID_AND_ATTRIBUTES * 1)]

    token = wintypes.HANDLE()
    if not advapi32.OpenProcessToken(kernel32.GetCurrentProcess(),
                                     TOKEN_ADJUST_PRIVILEGES | TOKEN_QUERY, ctypes.byref(token)):
        return False
    try:
        luid = LUID()
        if not advapi32.LookupPrivilegeValueW(None, "SeDebugPrivilege", ctypes.byref(luid)):
            return False
        tp = TOKEN_PRIVILEGES(1, (LUID_AND_ATTRIBUTES * 1)(LUID_AND_ATTRIBUTES(luid, SE_PRIVILEGE_ENABLED)))
        advapi32.AdjustTokenPrivileges(token, False, ctypes.byref(tp), 0, None, None)
        return ctypes.get_last_error() == 0  # ERROR_NOT_ALL_ASSIGNED == 1300 means not admin
    finally:
        kernel32.CloseHandle(token)


class WindowsProcess:
    def __init__(self, pid: int):
        self.pid = pid
        self.handle = kernel32.OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, False, pid)
        if not self.handle:
            err = ctypes.get_last_error()
            raise PermissionError(
                f"OpenProcess({pid}) failed, error {err}"
                + (" (access denied: different user, needs admin, or protected process)" if err == 5 else "")
            )

    def close(self) -> None:
        if self.handle:
            kernel32.CloseHandle(self.handle)
            self.handle = None

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    def _mapped_name(self, addr: int) -> str:
        buf = ctypes.create_unicode_buffer(1024)
        n = kernel32.K32GetMappedFileNameW(self.handle, ctypes.c_void_p(addr), buf, 1024)
        return buf.value if n else ""

    def regions(self, committed_only: bool = True):
        mbi = MEMORY_BASIC_INFORMATION()
        addr = 0
        max_addr = (1 << 47) if ctypes.sizeof(ctypes.c_void_p) == 8 else (1 << 31)
        while addr < max_addr:
            if not kernel32.VirtualQueryEx(self.handle, ctypes.c_void_p(addr),
                                           ctypes.byref(mbi), ctypes.sizeof(mbi)):
                break
            base = mbi.BaseAddress or 0
            size = mbi.RegionSize
            if mbi.State == MEM_COMMIT or not committed_only:
                kind = {MEM_IMAGE: "image", MEM_MAPPED: "mapped", MEM_PRIVATE: "private"}.get(mbi.Type, "?")
                readable = (mbi.State == MEM_COMMIT and not (mbi.Protect & PAGE_GUARD)
                            and (mbi.Protect & 0xFF) not in (0, PAGE_NOACCESS, 0x10))
                path = self._mapped_name(base) if kind in ("image", "mapped") else ""
                yield Region(base, size, _prot_str(mbi.Protect), kind, path, readable, mbi.Protect)
            nxt = base + size
            if nxt <= addr:
                break
            addr = nxt

    def read(self, addr: int, size: int) -> bytes:
        buf = (ctypes.c_char * size)()
        got = ctypes.c_size_t(0)
        ok = kernel32.ReadProcessMemory(self.handle, ctypes.c_void_p(addr), buf, size, ctypes.byref(got))
        if not ok and got.value == 0:
            raise OSError(f"ReadProcessMemory @{addr:#x} failed, error {ctypes.get_last_error()}")
        return bytes(buf[: got.value])
