# Handoff: Diag Helper (offline diagnosis helper for Android)

Read this first. It carries the context from the earlier chat where this project was designed and built.

## Who the user is
- Trainee engineer (track to service engineer) on MTU engine controls at a dealership; mechatronics degree; CCNA/CCNP base; learning SCADA/PLC.
- Works from a phone only, no PC. Explain simply, use visuals where possible.
- Wants progress bars / completion % in updates on long tasks.

## The goal
A phone AI that helps with fault diagnosis: reminds the principle, explains how the system works, and lists what could be defective.
Example: "module light is not glowing" → find the circuit in the indexed diagrams/docs → "the light glows when X → Y → Z; check A, B, C".

## Decisions made (by the user)
- Fully offline. Answers must come only from an internal index of simplified cards, each with tags and a reference to the original document page. Every technical answer lists the cards/sources used. If nothing matches: "Not in index", never guess.
- Knowledge is part of the app (internal index), not visible file searching.
- Version 1: specialist engine only, **no LLM** (small LLM may be added later only to phrase answers).
- Starter content: electrical principles, maths/calculators, a made-up example circuit, and a card editor.
- Output: **APK source code** (user builds via GitHub Actions from phone, or someone with a PC).

## Design agreed in the chat
- Card types by ID prefix: P- principle, S- system, C- component, CKT- circuit, F- fault, DOC- source.
- Card fields: id, title, tags, simple, path ("24V -> F3 -> K12:13-14 -> X2:5 -> H5 -> X2:6 -> 0V"), load, control, checks (ordered), links, source ("DOC-02 sheet 7").
- Diagrams are converted ONCE into connection/path cards (by a big AI like Claude from a photo of the sheet, then checked by the user against the drawing). Small offline models can't reliably read schematics.
- Why not fine-tune documents into a model: small models blur exact terminal numbers/values and invent them; no citations; retraining for each change.

## What exists now (this folder)
- `app/src/main/java/com/fieldhelper/diag/core/` — pure Java engine, no Android code: Json, Card, CardIndex (tag/ID/word search with synonyms), Answerer (answers + refs), CircuitTracer (path → ordered checks), ElecCalc (Ohm/power, cable drop, 4-20 mA, unit conversion), MathEval, Engine (router), Reply.
- `MainActivity.java` — plain Android views, 3 tabs: Ask (chat, quick chips, tappable source chips), Cards (filter, import file, paste JSON, export), Add card (editor, test trace). User cards saved to `files/user_cards.json`.
- `assets/cards/01_principles.json` (P-001…P-020), `02_example_circuit.json` (made-up panel: H5 lamp, K12 relay, PLC A1 DO4, F2/F3, X2; CKT-001/002; F-001…F-004).
- `.github/workflows/build-apk.yml` builds a debug APK (artifact "DiagHelper-apk"). Gradle wrapper 8.9, AGP 8.5.2, compileSdk 34, minSdk 26, Java 17, no dependencies, no permissions.

## Diagram reader (added)
- User's diagram PDFs are vector (sharp lines, searchable text). Decision: read them with geometry rules, not an AI model, so terminal numbers are exact.
- `app/src/main/java/com/fieldhelper/diag/diagram/`: `PdfExtractor` (PdfBox-Android: painted lines/shapes + positioned words), `PageGeom` (raw page data), `DiagramReader` (pure Java: wires vs symbols, nets with T-joints/dots, tags/pins/descriptions from nearest text, path search supply → load → 0V, auto CKT-/C-/DOC- cards with page source).
- App: Cards tab → "Read drawing PDF" / "Try practice drawing" (`assets/practice_drawing.pdf`, made by `tools/make_practice_drawing.py`). Long-press nothing; tap the DOC- card → Delete removes the whole drawing.
- Desktop test: compile core + diagram with Apache PDFBox 2.0.x (PdfExtractor with `com.tom_roush.pdfbox`→`org.apache.pdfbox`, `PointF`→`Point2D`, `Path.FillType`→`int`). Practice drawing gives all 4 circuits exactly.
- Not done yet: cross-sheet references (paths that continue on another page show "? (… not on this page)"), tuning on a real MTU sheet, scanned PDFs (would need OCR).
## Donor model (added)
- User's idea: take only the needed part of an open-source model. Used all-MiniLM-L6-v2 (Apache 2.0, distilled sentence model), 8-bit arm64 ONNX, `assets/model/minilm.onnx` + `vocab.txt`; run with ONNX Runtime Android (arm64-v8a only).
- `ai/WordPieceTokenizer` (matches HF BertWordPiece exactly), `ai/MiniLm` (mean pooling, normalised 384-d), `ai/SemanticIndex` (vector per card, cache `files/card_vectors.bin`), `core/SemanticSearch` interface.
- `Answerer.rank`: score = word score + 50 × (similarity − 0.30); accept only if similarity ≥ 0.30 or word score ≥ 15. Rejects nonsense ("coffee machine broken") that word search alone matched. Answers marked "Found by meaning" when words didn't match.
- Known limit: reasoning questions ("which fuse feeds the alarm lamp") aren't solved by meaning search.
- Desktop test: ONNX Runtime Java 1.19.2 jar + same classes; tested 18 questions, 17 right.
- Phone: Moto Edge 60 Pro 8 GB; user has only the phone.

## Verification status
- Engine tested on desktop JDK: all checks passed (fault lookup, calculators, Not in index, user card save/reload/delete).
- APK builds (Gradle + Android SDK 34). Not yet run on a device/emulator by Claude (no KVM in the cloud container); user tests on the phone.
- To re-run engine tests: compile `core/*.java` with a small test main that loads the two JSON files and calls `Engine.ask(...)`.

## Next steps (open)
1. ~~Build the APK~~ done. Test diagram reader on a real MTU sheet and tune.
2. Convert the user's real MTU diagram sheets into cards (user sends a photo; produce card JSON; mark unreadable items "UNREADABLE"; user checks terminals before relying on it). Real MTU documents are private — keep them out of public repos.
3. Later ideas from the chat: optional small LLM only for phrasing; specialist ML models (anomaly detection on sensor data) — not started.

Related earlier project of the user: "Manual Librarian" (offline APK that scans a folder of MTU PDFs; a previous on-phone LLM attempt was too slow, ~0.5 tokens/s; target phone Moto Edge 60 Pro 8 GB).
