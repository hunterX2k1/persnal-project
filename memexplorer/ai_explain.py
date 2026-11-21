""""Explain with AI" for a selected memory snippet.

Privacy rules from the plan, enforced in code:
  * cloud (Claude API) only after the caller confirms the snippet comes from
    a test program holding fake data (`confirmed_test_data=True`);
  * `redact=True` (default) masks e-mails, JWTs, cookies, PEM headers and long
    hex/base64 blobs before anything leaves the machine;
  * real dumps -> provider "local" (an Ollama server on this PC) or "print"
    (just show the JSON, send nothing).
The API key comes from the environment (ANTHROPIC_API_KEY or `ant auth login`),
never from this code or a shared config.
"""
from __future__ import annotations

import json
import os
import urllib.request

from .classify import ARTEFACTS, classify_bytes
from .regions import Region

MODEL = os.environ.get("MEMEXPLORER_MODEL", "claude-opus-5-5")
PREVIEW_BYTES = 512

SYSTEM_PROMPT = """You are a memory-forensics teaching assistant for a university thesis.
You receive a JSON description of one region/snippet of a process's memory on the
student's own test machine. Explain, concisely:
1. What this memory most likely is (code, heap object, string table, UTF-16 strings,
   crypto key/ciphertext, HTTP/JSON payload, zero-filled pages, stack, etc.) and why,
   citing the protection, region type, entropy and printable ratio.
2. Notable contents in the preview (structures, encodings, recognisable formats).
3. Security relevance: what would be exposed if an attacker could read this region,
   and which protection (wiping, DPAPI/CNG, TPM, VBS isolation) would prevent it.
Finish with a one-line label in the form `LABEL: <category>` using one of:
code, module-data, heap-text, heap-binary, utf16-strings, crypto-material,
network-payload, zeros, stack, unknown."""

_REDACT_KINDS = ("email", "jwt", "cookie_header", "pem", "hex_blob", "base64_blob")


def redact(data: bytes) -> bytes:
    out = bytearray(data)
    for kind in _REDACT_KINDS:
        for m in ARTEFACTS[kind].finditer(data):
            out[m.start():m.end()] = b"*" * (m.end() - m.start())
    return bytes(out)


def build_payload(pid: int, region: Region, address: int, data: bytes, image_name: str = "",
                  do_redact: bool = True) -> dict:
    stats = classify_bytes(data, executable=region.executable)
    preview = data[:PREVIEW_BYTES]
    if do_redact:
        preview = redact(preview)
    return {
        "pid": pid,
        "image_name": image_name,
        "region_base": hex(region.base),
        "snippet_address": hex(address),
        "size": len(data),
        "region_size": region.size,
        "region_type": region.kind,
        "protection": region.protect,
        "backing_file": region.path,
        "hex_preview": preview.hex(" ", 1)[: PREVIEW_BYTES * 3],
        "ascii_preview": "".join(chr(b) if 0x20 <= b < 0x7F else "." for b in preview),
        "entropy": round(stats.entropy, 3),
        "printable_ratio": round(stats.printable_ratio, 3),
        "utf16_ratio": round(stats.utf16_ratio, 3),
        "zero_ratio": round(stats.zero_ratio, 3),
        "heuristic_label": stats.label,
        "redacted": do_redact,
    }


def explain_claude(payload: dict, confirmed_test_data: bool) -> str:
    if not confirmed_test_data:
        raise PermissionError(
            "Refusing to send memory to a cloud API without confirmation that it comes from a "
            "test program with fake data. Re-run with --confirm-test-data, or use --provider local/print.")
    import anthropic

    client = anthropic.Anthropic()
    try:
        resp = client.beta.messages.create(
            model=MODEL,
            max_tokens=4000,
            system=SYSTEM_PROMPT,
            output_config={"effort": "medium"},
            betas=["server-side-fallback-2026-07-01"],
            fallbacks="default",
            messages=[{"role": "user", "content": json.dumps(payload, indent=2)}],
        )
    except anthropic.AuthenticationError as e:
        raise RuntimeError("Claude API auth failed: set ANTHROPIC_API_KEY or run `ant auth login`.") from e
    except anthropic.RateLimitError as e:
        raise RuntimeError("Claude API rate limit hit; retry shortly.") from e
    except anthropic.APIStatusError as e:
        raise RuntimeError(f"Claude API error {e.status_code}: {e.message}") from e
    except anthropic.APIConnectionError as e:
        raise RuntimeError(f"Could not reach the Claude API: {e}") from e

    if resp.stop_reason == "refusal":
        return "[model declined to analyse this snippet]"
    return "".join(b.text for b in resp.content if b.type == "text").strip()


def explain_local(payload: dict, url: str | None = None, model: str | None = None) -> str:
    """Send to a local Ollama server (default http://localhost:11434). Nothing leaves the PC."""
    url = url or os.environ.get("MEMEXPLORER_LOCAL_URL", "http://localhost:11434/api/generate")
    model = model or os.environ.get("MEMEXPLORER_LOCAL_MODEL", "llama3.1")
    body = json.dumps({
        "model": model,
        "prompt": SYSTEM_PROMPT + "\n\nSNIPPET:\n" + json.dumps(payload, indent=2),
        "stream": False,
    }).encode()
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.loads(r.read())["response"].strip()


def parse_label(explanation: str) -> str:
    for line in reversed(explanation.splitlines()):
        if line.strip().upper().startswith("LABEL:"):
            return line.split(":", 1)[1].strip().strip("`").lower()
    return "unknown"
