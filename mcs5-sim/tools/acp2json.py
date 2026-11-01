#!/usr/bin/env python3
"""
acp2json.py - convert an MTU MCS-5 ACP software package into a ship data pack
for the MCS-5 Trainer app.

Input: the three self-extracting archives that make up an ACP delivery
(A<gen>_<ver>.exe = InTouch/display application, C<gen>_<ver>.exe = controller
software + plant documentation, P<gen>_<ver>.exe = display pages), either
already extracted into folders, or as the .exe files themselves (requires the
`libarchive-c` Python package to unpack them).

Usage:
    python acp2json.py --a <A-folder-or-exe> --c <C-folder-or-exe> -o ship.json

Only the A and C packages are required. The output ship.json is loaded into the
app with "Load ship data" (or bundled into the APK, see android/README).

The ship data is project-specific and may be proprietary/restricted.
Do NOT commit ship.json or the ACP files to a public repository.
"""
import argparse
import csv
import io
import json
import os
import re
import sys
import tempfile
import xml.etree.ElementTree as ET


# --------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------
def read_text(path):
    with open(path, "rb") as f:
        raw = f.read()
    if raw.startswith(b"\xef\xbb\xbf"):
        return raw[3:].decode("utf-8", "replace")
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("latin-1")


def find(folder, name):
    """case-insensitive file lookup"""
    if not folder:
        return None
    low = name.lower()
    for fn in os.listdir(folder):
        if fn.lower() == low:
            return os.path.join(folder, fn)
    return None


def num(s, default=None):
    try:
        v = float(s)
        if v != v:  # NaN
            return default
        return int(v) if v.is_integer() else round(v, 6)
    except (TypeError, ValueError):
        return default


def unpack_if_needed(path, workdir, label):
    if os.path.isdir(path):
        return path
    try:
        import libarchive  # type: ignore
    except ImportError:
        sys.exit(f"{path} is not a folder. Extract it first with 7-Zip / WinRAR, "
                 f"or `pip install libarchive-c` to let this script unpack it.")
    dest = os.path.join(workdir, label)
    os.makedirs(dest, exist_ok=True)
    cwd = os.getcwd()
    os.chdir(dest)
    try:
        if zipfile_like(path):
            import zipfile
            zipfile.ZipFile(path).extractall(dest)
        else:
            try:
                libarchive.extract_file(os.path.abspath(path) if not os.path.isabs(path) else path)
            except Exception as e:  # partial archives still give most files
                print(f"warning: {label}: {e} (continuing with what was extracted)")
    finally:
        os.chdir(cwd)
    return dest


def zipfile_like(path):
    import zipfile
    return zipfile.is_zipfile(path)


# --------------------------------------------------------------------------
# A package: InTouch tag database (db.csv)
# --------------------------------------------------------------------------
def parse_tag_db(path):
    """Wonderware InTouch DBDump: sections start with ':<Type>;col;col...'"""
    text = read_text(path)
    signals, groups, access = [], [], []
    cols, kind = None, None
    for line in text.splitlines():
        if not line.strip():
            continue
        if line.startswith(":"):
            parts = line[1:].split(";")
            kind, cols = parts[0], parts
            continue
        if cols is None:
            continue
        vals = line.split(";")
        rec = dict(zip(cols[1:], vals[1:]))
        name = vals[0]
        if kind == "AlarmGroup":
            groups.append({"name": name, "parent": rec.get("Group", ""),
                           "red": rec.get("Comment", "").endswith("Red")})
        elif kind == "IOAccess":
            access.append({"name": name, "topic": rec.get("Topic", "")})
        elif kind in ("IOReal", "IOInt"):
            s = {"tag": name, "t": "A" if kind == "IOReal" else "I",
                 "g": rec.get("Group", ""), "n": rec.get("Comment", "").strip(),
                 "u": rec.get("EngUnits", "").strip(),
                 "min": num(rec.get("MinEU")), "max": num(rec.get("MaxEU")),
                 "io": rec.get("DDEAccessName", ""), "item": rec.get("ItemName", "")}
            hi = rec.get("HiAlarmState")
            if hi and hi.strip() not in ("", "No", "0"):
                s["hi"] = num(rec.get("HiAlarmValue"))
                s["pri"] = num(rec.get("HiAlarmPri"))
            signals.append(s)
        elif kind == "IODisc":
            s = {"tag": name, "t": "D", "g": rec.get("Group", ""),
                 "n": rec.get("Comment", "").strip(),
                 "io": rec.get("DDEAccessName", ""), "item": rec.get("ItemName", "")}
            st = rec.get("AlarmState", "")
            if st and st not in ("None", "No", ""):
                s["pri"] = num(rec.get("AlarmPri"))
            signals.append(s)
    return signals, groups, access


# --------------------------------------------------------------------------
# C package: plant documentation
# --------------------------------------------------------------------------
def parse_plant(path):
    lines = read_text(path).splitlines()
    out = {"info": {}, "deviceTypes": [], "software": [], "systems": [],
           "devices": [], "serial": [], "buses": []}

    def section(header_startswith):
        for i, l in enumerate(lines):
            if l.startswith(header_startswith):
                return i
        return None

    i = section("TP-Number")
    if i is not None:
        row = lines[i + 2].split()
        if row:
            out["info"] = {"tp": row[0], "index": row[1],
                           "tpName": " ".join(row[2:-3]) if len(row) > 5 else "",
                           "order": row[-3] if len(row) > 5 else "",
                           "plant": row[-2] if len(row) > 5 else "",
                           "manager": row[-1] if len(row) > 5 else ""}
    i = section("Device Type")
    if i is not None:
        for l in lines[i + 2:]:
            if not l.strip():
                break
            m = re.match(r"(.{13})\s*(\d+)", l)
            if m:
                out["deviceTypes"].append({"type": m.group(1).strip(), "count": int(m.group(2))})
    # software versions
    grp = ""
    for idx, l in enumerate(lines):
        if l.startswith("Name ") and "Version" in l:
            for l2 in lines[idx + 2:]:
                if not l2.strip() or l2.startswith("System-Number"):
                    break
                parts = l2.split()
                if len(parts) == 1:
                    grp = parts[0]
                    continue
                if len(parts) >= 3 and re.match(r"(?i)var", parts[1]):
                    out["software"].append({"group": grp, "name": parts[0], "var": parts[1],
                                            "ed": parts[2], "date": parts[-1]})
    i = section("System-Number")
    if i is not None:
        for l in lines[i + 2:]:
            if l.startswith("Device "):
                break
            m = re.match(r"\s+(\d{3})\s+(.+?)\s{2,}", l + "  ")
            if m:
                out["systems"].append({"no": int(m.group(1)), "name": m.group(2).strip()})
    i = section("Device          Devicetype")
    if i is not None:
        cur = None
        for l in lines[i + 2:]:
            if l.startswith("Device ") or l.startswith("Bus-Name"):
                break
            if not l.strip():
                continue
            dev = l[:16].strip()
            typ = l[16:30].strip()
            loc = l[30:52].strip()
            part = l[52:].strip()
            if dev:
                cur = {"id": dev, "type": typ, "cards": []}
                out["devices"].append(cur)
            if cur is not None:
                cur["cards"].append({"loc": loc, "part": part})
    i = section("Device          Nr   Protokoll")
    if i is not None:
        for l in lines[i + 2:]:
            if not l.strip():
                break
            p = l.split()
            out["serial"].append({"id": p[0], "proto": l[21:38].strip(),
                                  "baud": num(p[-1])})
    i = section("Bus-Name")
    if i is not None:
        bus = None
        for l in lines[i + 2:]:
            if not l.strip():
                bus = None
                continue
            p = l.split()
            if not l.startswith(" "):
                bus = {"name": p[0], "type": p[3] if len(p) > 3 else "",
                       "redundant": (p[4] == "J") if len(p) > 4 else False,
                       "baud": num(p[6]) if len(p) > 6 else None,
                       "members": [{"id": p[1], "node": num(p[2])}]}
                out["buses"].append(bus)
            elif bus is not None and len(p) >= 2:
                bus["members"].append({"id": p[0], "node": num(p[1])})
    return out


def parse_ov_index(path):
    """PV -> source node / CAN id / scaling, from OV_Index.xml"""
    res = {}
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        return res, {}
    proj = {}
    pi = root.find("Proj_Info")
    if pi is not None:
        proj = {c.tag: (c.text or "") for c in pi}
    for pv in root.iter("PV"):
        d = {c.tag: (c.text or "") for c in pv}
        res["PV" + d.get("PVNr", "")] = {
            "node": num(d.get("Source_Node")), "can": num(d.get("CAN_ID")),
            "mux": num(d.get("MUX")), "bus": d.get("Bus_Name", ""),
            "scale": num(d.get("Skal_Faktor"))}
    return res, proj


def parse_paralist(path):
    devs, cur = [], None
    for row in csv.reader(io.StringIO(read_text(path)), delimiter=";"):
        if not row:
            continue
        if row[0].startswith("Device"):
            cur = {"device": row[0].split(" ", 1)[1].strip(), "params": []}
            devs.append(cur)
            continue
        if cur is None or not row[0].strip().isdigit():
            continue
        cur["params"].append({"no": int(row[0]), "val": num(row[2], row[2]),
                              "u": row[3].strip(), "n": row[4].strip(),
                              "d": (row[5].strip() if len(row) > 5 else "")})
    return devs


def parse_kv_ini(path):
    data, sect = {}, None
    for l in read_text(path).splitlines():
        l = l.strip()
        if l.startswith("[") and l.endswith("]"):
            sect = l[1:-1]
            data[sect] = {}
        elif "=" in l and sect:
            k, v = l.split("=", 1)
            data[sect][k.strip()] = v.strip()
    return data


def parse_recipe_csv(path):
    """InTouch recipe csv -> list of columns (dict per recipe)"""
    rows = list(csv.reader(io.StringIO(read_text(path)), delimiter=";"))
    if len(rows) < 3:
        return []
    names = rows[1]
    cols = []
    for ci in range(3, len(names)):
        d = {"id": names[ci]}
        for r in rows[2:]:
            if len(r) > ci:
                d[r[0]] = r[ci]
        cols.append(d)
    return cols


# --------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--a", required=True, help="A package (folder or .exe)")
    ap.add_argument("--c", required=True, help="C package (folder or .exe)")
    ap.add_argument("--name", default="", help="ship / vessel name shown in the app")
    ap.add_argument("-o", "--out", default="ship.json")
    args = ap.parse_args()

    work = tempfile.mkdtemp(prefix="acp_")
    A = unpack_if_needed(args.a, work, "A")
    C = unpack_if_needed(args.c, work, "C")

    db = find(A, "db.csv")
    if not db:
        sys.exit("db.csv not found in the A package")
    signals, groups, access = parse_tag_db(db)

    ov, proj = ({}, {})
    if find(C, "OV_Index.xml"):
        ov, proj = parse_ov_index(find(C, "OV_Index.xml"))
    for s in signals:
        o = ov.get(s["tag"]) or ov.get("PV" + s["tag"][2:])
        if o:
            s["node"] = o["node"]
            s["can"] = o["can"]
            s["bus"] = o["bus"]

    plant = parse_plant(find(C, "Plant.txt")) if find(C, "Plant.txt") else {}

    # CAN process bus nodes (PBN)
    nodes = []
    crc = parse_kv_ini(find(C, "MCS5_CRC.TXT")) if find(C, "MCS5_CRC.TXT") else {}
    pbbus = next((b for b in plant.get("buses", []) if b["name"] == "PB"), None)
    devtype = {d["id"]: d["type"] for d in plant.get("devices", [])}
    if pbbus:
        for m in pbbus["members"]:
            n = m["node"]
            t = devtype.get(m["id"], "")
            label = {"MCU": "MCU", "PPS": "PPS", "CPS": "CPS"}.get(t, t)
            nodes.append({"pbn": n, "id": m["id"], "type": t,
                          "name": f"{label}{n:02d}",
                          "crc": crc.get("PBN-CRC", {}).get(f"{n:02d}"),
                          "blocks": num(crc.get("PBN-Block", {}).get(f"{n:02d}")),
                          "date": crc.get("PBN-Datum", {}).get(f"{n:02d}")})

    params = parse_paralist(find(C, "ParaList.csv")) if find(C, "ParaList.csv") else []
    tanks = parse_recipe_csv(find(A, "PR_Tank.csv")) if find(A, "PR_Tank.csv") else []
    tanks = [{"tag": t.get("Tagname Ausg."), "name": t.get("Text Ausgang"),
              "raw": t.get("Tagname Eing."), "level": t.get("Tagname aktuelle Höhe") or t.get("Tagname aktuelle Höhe")}
             for t in tanks]

    # engine / propulsion configuration (BR4000.INI) and project variables incl. limits (BVCONFVAR.INI)
    engines, cfg = {"n": None, "list": []}, {}
    if find(A, "BR4000.INI"):
        ini = parse_kv_ini(find(A, "BR4000.INI"))
        for k, v in ini.items():
            m = re.match(r"Engine (\d+)$", k)
            if m:
                n = m.group(1)
                g = lambda key, d=None: num(v.get("K%s%s" % (n, key)), d)
                engines["list"].append({"no": int(n), "cylinders": g("Nr_Cylinder"), "series": g("NumMotor"),
                                        "emu": g("EMU"), "exhaust": g("Exhaust"), "splashOil": g("Bear_SplashOil"),
                                        "gcu": g("GCU_PGCO"), "shaftSpeed": g("GMU_ShaftSpeed"), "fpp": g("FPP"), "cpp": g("CPP")})
        engines["n"] = num(ini.get("Propulsion", {}).get("K_Nr_AnzE"))
        engines["fuelUnits"] = {"litre": num(ini.get("Fuel Consumption", {}).get("K_Visible_lit")),
                                "galUS": num(ini.get("Fuel Consumption", {}).get("K_Visible_gal_US")),
                                "galIMP": num(ini.get("Fuel Consumption", {}).get("K_Visible_gal_IMP"))}
    if find(A, "BVCONFVAR.INI"):
        cfg = parse_kv_ini(find(A, "BVCONFVAR.INI"))
    auftrag = parse_kv_ini(find(A, "AUFTRAG.INI")) if find(A, "AUFTRAG.INI") else {}
    pbn = parse_kv_ini(find(A, "PbNodes.ini")) if find(A, "PbNodes.ini") else {}

    out = {
        "format": "mcs5-trainer/1",
        "project": {
            "name": args.name or plant.get("info", {}).get("tpName", "MCS-5 plant"),
            "order": proj.get("Project_Number") or auftrag.get("PU", {}).get("Auftrag"),
            "plant": proj.get("Anlagen_Name") or plant.get("info", {}).get("plant"),
            "puNumber": proj.get("PU_Sachnummer"),
            "intouchVer": auftrag.get("InTouch", {}).get("InTouch_Vers"),
            "canBaud": num(crc.get("Misc", {}).get("PB_Bus_Baudrate")),
            "busType": crc.get("Misc", {}).get("PB_Bus_Typ"),
            "mpc": crc.get("Misc", {}).get("MPC"),
            "mpcEd": crc.get("Misc", {}).get("ED"),
            "generated": crc.get("General", {}).get("Date"),
        },
        "pbNodesIni": pbn,
        "nodes": nodes,
        "deviceTypes": plant.get("deviceTypes", []),
        "software": plant.get("software", []),
        "systems": plant.get("systems", []),
        "devices": plant.get("devices", []),
        "serial": plant.get("serial", []),
        "buses": plant.get("buses", []),
        "engines": engines,
        "config": cfg,
        "alarmGroups": groups,
        "signals": signals,
        "params": params,
        "tanks": tanks,
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    na = sum(1 for s in signals if s["t"] != "D")
    nd = sum(1 for s in signals if s["t"] == "D")
    nal = sum(1 for s in signals if s.get("pri"))
    print(f"wrote {args.out}: {len(nodes)} PB nodes, {len(out['devices'])} devices, "
          f"{len(out['buses'])} buses, {na} analog + {nd} binary signals ({nal} alarms), "
          f"{sum(len(p['params']) for p in params)} parameters")


if __name__ == "__main__":
    main()
