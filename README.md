# Diag Helper — offline diagnosis helper for Android

A small, fully offline helper (no LLM, no internet permission). It answers **only from index cards** and lists the cards and source pages behind every answer. If nothing matches, it says **"Not in index"** instead of guessing.

## What's inside

| Part | Does |
|---|---|
| Card index | Searches cards by ID, tags and words (synonyms like "glow / lit / light" are understood) |
| Answerer | Builds the answer from the matched card + the cards it links to, then adds principle reminders and references |
| Circuit tracer | Turns a path like `24V -> F3 -> K12:13-14 -> X2:5 -> H5 -> X2:6 -> 0V` into an ordered check list |
| Calculators | Ohm's law + power, cable voltage drop, 4-20 mA scaling, unit conversion, maths |
| Diagram reader | Reads your own wiring-diagram PDFs (computer-made, searchable) and makes circuit + part cards automatically, with page numbers |
| Meaning search | A small offline donor model (MiniLM, 23 MB) understands your own wording, e.g. "module light not glowing" finds the lamp fault card. It only picks cards; answers still come from cards |
| Card editor | Add or edit your own cards on the phone, import/export as JSON |

Starter cards: 20 electrical principles (P-001…P-020), a **made-up** practice panel (lamp H5, relay K12, PLC output DO4) with circuit and fault cards, plus generic 4-20 mA and CAN bus fault cards.

## Build the APK from your phone (GitHub, free)

1. Create a free account on github.com and make a new **private** repository.
2. Upload all files from this zip, keeping the folders (on a phone, the GitHub mobile site's "Add file → Upload files" works best in Chrome's desktop-site mode).
3. The `.github/workflows/build-apk.yml` file builds automatically. Open the **Actions** tab, wait for the green tick (about 3-5 minutes).
4. Open the finished run, download **DiagHelper-apk**, unzip it, and install `app-debug.apk` (allow "install unknown apps" for your browser/files app).

Other ways: open the folder in an on-phone Android IDE app, or in Android Studio on any PC, and build `assembleDebug`.

## Using it

- Symptom: `H5 lamp not glowing`
- Part: `what does K12 do`
- Principle: `how does a relay work`
- Any path: `trace 24V -> F1 -> S1 -> H1 -> 0V`
- Ohm's law: `V=24 R=12` or `24V 5W`
- Cable drop: `drop L=20 A=1.5 I=5 V=24` (L = one-way metres, A = mm²)
- 4-20 mA: `4-20 12 0 10 bar` (or `4-20 v 5 0 10` for value → mA)
- Convert: `conv 5 bar psi`, `conv 90 c f`
- Maths: `(24-0.7)/2.2k`, `sqrt(3)*400*16` (use `=13-14` to force maths on a dash)
- Open a card: `card F-001`

Tap any source chip under an answer to open that card.

## Card format

```json
{"cards": [
  {"id": "CKT-010", "title": "Fuel pump relay circuit",
   "tags": ["fuel", "pump", "k20"],
   "path": "24V -> F7 -> K20:13-14 -> X4:1 -> M3 -> X4:2 -> 0V",
   "load": "M3",
   "control": "K20 coil <- PLC DO6",
   "simple": "M3 runs when K20 is pulled in.",
   "links": ["C-K20", "P-004"],
   "source": "DOC-03 sheet 12"},
  {"id": "F-030", "title": "Fuel pump not running",
   "tags": ["fuel", "pump", "m3"],
   "checks": ["Is K20 pulled in?", "24V at X4:1?", "Across M3?"],
   "links": ["CKT-010"],
   "source": "DOC-03 sheet 12"}
]}
```

ID prefixes: `P-` principle, `S-` system, `C-` component, `CKT-` circuit, `F-` fault, `DOC-` source document. A card you save with the same ID as a built-in card replaces it. Your cards are stored only on the phone (`user_cards.json`); use **Export mine** to back them up.

## Turning a real diagram into cards

Take a clear photo or screenshot of one diagram sheet, give it to Claude with this prompt, check the result against the drawing, then paste the JSON with **Cards → Paste JSON**:

> Convert this circuit diagram into Diag Helper cards (JSON, format: {"cards":[...]}). Make one CKT- card per current path (path with " -> ", load, control), one C- card per main component (function, terminals), and F- fault cards with ordered checks for the obvious symptoms. Use the exact designations and terminal numbers from the drawing; if something is unreadable, write "UNREADABLE" instead of guessing. Source: DOC-?? sheet ??.

Always check terminal numbers against the original drawing before you rely on a card.

## Project layout

```
app/src/main/java/com/fieldhelper/diag/MainActivity.java   screens (Ask, Cards, Add card)
app/src/main/java/com/fieldhelper/diag/core/               engine (no Android code, testable on any PC)
app/src/main/assets/cards/                                 built-in cards (JSON)
.github/workflows/build-apk.yml                            GitHub build
```

Requires: Android 8.0 (API 26) or newer. No permissions.
