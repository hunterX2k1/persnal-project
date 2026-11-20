"""Measure a move's frame data from a training-mode recording (needs opencv-python).

For each move, step to these four frames and press the key:
  1  first frame the move starts (input reacts)
  2  first frame the hit can connect (weapon/limb at full extension, hit spark)
  3  last frame the hit can connect
  4  first frame the fighter can act again (back to idle / next move possible)
Then type the move name, reach and damage in the terminal; it is saved
straight into data/characters/<Hero>.json.

Other keys: space play/pause, , . step a frame, [ ] jump 1s, q quit.
"""

from __future__ import annotations

import json
from pathlib import Path


def frames_from_marks(start: int, hit_first: int, hit_last: int, can_act: int) -> dict:
    """Convert four marked frames into startup/active/recovery counts."""
    if not start <= hit_first <= hit_last < can_act:
        raise ValueError(
            f"marks must satisfy start <= hit_first <= hit_last < can_act, "
            f"got {start}, {hit_first}, {hit_last}, {can_act}"
        )
    return {
        "startup": hit_first - start,
        "active": hit_last - hit_first + 1,
        "recovery": can_act - hit_last - 1,
    }


def save_move(path: str | Path, hero: str, move: dict) -> None:
    """Insert or replace `move` (by name) in the hero's frame-data file."""
    path = Path(path)
    data = {"name": hero, "moves": []}
    if path.exists():
        data = json.loads(path.read_text(encoding="utf-8"))
    data["moves"] = [m for m in data["moves"] if m["name"] != move["name"]] + [move]
    data["moves"].sort(key=lambda m: m["name"])
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def run(video: str, hero: str, data_dir: str) -> None:
    import cv2

    cap = cv2.VideoCapture(video)
    if not cap.isOpened():
        raise SystemExit(f"cannot open {video}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 60.0
    out = Path(data_dir) / f"{hero}.json"
    names = {1: "start", 2: "hit first", 3: "hit last", 4: "can act"}
    marks: dict[int, int] = {}

    frame_no, playing = 0, False
    ok, img = cap.read()
    while ok:
        shown = img.copy()
        status = "  ".join(f"{k}:{names[k]}={marks.get(k, '-')}" for k in names)
        cv2.putText(shown, f"frame {frame_no}", (10, 30),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 255), 2)
        cv2.putText(shown, status, (10, 60), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (0, 255, 255), 2)
        cv2.imshow(f"sfa-coach measure {hero}", shown)
        key = cv2.waitKey(int(1000 / fps) if playing else 0) & 0xFF

        target = frame_no + 1 if playing else frame_no
        if key == ord("q"):
            break
        elif key == ord(" "):
            playing = not playing
        elif key == ord("."):
            target = frame_no + 1
        elif key == ord(","):
            target = max(0, frame_no - 1)
        elif key == ord("]"):
            target = frame_no + int(fps)
        elif key == ord("["):
            target = max(0, frame_no - int(fps))
        elif key in (ord("1"), ord("2"), ord("3"), ord("4")):
            playing = False
            marks[key - ord("0")] = frame_no
            if len(marks) == 4:
                try:
                    counts = frames_from_marks(marks[1], marks[2], marks[3], marks[4])
                except ValueError as e:
                    print(e, "- re-mark the frames")
                else:
                    name = input(f"{counts} move name (blank = discard): ").strip()
                    if name:
                        reach = input("  reach (e.g. 1.0 = one body width): ").strip() or "1.0"
                        dmg = input("  damage shown [0]: ").strip() or "0"
                        tags = input("  tags, comma separated (unblockable, low, ...): ").strip()
                        save_move(out, hero, {
                            "name": name, **counts, "reach": float(reach),
                            "damage": float(dmg), "on_block": 0,
                            "tags": [t.strip() for t in tags.split(",") if t.strip()],
                        })
                        print(f"  saved {name} to {out}")
                marks.clear()

        if target != frame_no:
            if target != frame_no + 1:
                cap.set(cv2.CAP_PROP_POS_FRAMES, target)
            ok2, nxt = cap.read()
            if ok2:
                img, frame_no = nxt, target
            else:
                playing = False

    cap.release()
    cv2.destroyAllWindows()
