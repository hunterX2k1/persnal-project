# Ridgeline Drift

A 2.5D drift game on a winding mountain road with a cliff drop on both sides. The car keeps accelerating. Hold to drift through the bends; run wide or over-rotate and you go over the edge.

## Play

Open `index.html` in any modern browser. It's a single file with no build step.

**Auto steering (default):** the car follows the road by itself and reads the bend ahead. You only control the drift:

- **Hold anywhere / Space / ↑ / W**: drift. The longer you hold, the more it slides and the harder it turns into the bend.
- **Let go**: grip.
- Grip alone can only turn so tightly, and less so the faster you go, so tight bends need a drift. Too little and you run wide; too much, or holding on a straight, swings you off the inside.

**Manual steering** (Settings → Steering): hold the left or right half of the screen, or ← → / A D, to steer and drift yourself.

**M** mutes, **C** opens Settings.

## The road

- Hills, dips and crests (altitude and gradient shown under the score). Climbs slow you down, descents speed you up.
- Sharp crests at speed launch the car: airtime earns AIR points, and you can't steer in the air.
- Grassy, rocky mountainside with pine trees falls away on both sides down to the valley.

## Camera

Tap **CAM** (or press **C**) to open the camera panel. The game pauses while it's open.

- **Height**: from close to very high.
- **Angle**: from a low chase view to straight overhead.
- Presets: Low, Chase (default), High, Overhead. Your choice is remembered on this device.

## Obstacles

| Obstacle | What happens |
|---|---|
| Slow hay truck | Honks as you close in; overtake it, or hitting it bounces you off, cuts your speed and loses unbanked drift points |
| Traffic cones | Knock them flying; slows you a little |
| Oil slick | Almost no grip for a moment |
| Gold coins | Lined along the inside of bends: +20, +40, +60… for collecting in a row |

## Garage

Classic muscle cars plus one JDM drift coupe, inspired by real shapes (no real names or badges). Each car is rendered once per view angle into a high-resolution sprite, so edges stay smooth and sharp.

| Car | Inspired by | Details | Engine sound | Unlock |
|---|---|---|---|---|
| Stallion 67 | 1967 fastback pony car | Long hood, fastback roof, side scoops, tri-bar tail lights, twin stripes | Lumpy small-block V8 with crackle | free |
| Hornet 69 | 1969 pony coupe | Coke-bottle hips, cowl hood, hood and deck stripes, ducktail spoiler | Higher-revving V8 | 600 |
| Brawler 69 | 1969 big-block coupe | Long and wide, full-width hidden-headlight grille, vinyl roof, tail stripe | Deep, slow big-block V8, 3 long gears | 1400 |
| Outlaw 77 | Late-70s T-top pony car | Shaker scoop, gold pinstripes and hood chevron, T-tops, wrap spoiler | Heavy V8 with gear whine | 2600 |
| Kaze 95 | Mid-90s turbo drift coupe | Wide body, pop-up lights, carbon hood, big wing, twin round tail lights | High-revving turbo four with whistle and blow-off valve, 6 gears | 4000 |

Engines are synthesized: a firing-pulse engine voice with gated exhaust noise, rev limiter bounce, wheelspin revs while drifting, shift blips, crackle, and a three-tone tyre screech with a chirp when a drift starts.

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
