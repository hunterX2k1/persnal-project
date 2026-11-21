#!/usr/bin/env bash
# Linux/macOS build (for validating the spec; produces a native binary, not a .exe).
set -e
python3 -m pip install -r requirements.txt pyinstaller
pyinstaller --clean --noconfirm packaging/memexplorer.spec
echo "DONE: dist/memexplorer"
