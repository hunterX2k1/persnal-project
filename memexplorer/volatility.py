"""Driver for Volatility 3 (the forensics path of Track B).

Volatility 3 is the standard, citable framework for reconstructing OS structures
(process list, command lines, loaded DLLs, credential material) from a raw memory
image. This module *drives* it — it does not reimplement it — running the plugins
from the plan, parsing their JSON, and collecting everything into one report
alongside a marker search. Install it separately:

    pip install volatility3          # provides the `vol` / `vol.py` entry point

If Volatility is not installed, every command here reports that clearly with the
install hint rather than failing obscurely. The dump-file analyser
(`memexplorer/dumpfile.py`) needs no external tool and covers strings / markers /
artefacts / entropy map on its own.
"""
from __future__ import annotations

import json
import shutil
import subprocess
import sys

# The plan's plugins, section 3. hashdump is included to *demonstrate how
# credential material sits in memory* on your own test machine — documenting the
# OS wall, not attacking anyone else's system.
PLAN_PLUGINS = ["windows.pslist", "windows.cmdline", "windows.dlllist"]
CREDENTIAL_PLUGINS = ["windows.hashdump"]


class VolatilityNotFound(RuntimeError):
    pass


def find_volatility() -> list[str]:
    """Return the argv prefix that launches Volatility 3, or raise."""
    for exe in ("vol", "vol.py", "volatility3"):
        path = shutil.which(exe)
        if path:
            return [path]
    # module form: python -m volatility3
    try:
        import volatility3  # noqa: F401
        return [sys.executable, "-m", "volatility3"]
    except ImportError:
        raise VolatilityNotFound(
            "Volatility 3 not found. Install it with `pip install volatility3` "
            "(gives the `vol` command), then re-run."
        )


def run_plugin(image: str, plugin: str, extra: list[str] | None = None, timeout: int = 1800):
    """Run one plugin with JSON rendering; return parsed rows (list of dicts)."""
    argv = find_volatility() + ["-q", "-r", "json", "-f", image, plugin] + (extra or [])
    proc = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
    if proc.returncode != 0:
        raise RuntimeError(f"{plugin} failed (exit {proc.returncode}):\n{proc.stderr.strip()[:2000]}")
    out = proc.stdout.strip()
    if not out:
        return []
    try:
        return json.loads(out)
    except json.JSONDecodeError:
        # Some builds emit NDJSON; fall back to line-by-line.
        rows = []
        for line in out.splitlines():
            line = line.strip()
            if line:
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    pass
        return rows


def collect(image: str, include_credentials: bool = False, timeout: int = 1800) -> dict:
    """Run the plan's plugins and collect results + per-plugin errors into one dict."""
    find_volatility()  # raise early with the install hint if missing
    plugins = list(PLAN_PLUGINS) + (CREDENTIAL_PLUGINS if include_credentials else [])
    report: dict = {"image": image, "plugins": {}, "errors": {}}
    for plugin in plugins:
        try:
            report["plugins"][plugin] = run_plugin(image, plugin, timeout=timeout)
        except (RuntimeError, subprocess.TimeoutExpired) as e:
            report["errors"][plugin] = str(e)
    return report
