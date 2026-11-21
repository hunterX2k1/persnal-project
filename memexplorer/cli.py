"""Command-line interface: python -m memexplorer <command> ..."""
from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import Counter, defaultdict

import psutil

from . import ai_explain
from .backends import enable_debug_privilege, open_process
from .classify import (RegionReport, classify_bytes, find_artefacts, key_candidates,
                       region_category)
from .processes import list_processes
from .search import build_pattern, extract_strings, hexdump, iter_region_chunks, search_process


def _int(x: str) -> int:
    return int(x, 0)


def _regions(proc, args):
    for r in proc.regions():
        if getattr(args, "writable", False) and not r.writable:
            continue
        if getattr(args, "kind", None) and r.kind != args.kind:
            continue
        yield r


def _find_region(proc, addr):
    for r in proc.regions():
        if r.contains(addr):
            return r
    raise SystemExit(f"address {addr:#x} is not inside any committed region")


def _proc_name(pid):
    try:
        return psutil.Process(pid).name()
    except psutil.Error:
        return ""


# ------------------------------------------------------------------ commands

def cmd_ps(args):
    rows = list_processes(args.name, args.browsers, args.renderers)
    for p in rows:
        role = f"[{p.role}]" if p.role else ""
        line = f"{p.pid:>7}  {p.name:<28} {role:<22} {p.user}"
        if args.cmdline:
            line += f"\n         {p.cmdline[:300]}"
        print(line)
    print(f"\n{len(rows)} processes")


def cmd_regions(args):
    with open_process(args.pid) as proc:
        rows = list(_regions(proc, args))
    if args.json:
        print(json.dumps([r.to_dict() | {"category": region_category(r)} for r in rows], indent=2))
        return
    total = Counter()
    for r in rows:
        total[r.kind] += r.size
        print(f"{r.base:016x} {r.size:>12,}  {r.protect:<4} {r.kind:<8} {region_category(r):<26} {r.path}")
    print("\n" + "  ".join(f"{k}: {v / 2**20:,.1f} MiB" for k, v in total.items()))


def cmd_dump(args):
    with open_process(args.pid) as proc:
        data = proc.read(args.address, args.length)
    print(hexdump(data, args.address))
    s = classify_bytes(data)
    print(f"\nentropy={s.entropy:.2f} printable={s.printable_ratio:.2f} "
          f"utf16={s.utf16_ratio:.2f} zeros={s.zero_ratio:.2f} -> {s.label}")


def cmd_strings(args):
    with open_process(args.pid) as proc:
        data = proc.read(args.address, args.length)
    for off, enc, text in sorted(extract_strings(data, args.min)):
        print(f"{args.address + off:016x} {enc:<5} {text}")


def cmd_search(args):
    mode = "hex" if args.hex else "regex" if args.regex else "text"
    pat = build_pattern(args.pattern, mode, utf16=args.utf16, ignore_case=args.ignore_case)
    n = 0
    with open_process(args.pid) as proc:
        for m in search_process(proc, pat, list(_regions(proc, args)), args.max):
            n += 1
            print(f"{m.address:016x}  len={m.length:<4} {m.region_protect:<4} {m.region_kind:<8} {m.region_path}")
            if args.context:
                start = max(m.address - args.context // 2, 0)
                try:
                    print(hexdump(proc.read(start, args.context), start), "\n")
                except OSError:
                    pass
    print(f"{n} match(es)")


def scan(proc, args):
    reports = []
    for r in _regions(proc, args):
        rep = RegionReport(r, region_category(r), None)
        if not r.readable:
            rep.error = "not readable"
        elif r.size > args.max_region * 2**20:
            rep.error = f"skipped (> {args.max_region} MiB)"
        else:
            buf = bytearray()
            for _, data in iter_region_chunks(proc, r):
                buf += data  # overlap duplicates are fine for statistics
            if buf:
                rep.stats = classify_bytes(bytes(buf), executable=r.executable)
                if not r.executable:
                    rep.artefact_counts = dict(Counter(h.kind for h in find_artefacts(bytes(buf))))
                    if r.writable:
                        rep.key_like = len(key_candidates(bytes(buf)))
            else:
                rep.error = "read failed"
        reports.append(rep)
    return reports


def cmd_scan(args):
    with open_process(args.pid) as proc:
        reports = scan(proc, args)

    by_label, by_cat, artefacts = defaultdict(int), defaultdict(int), Counter()
    key_like = 0
    for rep in reports:
        by_cat[rep.category] += rep.region.size
        if rep.stats:
            by_label[rep.stats.label] += rep.region.size
        artefacts.update(rep.artefact_counts)
        key_like += rep.key_like

    name = _proc_name(args.pid)
    print(f"PID {args.pid} {name}: {len(reports)} regions\n\nBy region category (OS metadata):")
    for k, v in sorted(by_cat.items(), key=lambda kv: -kv[1]):
        print(f"  {k:<30} {v / 2**20:>10,.2f} MiB")
    print("\nBy content (byte statistics):")
    for k, v in sorted(by_label.items(), key=lambda kv: -kv[1]):
        print(f"  {k:<38} {v / 2**20:>10,.2f} MiB")
    print("\nArtefacts found (non-code regions):")
    for k, v in artefacts.most_common():
        print(f"  {k:<16} {v}")
    print(f"\nKey-like high-entropy windows in writable memory: {key_like}")

    if args.json:
        with open(args.json, "w") as f:
            json.dump({"pid": args.pid, "name": name, "regions": [r.to_dict() for r in reports]}, f, indent=2)
        print(f"wrote {args.json}")
    if args.csv:
        with open(args.csv, "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(["base", "size", "protect", "kind", "category", "label", "entropy",
                        "printable", "utf16", "zeros", "key_like", "artefacts", "path", "error"])
            for rep in reports:
                s = rep.stats
                w.writerow([hex(rep.region.base), rep.region.size, rep.region.protect, rep.region.kind,
                            rep.category, s.label if s else "", f"{s.entropy:.3f}" if s else "",
                            f"{s.printable_ratio:.3f}" if s else "", f"{s.utf16_ratio:.3f}" if s else "",
                            f"{s.zero_ratio:.3f}" if s else "", rep.key_like,
                            json.dumps(rep.artefact_counts), rep.region.path, rep.error])
        print(f"wrote {args.csv}")


def cmd_keys(args):
    with open_process(args.pid) as proc:
        if args.address is not None:
            start = max(args.address - args.radius, 0)
            spans = [(start, proc.read(start, 2 * args.radius))]
        else:
            args.writable = True
            spans = [c for r in _regions(proc, args) if r.readable and r.kind == "private"
                     for c in iter_region_chunks(proc, r)]
        shown = 0
        for base, data in spans:
            for off, ln, e in key_candidates(data, window=args.window):
                print(f"{base + off:016x} len={ln:<4} H={e:.2f}  {data[off:off + ln].hex()}")
                shown += 1
                if shown >= args.max:
                    return


def cmd_explain(args):
    with open_process(args.pid) as proc:
        region = _find_region(proc, args.address)
        data = proc.read(args.address, min(args.length, region.end - args.address))
    payload = ai_explain.build_payload(args.pid, region, args.address, data, _proc_name(args.pid),
                                       do_redact=not args.no_redact)
    if args.provider == "print":
        print(json.dumps(payload, indent=2))
        return
    if args.provider == "claude":
        text = ai_explain.explain_claude(payload, confirmed_test_data=args.confirm_test_data)
    else:
        text = ai_explain.explain_local(payload, args.local_url, args.local_model)
    print(text)


# ------------------------------------------------------------------ parser

def build_parser():
    p = argparse.ArgumentParser(prog="memexplorer", description="Process memory explorer (thesis lab tool)")
    p.add_argument("--debug-priv", action="store_true", help="enable SeDebugPrivilege (Windows, admin)")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("ps", help="list processes")
    s.add_argument("--name")
    s.add_argument("--browsers", action="store_true", help="only browser processes, with their role")
    s.add_argument("--renderers", action="store_true", help="only page-content (renderer/tab) processes")
    s.add_argument("--cmdline", action="store_true")
    s.set_defaults(fn=cmd_ps)

    def pid_cmd(name, fn, help_):
        s = sub.add_parser(name, help=help_)
        s.add_argument("pid", type=int)
        s.set_defaults(fn=fn)
        return s

    def filters(s):
        s.add_argument("--writable", action="store_true", help="only writable (data/heap/stack) regions")
        s.add_argument("--kind", choices=["image", "mapped", "private"])

    s = pid_cmd("regions", cmd_regions, "list memory regions")
    filters(s)
    s.add_argument("--json", action="store_true")

    s = pid_cmd("dump", cmd_dump, "hex/ASCII dump")
    s.add_argument("address", type=_int)
    s.add_argument("--length", "-n", type=_int, default=256)

    s = pid_cmd("strings", cmd_strings, "ASCII + UTF-16 strings in a range")
    s.add_argument("address", type=_int)
    s.add_argument("--length", "-n", type=_int, default=4096)
    s.add_argument("--min", type=int, default=6)

    s = pid_cmd("search", cmd_search, "search bytes/strings")
    s.add_argument("pattern")
    g = s.add_mutually_exclusive_group()
    g.add_argument("--hex", action="store_true")
    g.add_argument("--regex", action="store_true")
    s.add_argument("--utf16", action="store_true", help="also match UTF-16LE (Windows wide strings)")
    s.add_argument("-i", "--ignore-case", action="store_true")
    s.add_argument("--max", type=int, default=100)
    s.add_argument("--context", type=int, default=0, help="bytes of hexdump around each hit")
    filters(s)

    s = pid_cmd("scan", cmd_scan, "classify every region and summarise")
    filters(s)
    s.add_argument("--max-region", type=int, default=256, help="skip regions larger than N MiB")
    s.add_argument("--json")
    s.add_argument("--csv")

    s = pid_cmd("keys", cmd_keys, "find key-like high-entropy windows")
    s.add_argument("--address", type=_int, help="only look around this address")
    s.add_argument("--radius", type=_int, default=4096)
    s.add_argument("--window", type=int, default=32)
    s.add_argument("--max", type=int, default=50)
    s.add_argument("--kind", default=None)

    s = pid_cmd("explain", cmd_explain, "explain a snippet with AI")
    s.add_argument("address", type=_int)
    s.add_argument("--length", "-n", type=_int, default=256)
    s.add_argument("--provider", choices=["print", "claude", "local"], default="print")
    s.add_argument("--confirm-test-data", action="store_true",
                   help="I confirm this process holds only my own fake test data (required for claude)")
    s.add_argument("--no-redact", action="store_true")
    s.add_argument("--local-url")
    s.add_argument("--local-model")
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    if args.debug_priv:
        print("SeDebugPrivilege:", "enabled" if enable_debug_privilege() else "NOT enabled (not admin?)",
              file=sys.stderr)
    try:
        args.fn(args)
    except (PermissionError, OSError, RuntimeError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
