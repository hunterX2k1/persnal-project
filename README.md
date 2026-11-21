# Memory Explorer — Windows thesis lab tool

Reads, searches and categorises the **live memory of a process on a machine you own**,
for a thesis on where data lives (RAM vs disk) and what the OS/hardware isolates.
Implements **Track A** of [`docs/plan-windows.txt`](docs/plan-windows.txt); Track B
(full-RAM capture + Volatility) uses existing tools — commands are listed below.

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
2. **Credential Guard on/off:** document with `msinfo32` (VBS status) and observe that
   protected processes deny `OpenProcess` — use the access result as data, not the contents.
3. **Full RAM (Track B):** capture with WinPMEM/DumpIt (admin), then Volatility 3:
   `vol -f mem.raw windows.pslist`, `windows.cmdline`, `windows.dlllist`, and
   `strings -el mem.raw | findstr THESIS_MARKER` (plus plain `strings`) to locate your marker.
4. **pagefile/hiberfil, BitLocker off vs on:** examine on a test machine from offline media;
   search for the marker the demo left behind.
5. **AI labelling:** run `explain` on 3+ regions of the demo process, compare `LABEL:` with
   `heuristic_label` and your ground truth.

Browser renderer workflow (section 12): open only your test tab,
`ps --renderers --cmdline`, then `search PID "<word from page>" --utf16 --writable --context 128`
and `scan PID --writable --csv renderer.csv`.

## Tests

```bash
python -m pytest -q
```
Includes an end-to-end test that starts the crypto demo, finds the marker/key in its memory,
triggers the wipe and checks that the key reads back as zeros.
