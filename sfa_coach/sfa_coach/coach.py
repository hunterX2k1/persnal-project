"""The suggestion engine: given what the opponent is doing, list your options.

Pure logic, no video. Used by both the replay report and the practice coach.
"""

from __future__ import annotations

from dataclasses import dataclass

from .framedata import Character, Move, Phase


@dataclass(frozen=True)
class Suggestion:
    action: str  # "punish", "interrupt", "block", "back off", "wait"
    move: str | None
    reason: str
    score: float  # higher is better; used only for ordering


def _in_range(move: Move, distance: float) -> bool:
    return distance <= move.reach


def suggest(
    me: Character,
    opp_move: Move,
    frame: int,
    distance: float,
    blocked: bool = False,
    limit: int = 3,
) -> list[Suggestion]:
    """Rank what `me` can do when the opponent is `frame` frames into `opp_move`.

    `distance` is the gap between fighters in the same units as `reach`.
    `blocked` means you blocked the opponent's move, so its on_block applies.
    """
    phase = opp_move.phase_at(frame)
    out: list[Suggestion] = []

    if phase is Phase.STARTUP:
        window = opp_move.startup - frame
        for m in me.moves.values():
            if m.startup < window and _in_range(m, distance):
                out.append(Suggestion(
                    "interrupt", m.name,
                    f"{opp_move.name} hits in {window}f; {m.name} comes out in {m.startup}f",
                    100 + m.damage - m.startup,
                ))
        threatened = distance <= opp_move.reach
        if "unblockable" in opp_move.tags and threatened:
            out.append(Suggestion("back off", None, f"{opp_move.name} is unblockable", 90))
        elif threatened:
            out.append(Suggestion("block", None, f"{opp_move.name} will reach you", 50))
        else:
            out.append(Suggestion("wait", None, f"{opp_move.name} is out of range", 40))

    elif phase is Phase.ACTIVE:
        if distance > opp_move.reach:
            out.append(Suggestion("wait", None, "it will whiff; get ready to punish", 60))
        elif "unblockable" in opp_move.tags:
            out.append(Suggestion("back off", None, f"{opp_move.name} is unblockable", 90))
        else:
            out.append(Suggestion("block", None, f"{opp_move.name} hitbox is out", 80))

    elif phase in (Phase.RECOVERY, Phase.DONE):
        window = opp_move.frames_left(frame, blocked=blocked)
        for m in me.moves.values():
            if m.startup < window and _in_range(m, distance):
                out.append(Suggestion(
                    "punish", m.name,
                    f"opponent stuck for {window}f; {m.name} comes out in {m.startup}f",
                    200 + m.damage,
                ))
        if not out:
            out.append(Suggestion(
                "wait", None,
                f"no punish fits ({window}f left, distance {distance:g})", 10,
            ))

    out.sort(key=lambda s: s.score, reverse=True)
    return out[:limit]
