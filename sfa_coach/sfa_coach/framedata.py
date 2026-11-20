"""Frame-data database: characters, their moves, and move phases.

Frame data is loaded from JSON files you fill in yourself (see
data/characters/TEMPLATE.json). Nothing here ships real SFA numbers.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path


class Phase(str, Enum):
    STARTUP = "startup"
    ACTIVE = "active"
    RECOVERY = "recovery"
    DONE = "done"


@dataclass(frozen=True)
class Move:
    name: str
    startup: int  # frames before the hitbox comes out
    active: int  # frames the hitbox is out
    recovery: int  # frames after the hitbox until the fighter can act again
    reach: float  # max distance at which the move connects (your own units)
    damage: float = 0.0
    on_block: int = 0  # extra recovery (+) or advantage (-) when blocked
    tags: tuple[str, ...] = ()  # e.g. "unblockable", "low", "launcher"

    @property
    def total(self) -> int:
        return self.startup + self.active + self.recovery

    def phase_at(self, frame: int) -> Phase:
        """Phase of the move `frame` frames after it started (0-based)."""
        if frame < 0:
            raise ValueError("frame must be >= 0")
        if frame < self.startup:
            return Phase.STARTUP
        if frame < self.startup + self.active:
            return Phase.ACTIVE
        if frame < self.total:
            return Phase.RECOVERY
        return Phase.DONE

    def frames_left(self, frame: int, blocked: bool = False) -> int:
        """Frames until this fighter can act again."""
        extra = self.on_block if blocked else 0
        return max(0, self.total + extra - frame)


@dataclass
class Character:
    name: str
    moves: dict[str, Move] = field(default_factory=dict)

    def move(self, name: str) -> Move:
        try:
            return self.moves[name]
        except KeyError:
            known = ", ".join(sorted(self.moves)) or "none"
            raise KeyError(f"{self.name} has no move {name!r} (known: {known})") from None


def character_from_dict(data: dict) -> Character:
    moves = {}
    for m in data["moves"]:
        move = Move(
            name=m["name"],
            startup=int(m["startup"]),
            active=int(m["active"]),
            recovery=int(m["recovery"]),
            reach=float(m["reach"]),
            damage=float(m.get("damage", 0)),
            on_block=int(m.get("on_block", 0)),
            tags=tuple(m.get("tags", ())),
        )
        if min(move.startup, move.active, move.recovery) < 0 or move.active == 0:
            raise ValueError(f"{data['name']}/{move.name}: invalid frame counts")
        moves[move.name] = move
    return Character(name=data["name"], moves=moves)


def load_character(path: str | Path) -> Character:
    with open(path, encoding="utf-8") as f:
        return character_from_dict(json.load(f))


def load_roster(directory: str | Path) -> dict[str, Character]:
    """Load every *.json in `directory` (except TEMPLATE.json) keyed by name."""
    roster = {}
    for path in sorted(Path(directory).glob("*.json")):
        if path.name.upper() == "TEMPLATE.JSON":
            continue
        char = load_character(path)
        roster[char.name] = char
    return roster
