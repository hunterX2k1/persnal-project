#!/usr/bin/env bash
# Run this on your own computer (YouTube blocks caption downloads from cloud servers).
# It saves the English captions of every lecture in the playlist as .vtt files in ./transcripts
set -e
python3 -m pip install --upgrade yt-dlp
mkdir -p transcripts
python3 -m yt_dlp --skip-download --write-auto-subs --write-subs --sub-langs "en.*" --sub-format vtt \
  -o "transcripts/%(playlist_index)02d %(title)s.%(ext)s" \
  "https://www.youtube.com/playlist?list=PLwdnzlV3ogoUT7g3BySY2QQesG3eB4dss"
echo "Done. Zip the transcripts folder and share it with Claude."
