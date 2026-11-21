@echo off
REM ============================================================
REM  Build memexplorer.exe on Windows (run from the repo root).
REM  Produces  dist\memexplorer.exe  — a single self-contained file.
REM ============================================================
setlocal
echo [1/3] Creating venv and installing dependencies...
py -m venv .venv || goto :err
call .venv\Scripts\activate.bat
python -m pip install --upgrade pip >nul
python -m pip install -r requirements.txt pyinstaller || goto :err

echo [2/3] Running tests...
python -m pytest -q || echo (tests reported failures - continuing build)

echo [3/3] Building single-file exe...
pyinstaller --clean --noconfirm packaging\memexplorer.spec || goto :err

echo.
echo DONE:  dist\memexplorer.exe
echo Try:   dist\memexplorer.exe ps --browsers
goto :eof
:err
echo BUILD FAILED. See messages above.
exit /b 1
