"""Replay analysis: read a labelled match timeline and find missed chances.

Timeline CSV columns (one row per move started):
    frame,actor,move,result,distance
- frame:    video frame where the move started
- actor:    "me" or "opp"
- move:     move name as written in the character's frame-data file
- result:   "hit", "block" or "whiff"
- distance: gap between fighters when the move started (reach units)
"""

from __future__ import annotations

import csv
from dataclasses import dataclass
from pathlib import Path

from .coach import Suggestion, suggest
from .framedata import Character


@dataclass(frozen=True)
class Event:
    frame: int
    actor: str
    move: str
    result: str
    distance: float


@dataclass(frozen=True)
class Finding:
    event: Event
    kind: str  # "missed punish", "got punished", "good punish"
    detail: str
    options: tuple[Suggestion, ...] = ()


VALID_ACTORS = {"me", "opp"}
VALID_RESULTS = {"hit", "block", "whiff"}


def load_timeline(path: str | Path) -> list[Event]:
    events = []
    with open(path, newline="", encoding="utf-8") as f:
        for lineno, row in enumerate(csv.DictReader(f), start=2):
            ev = Event(
                frame=int(row["frame"]),
                actor=row["actor"].strip().lower(),
                move=row["move"].strip(),
                result=row["result"].strip().lower(),
                distance=float(row["distance"]),
            )
            if ev.actor not in VALID_ACTORS:
                raise ValueError(f"line {lineno}: actor must be me/opp, got {ev.actor!r}")
            if ev.result not in VALID_RESULTS:
                raise ValueError(f"line {lineno}: result must be hit/block/whiff, got {ev.result!r}")
            events.append(ev)
    events.sort(key=lambda e: e.frame)
    return events


def analyze(events: list[Event], me: Character, opp: Character) -> list[Finding]:
    findings: list[Finding] = []
    for i, ev in enumerate(events):
        if ev.result == "hit":
            continue
        actor_char = opp if ev.actor == "opp" else me
        move = actor_char.move(ev.move)
        blocked = ev.result == "block"
        # Earliest frame after the move's active window ends.
        recover_start = ev.frame + move.startup + move.active
        window = move.frames_left(move.startup + move.active, blocked=blocked)
        reply = next((e for e in events[i + 1:] if e.actor != ev.actor), None)
        punished = (
            reply is not None
            and reply.frame < recover_start + window
            and reply.result == "hit"
        )

        if ev.actor == "opp":
            options = tuple(
                s for s in suggest(me, move, move.startup + move.active, ev.distance, blocked)
                if s.action == "punish"
            )
            if punished:
                findings.append(Finding(ev, "good punish",
                                        f"you punished {ev.move} with {reply.move}"))
            elif options:
                findings.append(Finding(
                    ev, "missed punish",
                    f"opponent's {ev.move} ({ev.result}) left a {window}f window",
                    options,
                ))
        elif punished:
            findings.append(Finding(
                ev, "got punished",
                f"your {ev.move} ({ev.result}) left a {window}f window; "
                f"opponent hit you with {reply.move}",
            ))
    return findings


def format_report(findings: list[Finding], fps: float = 60.0) -> str:
    if not findings:
        return "No punish chances found in this timeline."
    counts: dict[str, int] = {}
    lines = []
    for f in findings:
        counts[f.kind] = counts.get(f.kind, 0) + 1
        t = f.event.frame / fps
        lines.append(f"[{int(t // 60)}:{t % 60:05.2f}] {f.kind.upper()}: {f.detail}")
        for s in f.options:
            lines.append(f"      -> {s.move}: {s.reason}")
    summary = ", ".join(f"{n} {k}" for k, n in sorted(counts.items()))
    return "\n".join(lines + ["", f"Summary: {summary}"])
