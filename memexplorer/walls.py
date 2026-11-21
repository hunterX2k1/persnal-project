"""Document the OS/hardware isolation walls (plan section 6 + exercise 2).

This probes the *boundaries* that stay hard — it reports whether a protected
process can be opened and how much of it is readable, NOT its contents. It never
reads, parses, dumps or interprets credential material from lsass or any other
process. The thesis point is "the wall exists and here is proof", which the
access result alone demonstrates; extracting secrets from another security
context is explicitly out of scope (plan section 10).

Walls covered:
  * Credential Guard / VBS (virtualization-based security) — when ON, OS secrets
    live in VTL1 that even admin cannot read; compare the OpenProcess result on
    lsass with CG off vs on (exercise 2).
  * Protected Processes (PPL) — e.g. lsass (when RunAsPPL), Defender's MsMpEng,
    anti-malware services — resist reads even from admin.
  * TPM — BitLocker/Hello keys live here, never in RAM.
"""
from __future__ import annotations

import subprocess
import sys
from dataclasses import dataclass, field

# Processes whose protection boundary is worth documenting. We only ever report
# the ACCESS RESULT for these, never their memory contents.
WALL_PROCESSES = ["lsass.exe", "csrss.exe", "services.exe", "MsMpEng.exe", "SgrmBroker.exe"]


def _powershell(cmd: str, timeout: int = 30) -> str:
    try:
        r = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", cmd],
                           capture_output=True, text=True, timeout=timeout)
        return r.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def credential_guard_status() -> dict:
    """VBS + Credential Guard running state (Windows). SecurityServicesRunning
    contains 1 when Credential Guard is active, 2 for HVCI."""
    if sys.platform != "win32":
        return {"available": False, "note": "Windows-only (VBS/Credential Guard)"}
    out = _powershell(
        "(Get-CimInstance -ClassName Win32_DeviceGuard "
        "-Namespace root\\Microsoft\\Windows\\DeviceGuard)."
        "SecurityServicesRunning -join ','")
    running = [x for x in out.split(",") if x.strip().isdigit()]
    return {
        "available": True,
        "vbs_services_running_raw": out,
        "credential_guard": "1" in running,
        "hvci": "2" in running,
        "note": "Credential Guard ON: lsass secrets are in VTL1, unreadable even by admin."
                if "1" in running else
                "Credential Guard OFF: lsass memory is readable by admin tools (document this contrast).",
    }


def tpm_present() -> dict:
    if sys.platform != "win32":
        return {"available": False, "note": "Windows-only"}
    out = _powershell("(Get-Tpm).TpmPresent")
    return {"available": True, "tpm_present": out.strip().lower() == "true",
            "note": "BitLocker / Windows Hello keys live in the TPM, never in RAM."}


@dataclass
class ProbeResult:
    name: str
    pids: list[int] = field(default_factory=list)
    openable: bool | None = None
    readable_regions: int = 0
    total_regions: int = 0
    access_error: str = ""
    note: str = ""

    def to_dict(self) -> dict:
        return self.__dict__


def probe_process(name: str, max_regions_to_count: int = 5000) -> ProbeResult:
    """Attempt OpenProcess and count readable regions — boundary result only.

    Deliberately does NOT call read(); we report whether the OS *let us in* and
    how much is enumerable, which is the isolation evidence. No bytes leave the
    target process.
    """
    import psutil

    from .backends import open_process

    res = ProbeResult(name)
    res.pids = [p.pid for p in psutil.process_iter(["name"])
                if (p.info.get("name") or "").lower() == name.lower()]
    if not res.pids:
        res.note = "not running"
        return res
    pid = res.pids[0]
    try:
        proc = open_process(pid)
    except PermissionError as e:
        res.openable = False
        res.access_error = str(e)
        res.note = "WALL HELD: OpenProcess denied (protected process / different context)."
        return res
    try:
        for i, r in enumerate(proc.regions()):
            if i >= max_regions_to_count:
                break
            res.total_regions += 1
            if r.readable:
                res.readable_regions += 1
        res.openable = True
        res.note = (f"opened; {res.readable_regions}/{res.total_regions} regions enumerable "
                    "(contents intentionally NOT read).")
    finally:
        proc.close()
    return res


def survey(processes=None) -> dict:
    processes = processes or WALL_PROCESSES
    report = {
        "credential_guard": credential_guard_status(),
        "tpm": tpm_present(),
        "processes": [probe_process(n).to_dict() for n in processes],
        "disclaimer": "Access results only; no process memory contents are read or dumped.",
    }
    return report
