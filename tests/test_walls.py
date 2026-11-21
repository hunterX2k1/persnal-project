"""Tests for the isolation-walls probe (section 6 / exercise 2)."""
import subprocess
import sys
from pathlib import Path

from memexplorer import walls

ROOT = Path(__file__).resolve().parents[1]


def test_survey_shape():
    rep = walls.survey(["definitely-not-a-real-process.exe"])
    assert "credential_guard" in rep and "tpm" in rep
    assert rep["processes"][0]["note"] == "not running"
    assert "no process memory contents" in rep["disclaimer"].lower()


def test_cg_tpm_degrade_off_windows():
    if sys.platform != "win32":
        assert walls.credential_guard_status()["available"] is False
        assert walls.tpm_present()["available"] is False


def test_probe_openable_counts_regions_without_reading(monkeypatch):
    """Openable target: reports region counts but never calls read()."""
    class FakeRegion:
        def __init__(self, readable): self.readable = readable

    class FakeProc:
        def __init__(self): self.read_called = False
        def regions(self): return [FakeRegion(True), FakeRegion(True), FakeRegion(False)]
        def read(self, *a): self.read_called = True; raise AssertionError("must not read contents")
        def close(self): pass

    class FakeP:
        info = {"name": "lsass.exe"}
        pid = 4321

    monkeypatch.setattr(walls, "WALL_PROCESSES", ["lsass.exe"])
    import psutil
    monkeypatch.setattr(psutil, "process_iter", lambda attrs=None: [FakeP()])
    monkeypatch.setattr(walls, "open_process", None, raising=False)
    import memexplorer.backends as backends
    monkeypatch.setattr(backends, "open_process", lambda pid: FakeProc())

    res = walls.probe_process("lsass.exe")
    assert res.openable is True
    assert res.readable_regions == 2 and res.total_regions == 3


def test_probe_denied_is_reported_as_wall_held(monkeypatch):
    class FakeP:
        info = {"name": "lsass.exe"}
        pid = 4

    import psutil
    monkeypatch.setattr(psutil, "process_iter", lambda attrs=None: [FakeP()])
    import memexplorer.backends as backends

    def deny(pid):
        raise PermissionError("access denied (protected process)")
    monkeypatch.setattr(backends, "open_process", deny)

    res = walls.probe_process("lsass.exe")
    assert res.openable is False
    assert "WALL HELD" in res.note


def test_cli_walls_runs():
    r = subprocess.run([sys.executable, "-m", "memexplorer", "walls", "--process", "nope.exe"],
                       capture_output=True, text=True, cwd=ROOT)
    assert r.returncode == 0, r.stderr
    assert "isolation walls" in r.stdout
    assert "no process memory contents" in r.stdout.lower()
