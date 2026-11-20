import json

import pytest

from sfa_coach.framedata import load_character
from sfa_coach.measure import frames_from_marks, save_move


def test_frames_from_marks():
    # starts at 100, hits on 110-112, can act again at 130
    assert frames_from_marks(100, 110, 112, 130) == {"startup": 10, "active": 3, "recovery": 17}


def test_bad_marks():
    with pytest.raises(ValueError):
        frames_from_marks(100, 95, 112, 130)


def test_save_move_roundtrip(tmp_path):
    p = tmp_path / "Itu.json"
    base = {"reach": 1.0, "damage": 5, "on_block": 0, "tags": []}
    save_move(p, "Itu", {"name": "b", "startup": 9, "active": 2, "recovery": 12, **base})
    save_move(p, "Itu", {"name": "a", "startup": 7, "active": 2, "recovery": 10, **base})
    save_move(p, "Itu", {"name": "b", "startup": 8, "active": 2, "recovery": 12, **base})
    data = json.loads(p.read_text())
    assert [m["name"] for m in data["moves"]] == ["a", "b"]
    assert load_character(p).move("b").startup == 8
