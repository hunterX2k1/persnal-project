# Parking Pro

A one-tap, high-score browser game. Your car rolls down a city street toward an open parking spot. Tap once to brake and stop inside the lines, as close to the center as you can.

## Play

Open `index.html` in any modern browser. It's a single file with no build step.

- **Tap / click / Space**: brake (one tap only, no second chances)
- **M**: mute or unmute

## Rules

| Result | How | Points |
|---|---|---|
| Perfect | Within 10 cm of the spot's center | 100 |
| Great | Close to center | 60 |
| Good | Inside the lines | 30 |
| Tight fit | Barely inside | 10 |

- A Perfect or Great builds your streak multiplier, up to x5. A Good or Tight fit resets it.
- The run ends if you hit the car (or cones) in front, stop with your bumper in the street, or stop before the spot.
- Each spot comes faster and tighter. From spot 5 on, some streets are wet, so the car takes longer to stop.
- Your best score is saved in the browser.

## Scale

1 game unit = 3.75 cm, so the car is 4.5 m long. Speeds are shown in km/h and misses in cm, both using this scale.
