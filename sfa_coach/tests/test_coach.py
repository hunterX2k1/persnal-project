import csv

import pytest

from sfa_coach.cli import main
from sfa_coach.coach import suggest
from sfa_coach.framedata import Character, Move, Phase, character_from_dict
from sfa_coach.modes import check_live_mode
from sfa_coach.timeline import analyze, load_timeline

# Made-up numbers for tests only.
JAB = Move("jab", startup=5, active=2, recovery=8, reach=1.0, damage=4)
SWEEP = Move("sweep", startup=12, active=3, recovery=25, reach=2.0, damage=12)
SLAM = Move("slam", startup=20, active=4, recovery=30, reach=1.5, damage=25, tags=("unblockable",))
ME = Character("Me", {m.name: m for m in (JAB, SWEEP)})
OPP = Character("Opp", {m.name: m for m in (JAB, SWEEP, SLAM)})


def test_phases():
    assert SWEEP.phase_at(0) is Phase.STARTUP
    assert SWEEP.phase_at(12) is Phase.ACTIVE
    assert SWEEP.phase_at(15) is Phase.RECOVERY
    assert SWEEP.phase_at(40) is Phase.DONE


def test_interrupt_slow_startup():
    top = suggest(ME, SWEEP, frame=0, distance=0.8)[0]
    assert (top.action, top.move) == ("interrupt", "jab")


def test_block_when_too_late_to_interrupt():
    acts = [s.action for s in suggest(ME, SWEEP, frame=10, distance=0.8)]
    assert acts == ["block"]


def test_unblockable_means_back_off():
    acts = [s.action for s in suggest(ME, SLAM, frame=21, distance=1.0)]
    assert acts == ["back off"]


def test_punish_whiffed_sweep_prefers_damage():
    opts = suggest(ME, SWEEP, frame=15, distance=1.8)
    assert [s.move for s in opts] == ["sweep"]  # jab doesn't reach 1.8
    opts = suggest(ME, SWEEP, frame=15, distance=0.5)
    assert [s.move for s in opts] == ["sweep", "jab"]


def test_no_punish_on_fast_recovery():
    assert suggest(ME, JAB, frame=12, distance=0.5)[0].action == "wait"  # 3f left


def test_bad_frame_data_rejected():
    with pytest.raises(ValueError):
        character_from_dict({"name": "X", "moves": [
            {"name": "a", "startup": 1, "active": 0, "recovery": 1, "reach": 1}]})


def test_live_modes():
    check_live_mode("training")
    check_live_mode("friend")
    for mode in ("pvp1v1", "pvp3v3"):
        with pytest.raises(PermissionError):
            check_live_mode(mode)
    with pytest.raises(ValueError):
        check_live_mode("ranked")


def _write_timeline(path, rows):
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["frame", "actor", "move", "result", "distance"])
        w.writerows(rows)


def test_replay_analysis(tmp_path):
    tl = tmp_path / "match.csv"
    _write_timeline(tl, [
        (100, "opp", "sweep", "whiff", 1.2),   # missed punish: no reply
        (300, "opp", "sweep", "whiff", 1.2),
        (318, "me", "sweep", "hit", 1.2),      # good punish
        (500, "me", "sweep", "block", 0.9),
        (520, "opp", "jab", "hit", 0.9),       # you got punished
    ])
    kinds = [f.kind for f in analyze(load_timeline(tl), ME, OPP)]
    assert kinds == ["missed punish", "good punish", "got punished"]


def test_cli_blocks_live_help_in_pvp(tmp_path):
    with pytest.raises(SystemExit) as e:
        main(["--data", str(tmp_path), "ask", "--me", "a", "--opp", "b",
              "--move", "x", "--frame", "0", "--distance", "1", "--mode", "pvp1v1"])
    assert "not available" in str(e.value)
