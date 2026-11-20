"""Frame-by-frame labelling tool for recorded replays (needs opencv-python).

Keys:
  space  play / pause          ,  .   step back / forward one frame
  [  ]   jump back / forward 1s
  o      label an OPPONENT move starting at this frame
  m      label one of MY moves starting at this frame
  u      undo last label        q      save and quit
After pressing o/m, type the move, result and distance in the terminal.
"""

from __future__ import annotations

import csv
from pathlib import Path

FIELDS = ["frame", "actor", "move", "result", "distance"]


def _ask(actor: str, frame: int, last_distance: str) -> dict | None:
    move = input(f"[{actor} @ frame {frame}] move name (blank = cancel): ").strip()
    if not move:
        return None
    result = ""
    while result not in {"hit", "block", "whiff"}:
        result = input("  result (hit/block/whiff): ").strip().lower()
    dist = input(f"  distance [{last_distance}]: ").strip() or last_distance
    return {"frame": frame, "actor": actor, "move": move, "result": result, "distance": dist}


def run(video: str, out: str) -> None:
    import cv2  # imported lazily so the rest of the package works without OpenCV

    cap = cv2.VideoCapture(video)
    if not cap.isOpened():
        raise SystemExit(f"cannot open {video}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 60.0
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))

    rows: list[dict] = []
    if Path(out).exists():
        with open(out, newline="", encoding="utf-8") as f:
            rows = list(csv.DictReader(f))
    last_distance = rows[-1]["distance"] if rows else "0"

    frame_no, playing = 0, False
    cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
    ok, img = cap.read()
    while ok:
        shown = img.copy()
        cv2.putText(shown, f"frame {frame_no}/{total}  labels {len(rows)}",
                    (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 255), 2)
        cv2.imshow("sfa-coach label", shown)
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
        elif key == ord("u") and rows:
            print("removed", rows.pop())
        elif key in (ord("o"), ord("m")):
            playing = False
            row = _ask("opp" if key == ord("o") else "me", frame_no, last_distance)
            if row:
                rows.append(row)
                last_distance = row["distance"]

        if target != frame_no:
            if target != frame_no + 1:
                cap.set(cv2.CAP_PROP_POS_FRAMES, target)
            ok, nxt = cap.read()
            if ok:
                img, frame_no = nxt, target
            else:
                playing, ok = False, True  # stay on last frame

    cap.release()
    cv2.destroyAllWindows()
    rows.sort(key=lambda r: int(r["frame"]))
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS)
        w.writeheader()
        w.writerows(rows)
    print(f"saved {len(rows)} labels to {out}")
