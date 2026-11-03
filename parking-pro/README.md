# Parking Pro

A one-tap, high-score browser game. Your car rolls down a city street toward an open parking spot. Tap once to slam the brakes and stop inside the lines, as close to the center as you can.

## Play

Open `index.html` in any modern browser. It's a single file with no build step.

- **Tap / click / Space**: brake (one tap, no second chances)
- **M**: mute or unmute

## Scoring

| Result | How | Points |
|---|---|---|
| Perfect | Within 15 cm of the spot's center | 100 |
| Great | Close to center | 60 |
| Good | Inside the lines | 30 |
| Tight fit | Barely inside | 15 |

- Perfects and Greats in a row build a streak multiplier up to x5.
- **Forgiving mistakes** cost points but not the run: a gentle bump under 8 km/h, or sticking out 30 cm or less.
- **Run-ending mistakes**: a real crash, sticking out more than 30 cm, or stopping before the spot.

## Variety

- **Spot types**: Wet road (slides further, x1.5), Rush hour (faster, x2), Tight squeeze (smaller spot, x2), and a Golden spot every 5th spot (x3). Each is announced before you reach it.
- **Streets**: Downtown Dusk, Sunset Avenue and Midnight Market, changing every 4 spots.
- **Obstacles**: parked cars, vans, pickups, or cones.
- **Paints**: 7 car colors unlock as you park cars and land Perfects across runs.

## Leaderboard

When the game runs as a claude.ai artifact, a shared leaderboard shows the top 5 drivers and your position. The game-over screen tells you how many points you need to pass the next player.

## Fair failure

When a run ends, the street shows a ghost of the ideal stop, a green **Brake here** flag and a red **You braked** flag. The result card shows your brake timing in seconds (early or late) on a bar against the window that would have worked, plus the stopping distance at your speed.

## Sound

All sounds are synthesized with the Web Audio API. Each play gets random pitch, timing and stereo position, so it doesn't repeat. Sounds include the engine, tyre squeal, suspension clunk, handbrake ratchet, indicator ticks, crash variants with glass and car alarms, other drivers' horns, rain, and city ambience.

## Scale

1 game unit = 3.75 cm, so the car is 4.5 m long. Speeds are shown in km/h, and misses in cm or m.
