"""Process listing and browser-process role detection (by command line, never by PID guess)."""
from __future__ import annotations

from dataclasses import dataclass

import psutil

BROWSERS = {"chrome.exe", "msedge.exe", "brave.exe", "firefox.exe",
            "chrome", "chromium", "msedge", "firefox", "firefox-bin", "brave"}


@dataclass
class ProcInfo:
    pid: int
    name: str
    user: str
    role: str
    cmdline: str


def browser_role(name: str, cmdline: list[str]) -> str:
    """Chromium: --type=renderer/gpu-process/utility; none => main browser.
    Firefox: -contentproc ... with "tab" => web content (page) process."""
    if name.lower() not in BROWSERS:
        return ""
    joined = " ".join(cmdline)
    for arg in cmdline:
        if arg.startswith("--type="):
            t = arg.split("=", 1)[1]
            if t == "renderer" and "--extension-process" in joined:
                return "renderer (extension)"
            return t
    if "-contentproc" in joined:
        return "tab (web content)" if " tab" in f" {joined}" else "content-child"
    return "browser (main)"


def list_processes(name_filter: str | None = None, browsers_only: bool = False,
                   renderers_only: bool = False) -> list[ProcInfo]:
    out = []
    for p in psutil.process_iter(["pid", "name", "username", "cmdline"]):
        info = p.info
        name = info.get("name") or ""
        cmd = info.get("cmdline") or []
        if name_filter and name_filter.lower() not in name.lower():
            continue
        role = browser_role(name, cmd)
        if browsers_only and not role:
            continue
        if renderers_only and not (role.startswith("renderer") or role.startswith("tab")):
            continue
        out.append(ProcInfo(info["pid"], name, info.get("username") or "?", role, " ".join(cmd)))
    return sorted(out, key=lambda x: (x.name.lower(), x.pid))
