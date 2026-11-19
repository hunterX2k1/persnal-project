# Voice Insight — audio to text with tone & psychological analysis

A single-page web app (`index.html`) that turns any audio or video file into a timestamped transcript and analyses **how** things were said as well as **what** was said. Everything runs in your browser, so the audio never leaves your device.

## Features
- **Input:** drag-and-drop or browse for any format your browser can decode (MP3, WAV, M4A/AAC, OGG/Opus, FLAC, WEBM, MP4/MOV video…), or record from the microphone.
- **Transcription:** OpenAI Whisper (tiny / base / small, multilingual or English-only) via transformers.js, with timestamps. It auto-detects the language, or you can pick one (Hindi, Tamil, Spanish…).
- **Tone (acoustic) analysis:** pitch contour, pitch variation in semitones, loudness and its variation, speaking rate, pauses, and pitch instability. Each segment gets tags such as *raised pitch*, *emphatic*, *soft*, *rapid*, *flat tone* or *animated*.
- **Emotion recognition:** the GoEmotions model (28 emotions, grouped into joy, warmth, sadness, anger, anxiety, surprise and neutral) runs on each segment, with an emotional timeline. If the model can't load, a word list is used instead.
- **Psychological profile:** a valence × arousal (circumplex) map and an overall communication style. It scores confidence, stress/anxiety markers, low-mood markers, expressiveness, hesitation, warmth, self-focus and analytical thinking, showing the evidence behind each score. It also writes a narrative summary that points out emotional peaks.
- **Export:** TXT, SRT subtitles, full JSON report, copy to clipboard.

## Run
Open `index.html` directly, or serve the folder (recommended, because the models then load in a background worker):

```bash
cd audio-transcript && python3 -m http.server 8000   # → http://localhost:8000
```

The first run downloads the models from Hugging Face (~130 MB for Whisper Base plus ~120 MB for the emotion model). After that the browser caches them.

## Disclaimer
The profile is built from research-inspired heuristics, such as hedging, absolutist words, first-person pronoun use, pitch variability and speech rate. It is **not** a clinical or diagnostic assessment.
