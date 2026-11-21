# Packaging Memory Explorer as a Windows .exe / installer

A Windows `.exe` can only be built **on Windows** — PyInstaller bundles the
Python of the machine it runs on, so it can't be cross-built from Linux/macOS.
Do the following on your PC.

## 1. Build the single-file exe

From the repo root, in a normal (non-admin) terminal:

```powershell
packaging\build_exe.bat
```

This creates a venv, installs dependencies + PyInstaller, runs the tests, and
produces:

```
dist\memexplorer.exe        # self-contained, ~40 MB, no Python needed to run
```

Run it directly:

```powershell
dist\memexplorer.exe ps --browsers
dist\memexplorer.exe scan <PID> --writable
```

To inspect other users'/system processes, right-click the terminal → **Run as
administrator** and add `--debug-priv`.

(The spec is `packaging\memexplorer.spec`. `anthropic` is bundled only if it is
installed at build time; without it, every command works except
`explain --provider claude`.)

## 2. (Optional) Build a double-click installer

The installer adds a Start-menu entry and can put `memexplorer` on your PATH.

1. Install **Inno Setup** — https://jrsoftware.org/isdl.php
2. Build the exe first (step 1), then:
   ```powershell
   iscc packaging\installer.iss
   ```
   or open `packaging\installer.iss` in Inno Setup and click **Compile**.
3. Output: `dist\MemExplorerSetup.exe` — the installer you double-click.
   It requests admin (memory inspection needs it) and offers an "add to PATH"
   option so you can type `memexplorer` in any terminal.

## Notes

* Antivirus/SmartScreen may warn on an unsigned PyInstaller exe — expected for a
  self-built tool; "More info → Run anyway", or sign it with your own cert.
* The exe is for *your* machine. It reads process memory, which is exactly what
  the thesis is about; keep to machines you own or are authorised to test.
