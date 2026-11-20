# sfa-coach

A study and practice tool for a 1v1 fighting game: frame-data lookups, replay
analysis, and "what are my options here?" suggestions.

| Mode | Live suggestions | Replay report |
|---|---|---|
| Training (vs AI) | yes | yes |
| Friend match (both players agree) | yes | yes |
| 1v1 PvP (ranked) | no | yes |
| 3v3 PvP | no | yes |

## Setup

```bash
pip install -e ".[video,dev]"   # video = OpenCV, needed only for `label`
```

## 1. Fill in frame data

Copy `data/characters/TEMPLATE.json` to `data/characters/<Name>.json`, one file
per character, and enter each move's frames (`startup`, `active`, `recovery`),
`reach`, `damage`, `on_block`, and `tags` (`unblockable`, …). No real game
numbers ship with this repo. Measure them from replays with the label tool by
stepping frame by frame. Use one distance unit consistently, for example
character widths.

## 2. Record and label a match

Play a saved match in the game's replay viewer, screen-record it, copy the
video to your PC, then run:

```bash
sfa-coach label match.mp4 -o match.csv
```

Step through frames (`,` `.`), and press `o` or `m` on the frame where an
opponent or own move starts. Then type the move, its result (hit, block or
whiff), and the distance.

## 3. Get the report

```bash
sfa-coach report match.csv --me Alice --opp Bob
```

```
[0:01.67] MISSED PUNISH: opponent's sweep (whiff) left a 25f window
      -> sweep: opponent stuck for 25f; sweep comes out in 12f
[0:05.00] GOT PUNISHED: your sweep (block) left a 25f window; opponent hit you with jab
```

## 4. Ask during practice

```bash
sfa-coach ask --me Alice --opp Bob --move sweep --frame 16 --distance 0.8 --mode training
```

## Roadmap

- Automatic move recognition trained on labelled replays, to replace manual labels.
- Live practice coach (training and friend modes) using that model.

## Measuring a hero (example: Itu)

1. Go to training mode as Itu with the dummy standing still. Record at 60 fps.
2. Perform each move 2–3 times, with a pause between moves. Cover:
   - every button (weapon, kick) in each direction: neutral, forward, back, down,
     plus the jump versions,
   - combo strings, one at a time,
   - Spacetime Slash and Time Manipulation,
   - also record a few moves close to the dummy so hits connect, to judge reach.
3. Run the command below. For each move, step frame by frame and press `1`
   (move starts), `2` (first hit frame), `3` (last hit frame) and `4` (can act
   again). Then type the move name. The command writes `data/characters/Itu.json`.

```bash
sfa-coach measure itu_training.mp4 --hero Itu
```
