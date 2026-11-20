"""Which game modes each feature may be used in.

Live suggestions are only for modes where nobody is disadvantaged:
training (vs. AI) and friend matches where both players agreed.
Ranked 1v1 and 3v3 PvP get the replay coach only.
"""

LIVE_ALLOWED = {"training", "friend"}
REPLAY_ALLOWED = {"training", "friend", "pvp1v1", "pvp3v3"}
ALL_MODES = REPLAY_ALLOWED


def check_live_mode(mode: str) -> None:
    if mode not in ALL_MODES:
        raise ValueError(f"unknown mode {mode!r}; choose from {sorted(ALL_MODES)}")
    if mode not in LIVE_ALLOWED:
        raise PermissionError(
            f"live suggestions are not available in {mode}; "
            "record the match and use `sfa-coach report` afterwards"
        )
