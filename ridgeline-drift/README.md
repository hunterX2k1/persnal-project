# Ridgeline Drift

A 2.5D drift game on a winding mountain road with a cliff drop on both sides. The car keeps accelerating. Hold to drift through the bends; run wide or over-rotate and you go over the edge.

## Play

Open `index.html` in any modern browser. It's a single file with no build step.

- **Hold left half of the screen / ← / A**: drift left
- **Hold right half / → / D**: drift right
- **M**: mute or unmute

## Look

- 2.5D camera that follows behind the car and turns with it. Cliff walls drop about 28 m to a forest valley with a river.
- Miniature retro cars built from stacked slices, so they have real height and shading.
- Textured asphalt with patches and cracks, double yellow centre line, gravel shoulder, reflector posts, and red/white kerbs on tight bends.
- Effects: tyre smoke (gold during big combos), dust on the gravel, sparks, drifting clouds and their shadows, distance haze, vignette, wind streaks at speed, brake-light glow, and slow motion plus a dust cloud when you go over.

## Garage

| Car | Unlock (best score) | Character |
|---|---|---|
| Rally 72 | free | Balanced |
| Pocket | 600 | Quick steering, grippy, slower |
| Bubble | 1400 | Slides easily, drift x1.1 |
| Comet GT | 2600 | Fastest, slow to turn, drift x1.2 |
| Camper | 4000 | Heavy and grippy, hard to drift, drift x1.3 |

## Scoring

- 1 point per metre driven.
- Drift points build while you slide, with a combo up to x5 for long drifts. Drifting close to the edge doubles them.
- Drift points only count once you straighten out. Go over the edge mid-drift and you lose them.
- Running onto the gravel shoulder slows you and throws dust: it is the last warning before the edge.
- The road narrows, the car gets faster and bends get tighter the further you go.

## Competition

- A shared leaderboard (top 10, plus your own position) when the game runs as a claude.ai artifact.
- Checkered lines on the road mark your best distance and the #1 run.
- The game-over screen says what went wrong (ran wide or over-rotated, on which bend), what to change, and how many points you need to pass the next player.

## Sound

All sounds are synthesized with the Web Audio API, with randomized pitch, timing and pan: a geared engine with shift blips, tyre squeal and rubber noise tied to slip angle, gravel near the edge, wind, falling and impact, and ambient gusts, hawks and pebbles.
