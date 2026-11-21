"""Tests for the storage/data-lifecycle demo (section 4)."""
import importlib.util
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]


def _load():
    spec = importlib.util.spec_from_file_location("storage_target", ROOT / "demo" / "storage_target.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


storage = _load()


def test_plaintext_roundtrip(tmp_path):
    p = storage.write_plaintext(tmp_path / "s.txt", storage.SECRET)
    assert p.read_bytes() == storage.SECRET
    assert storage.MARKER.encode() in p.read_bytes()


def test_encrypted_hides_marker_and_differs(tmp_path):
    info = storage.write_encrypted(tmp_path / "s.bin", storage.SECRET)
    blob = Path(info["file"]).read_bytes()
    assert blob != storage.SECRET
    assert storage.MARKER.encode() not in blob          # marker not visible in ciphertext
    assert info["method"] and info["key_location"]


@pytest.mark.skipif(sys.platform == "win32", reason="sidecar-key path is the non-Windows branch")
def test_encrypted_key_stored_separately(tmp_path):
    info = storage.write_encrypted(tmp_path / "s.bin", storage.SECRET)
    assert (tmp_path / "s.bin.key").exists()            # key is apart from the ciphertext file
    from cryptography.fernet import Fernet
    key = (tmp_path / "s.bin.key").read_bytes()
    assert Fernet(key).decrypt(Path(info["file"]).read_bytes()) == storage.SECRET


def test_find_swap_files_no_crash():
    for s in storage.find_swap_files():
        assert "file" in s and "volume_encrypted" in s


def test_cli_runs_and_writes_all_layers(tmp_path):
    r = subprocess.run([sys.executable, str(ROOT / "demo" / "storage_target.py"),
                        "--outdir", str(tmp_path), "--no-wait"],
                       capture_output=True, text=True)
    assert r.returncode == 0, r.stderr
    assert "Data-lifecycle" in r.stdout
    assert (tmp_path / "secret_plaintext.txt").exists()
    assert (tmp_path / "secret_encrypted.bin").exists()
    assert storage.MARKER in r.stdout
