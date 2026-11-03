# Ridgeline Drift

A 2.5D drift game on a winding mountain road with a cliff drop on both sides. The car keeps accelerating. Hold to drift through the bends; run wide or over-rotate and you go over the edge.

## Play

Open `index.html` in any modern browser. It's a single file with no build step.

- **Touch and hold anywhere**: the further left or right of centre your finger is, the harder you steer and drift. Slide your finger to adjust mid-corner. Let go and the car straightens itself.
- **← / A and → / D**: full-lock steering on a keyboard
- Counter-steering (pushing the other way mid-slide) catches the slide faster.
- **M**: mute or unmute

## Look

- 2.5D camera that follows behind the car and turns with it. Cliff walls drop about 28 m to a forest valley with a river.
- Miniature retro cars built from stacked slices, so they have real height and shading.
- Textured asphalt with patches and cracks, double yellow centre line, gravel shoulder, reflector posts, and red/white kerbs on tight bends.
- Effects: tyre smoke (gold during big combos), dust on the gravel, sparks, drifting clouds and their shadows, distance haze, vignette, wind streaks at speed, brake-light glow, and slow motion plus a dust cloud when you go over.

## The road

- Hills, dips and crests (altitude and gradient shown under the score). Climbs slow you down, descents speed you up.
- Sharp crests at speed launch the car: airtime earns AIR points, and you can't steer in the air.
- Grassy, rocky mountainside with pine trees falls away on both sides down to the valley.

## Obstacles

| Obstacle | What happens |
|---|---|
| Fallen boulder | Bounces you off, cuts your speed and loses unbanked drift points |
| Slow hay truck | Honks as you close in; overtake it or crash into it like a boulder |
| Traffic cones | Knock them flying; slows you a little |
| Oil slick | Almost no grip for a moment |
| Gold coins | Lined along the inside of bends: +20, +40, +60… for collecting in a row |

## Garage

Miniature models inspired by classic car shapes (no real names or badges):

| Car | Inspired by | Details | Engine sound | Unlock |
|---|---|---|---|---|
| Rally 72 | 1970s rally saloon | Four spotlights, flared arches, mud flaps, roof roundel, stripes | Rev-happy four with exhaust pops | free |
| Pocket | 1960s British mini | Wheels at the corners, white roof, chrome grille, bonnet stripes, twin fuel caps | Buzzy small four with gear whine | 600 |
| Bubble | 1950s air-cooled bug | Separate round fenders, running boards, domed roof, louvred engine lid | Chugging flat-four | 1400 |
| Comet GT | Late-60s fastback muscle coupe | Long hood with scoop, sloping rear glass, tri-bar tail lights, twin stripes, dual exhausts | Lumpy V8 burble with crackle | 2600 |
| Camper | 1960s split-window bus | Two-tone with V-front, split windscreen, roof rack and surfboard | Wheezy flat-four with gear whine | 4000 |

Each car cranks and starts before the countdown, has its own gearing, and handles differently.

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
