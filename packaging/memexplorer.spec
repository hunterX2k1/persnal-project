# -*- mode: python ; coding: utf-8 -*-
# Build:  pyinstaller packaging/memexplorer.spec   (run from the repo root)
# Output: dist/memexplorer(.exe)  — a single-file console program.
from PyInstaller.utils.hooks import collect_submodules

hiddenimports = collect_submodules("psutil") + collect_submodules("cryptography")
# anthropic is optional (only the `explain --provider claude` path). Bundle it if installed.
try:
    import anthropic  # noqa: F401
    hiddenimports += collect_submodules("anthropic")
except ImportError:
    pass

a = Analysis(
    ["memexplorer_entry.py"],
    pathex=["."],
    binaries=[],
    datas=[],
    hiddenimports=hiddenimports,
    hookspath=[],
    runtime_hooks=[],
    excludes=["tkinter", "matplotlib", "numpy", "pytest"],
    noarchive=False,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz, a.scripts, a.binaries, a.datas, [],
    name="memexplorer",
    console=True,
    debug=False,
    strip=False,
    upx=False,
)
