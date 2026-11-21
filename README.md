# Memory Explorer — Windows thesis lab tool

Reads, searches and categorises the **live memory of a process on a machine you own**,
and analyses **full physical-RAM dumps offline**, for a thesis on where data lives
(RAM vs disk) and what the OS/hardware isolates. Implements **Track A** (live reader)
and **Track B** (dump capture + forensics) of [`docs/plan-windows.txt`](docs/plan-windows.txt).

Scope: your own PC and your own test programs/sites. The tool is deliberately a
reader/classifier; it contains no LSASS/credential extraction or protection bypass.

## Setup (Windows, 64-bit Python 3.10+)

```powershell
py -m venv .venv; .venv\Scripts\activate
pip install -r requirements.txt
```

Same-user processes open without admin. For other users'/SYSTEM processes run an
elevated shell and add `--debug-priv` (enables SeDebugPrivilege). PPL processes and
VBS-isolated memory will still return *access denied* — record that as a result.
A Linux `/proc` backend is included so the code and tests also run on Linux.

## Commands

| Command | What it does |
|---|---|
| `python -m memexplorer ps [--name X] [--browsers] [--renderers] [--cmdline]` | List processes; browser processes get their role from the command line (`renderer`, `gpu-process`, Firefox `tab`…) |
| `python -m memexplorer regions PID [--writable] [--kind image\|mapped\|private] [--json]` | Walk regions (VirtualQueryEx): base, size, protection, type, backing module, category |
| `python -m memexplorer dump PID ADDR [-n 256]` | Hex/ASCII dump + byte statistics |
| `python -m memexplorer strings PID ADDR [-n 4096]` | ASCII **and UTF-16LE** strings (Windows uses wide strings a lot) |
| `python -m memexplorer search PID TEXT [--utf16] [--hex] [--regex] [-i] [--writable] [--context 64]` | Search all readable regions |
| `python -m memexplorer scan PID [--writable] [--csv out.csv] [--json out.json]` | Classify every region → summary of MiB per category/content label, artefact counts (URLs, e-mails, JWTs, JSON, cookies, PEM, hex/base64 blobs) and key-like windows |
| `python -m memexplorer keys PID [--address ADDR --radius 256]` | High-entropy windows that look like keys |
| `python -m memexplorer explain PID ADDR [-n 256] --provider print\|local\|claude` | "Explain with AI" (see privacy below) |
| `python -m memexplorer analyse-dump IMAGE [--marker X [--hex] [--context N]] [--block MiB] [--json out]` | **Track B:** run the classifier/strings/artefacts/marker-search over a flat RAM dump — no external tool, any OS |
| `python -m memexplorer volatility IMAGE [--plugin P] [--credentials] [--json out]` | **Track B:** drive Volatility 3 (pslist / cmdline / dlllist, +hashdump with `--credentials`) and collect a report |
| `python -m memexplorer walls [--process NAME] [--json out]` | **§6 isolation walls:** Credential Guard/VBS + TPM status and the OpenProcess *access result* on protected processes (lsass, Defender, …) — reports the boundary, never reads contents |

Classification: region level from the OS (`image` = code/modules, `mapped`, `private` =
heap/stack; `R-X` code vs `RW-` data) and content level from bytes (zeros / text /
utf16-text / high-entropy key-cipher-compressed / binary-struct / code), using
size-normalised Shannon entropy and printable ratios.

## Demo targets (fake data only)

* `demo/crypto_target.py [--gcm]` — plaintext + AES-256 key + IV + ciphertext in RAM, prints
  addresses; Enter wipes key/plaintext (memset ≈ SecureZeroMemory). Search again afterwards:
  Python's hidden copies of the marker are a finding in themselves.
* `demo/dpapi_target.py` — same secret protected with DPAPI: only the opaque blob is in the
  process, no key; decrypting brings plaintext back into RAM.
* `demo/web_fetch_target.py URL` — fetch your own LAN test site; the decrypted HTTPS body is
  findable in RAM while Wireshark shows only ciphertext.
* `demo/storage_target.py [--outdir out]` — **section 4 storage chapter.** Writes the same fake
  secret into every layer (RAM variable, plaintext file, encrypted file) and prints a
  data-lifecycle table + the on-disk plaintext-vs-ciphertext contrast. The encrypted file uses
  **DPAPI on Windows** (key held by the OS/TPM, not in the process) and an AES stand-in with a
  separate key file elsewhere. It also locates `pagefile.sys` / `hiberfil.sys` (or `/proc/swaps`)
  and reports BitLocker state — the RAM-spilled-to-disk layer you then read offline.

## Track B — full RAM dump + offline forensics

Capture all physical memory (needs admin), then analyse the image. Two analysers:

**1. Built-in offline analyser (`analyse-dump`)** — the same classifier, string
extractor, artefact detector and marker search used on live processes, run directly
over the flat image. Pure Python on a file: no admin, no OS, works on a dump from any
machine. Produces the categorised "how much of RAM is code / text / zeros / key
material" figure (as % and MiB), carves URLs / e-mails / JWTs / cookies / JSON / PEM /
hex & base64 blobs with their file offsets, and locates planted markers.

```powershell
# capture (pick one; all need admin)
winpmem.exe mem.raw           # or: DumpIt.exe / Magnet RAM Capture
python -m memexplorer analyse-dump mem.raw --marker THESIS_MARKER_7f3a9c --context 64 --json dump.json
```

**2. Volatility 3 driver (`volatility`)** — orchestrates the standard forensics
framework for OS-structure reconstruction (not reimplemented). Install separately:

```bash
pip install volatility3
python -m memexplorer volatility mem.raw --json vol.json      # pslist + cmdline + dlllist
python -m memexplorer volatility mem.raw --credentials        # + windows.hashdump (own machine only)
python -m memexplorer volatility mem.raw --plugin windows.netscan   # any single plugin, raw JSON
```

If Volatility isn't installed the command says so with the install hint. `hashdump` is
included to *document how credential material sits in memory* on your own test machine —
illustrating the OS wall, not attacking anyone's system.

## AI explain — privacy rules enforced in code

* `--provider print` (default): show the JSON payload, send nothing.
* `--provider local`: POST to a local Ollama server (`MEMEXPLORER_LOCAL_URL`,
  `MEMEXPLORER_LOCAL_MODEL`). Use this for real dumps.
* `--provider claude`: Claude API (`claude-opus-5-5`, override with `MEMEXPLORER_MODEL`),
  **only with `--confirm-test-data`**. Key from `ANTHROPIC_API_KEY` or `ant auth login`, never
  stored in the repo. Server-side refusal fallback is enabled (`fallbacks: "default"`).
* Previews are redacted (e-mails, JWTs, cookies, PEM, long hex/base64) unless `--no-redact`.
* The reply ends with `LABEL: <category>`; `ai_explain.parse_label()` extracts it so you can
  score accuracy against the heuristic label (Exercise 5).

## Exercises → commands

1. **Own test program:** `crypto_target.py`, then `search PID THESIS_MARKER_7f3a9c --utf16`,
   `search PID <keyhex> --hex`, `keys PID --address <key addr>`; wipe and repeat.
2. **Credential Guard on/off:** `python -m memexplorer walls --json walls_cg_on.json`, then toggle
   Credential Guard and re-run to `walls_cg_off.json`. Compare the lsass access result in the two —
   the boundary (openable vs denied, regions enumerable) is the data; contents are never read.
3. **Full RAM (Track B):** capture with WinPMEM/DumpIt (admin), then
   `python -m memexplorer analyse-dump mem.raw --marker THESIS_MARKER_7f3a9c` for the
   categorised map + marker location, and `python -m memexplorer volatility mem.raw` for
   the process list / command lines / loaded DLLs.
4. **pagefile/hiberfil, BitLocker off vs on:** examine on a test machine from offline media;
   search for the marker the demo left behind.
5. **AI labelling:** run `explain` on 3+ regions of the demo process, compare `LABEL:` with
   `heuristic_label` and your ground truth.

Browser renderer workflow (section 12): open only your test tab,
`ps --renderers --cmdline`, then `search PID "<word from page>" --utf16 --writable --context 128`
and `scan PID --writable --csv renderer.csv`.

## Wire vs RAM — the TLS boundary (section 11)

Capture your own test traffic on the wire and find the same content in RAM, for the
"both sides of the TLS boundary" figure. **Your sites / your network only.**

* **Wireshark** on the LAN interface: plain HTTP shows everything; HTTPS shows only
  encrypted bytes.
* **mitmproxy / Fiddler**: run as a LAN proxy with *your own* CA installed on a *test*
  machine, point your own browser/app at it, read the decrypted request/response.
* **Decrypt your own browser's TLS in Wireshark with `SSLKEYLOGFILE`** — works because you
  own the client, not a way into anyone else's traffic:
  1. Set the env var *before* launching the browser so it logs its session keys:
     ```powershell
     setx SSLKEYLOGFILE "%USERPROFILE%\tls-keys.log"   # new shell, then launch Chrome/Edge/Firefox
     ```
     ```bash
     export SSLKEYLOGFILE=~/tls-keys.log && firefox   # Linux/macOS
     ```
  2. Wireshark → *Preferences → Protocols → TLS → (Pre)-Master-Secret log filename* →
     point at that file. HTTPS frames now decrypt in the capture.
  3. Load your test site, then run `demo/web_fetch_target.py` (or read the browser renderer
     per section 12) and `search` the process RAM for a word from the page.

**One figure, three layers:** the same page captured encrypted on the wire (or decrypted via
your own key log), found in clear in the client's RAM, and persisted in the browser cache /
cookies SQLite on disk. The AI/privacy rule from section 7 still holds — only your own test
traffic goes to a cloud model; real captures use the local provider.

## Android app (non-root, self-inspection)

`android/` is the Android analogue (Kotlin), built to [`docs/plan-android-nonroot.txt`](docs/plan-android-nonroot.txt).
Without root an app can read only **its own** process, so the app inspects itself:

* **Scan my regions** — parse `/proc/self/maps`, sample `/proc/self/mem`, classify by the same
  entropy/printable rules as the Python tool.
* **Crypto demo** — plant an AES key + marked plaintext + ciphertext in RAM, locate them, count
  key-like entropy windows, then **wipe** and re-scan (shows ART's hidden copies).
* **Storage (§5 "one figure")** — the same secret in RAM, in a plaintext file, and in a
  Keystore-encrypted file: plaintext is found in the first two, only ciphertext in the third.
* **Keystore (§6)** — a TEE-backed AES key whose bytes are not exportable (`getEncoded()==null`),
  so they never appear in RAM — the Android equivalent of the Windows TPM/VBS wall.
* **Walls** — a screen explaining the UID sandbox, SELinux, `ptrace_scope`, Keystore/TEE/StrongBox.

Build the APK on CI (no local Android SDK needed) via `.github/workflows/build-apk.yml`, or locally:

```bash
cd android && ./gradlew assembleDebug    # -> app/build/outputs/apk/debug/app-debug.apk
```

## Build a Windows .exe / installer

```powershell
packaging\build_exe.bat          # -> dist\memexplorer.exe (self-contained, no Python needed)
iscc packaging\installer.iss     # optional -> dist\MemExplorerSetup.exe (double-click installer)
```
Must be built on Windows (a .exe can't be cross-built from Linux/macOS). Full steps and notes:
[`packaging/README.md`](packaging/README.md).

## Tests

```bash
python -m pytest -q
```
Includes an end-to-end test that starts the crypto demo, finds the marker/key in its memory,
triggers the wipe and checks that the key reads back as zeros.
