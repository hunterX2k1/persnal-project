"""Command line: sfa-coach {label,report,ask}."""

from __future__ import annotations

import argparse
import sys

from .coach import suggest
from .framedata import load_roster
from .modes import check_live_mode
from .timeline import analyze, format_report, load_timeline


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="sfa-coach")
    p.add_argument("--data", default="data/characters", help="frame-data directory")
    sub = p.add_subparsers(dest="cmd", required=True)

    lab = sub.add_parser("label", help="label moves in a recorded replay video")
    lab.add_argument("video")
    lab.add_argument("-o", "--out", required=True, help="timeline CSV to write")

    mea = sub.add_parser("measure", help="measure a hero's frame data from a training video")
    mea.add_argument("video")
    mea.add_argument("--hero", required=True)

    rep = sub.add_parser("report", help="analyse a labelled timeline")
    rep.add_argument("timeline")
    rep.add_argument("--me", required=True, help="your character")
    rep.add_argument("--opp", required=True, help="opponent character")
    rep.add_argument("--fps", type=float, default=60.0)

    ask = sub.add_parser("ask", help="what can I do against this move? (practice/study)")
    ask.add_argument("--me", required=True)
    ask.add_argument("--opp", required=True)
    ask.add_argument("--move", required=True, help="opponent's move")
    ask.add_argument("--frame", type=int, required=True, help="frames into the move")
    ask.add_argument("--distance", type=float, required=True)
    ask.add_argument("--blocked", action="store_true")
    ask.add_argument("--mode", default="training",
                     help="training | friend (live help is not offered in pvp1v1/pvp3v3)")

    args = p.parse_args(argv)

    if args.cmd == "label":
        from .label import run
        run(args.video, args.out)
        return 0
    if args.cmd == "measure":
        from .measure import run as measure_run
        measure_run(args.video, args.hero, args.data)
        return 0

    roster = load_roster(args.data)

    def char(name: str):
        if name not in roster:
            sys.exit(f"unknown character {name!r}; have: {', '.join(sorted(roster)) or 'none'}")
        return roster[name]

    if args.cmd == "report":
        findings = analyze(load_timeline(args.timeline), char(args.me), char(args.opp))
        print(format_report(findings, fps=args.fps))
    elif args.cmd == "ask":
        try:
            check_live_mode(args.mode)
        except (ValueError, PermissionError) as e:
            sys.exit(str(e))
        opp_move = char(args.opp).move(args.move)
        print(f"{args.move} is in {opp_move.phase_at(args.frame).value} at frame {args.frame}")
        for s in suggest(char(args.me), opp_move, args.frame, args.distance, args.blocked):
            print(f"  {s.action:9} {s.move or '':14} {s.reason}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
