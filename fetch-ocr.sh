#!/usr/bin/env bash
# Downloads the offline OCR dependencies (Tesseract4Android + chi_sim/eng models).
# Run automatically by build.sh when deps/ or assets/tessdata/ are missing.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AAR="$ROOT/deps/tesseract4android-4.9.0.aar"
AAR_URL="https://mirrors.cloud.tencent.com/nexus/repository/maven-public/cz/adaptech/tesseract4android/tesseract4android/4.9.0/tesseract4android-4.9.0.aar"
MODEL_BASE="https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main"

if [ ! -f "$ROOT/deps/tesseract/classes.jar" ]; then
  mkdir -p "$ROOT/deps"
  if [ ! -s "$AAR" ]; then
    echo "== downloading Tesseract4Android 4.9.0 (13 MB)"
    curl -fL --retry 3 --connect-timeout 20 -o "$AAR" "$AAR_URL"
  fi
  echo "== extracting AAR"
  rm -rf "$ROOT/deps/tesseract"
  mkdir -p "$ROOT/deps/tesseract"
  (cd "$ROOT/deps/tesseract" && unzip -q "$AAR")
fi

mkdir -p "$ROOT/assets/tessdata"
for f in chi_sim eng; do
  if [ ! -s "$ROOT/assets/tessdata/$f.traineddata" ]; then
    echo "== downloading $f.traineddata"
    curl -fL --retry 3 --connect-timeout 20 \
      -o "$ROOT/assets/tessdata/$f.traineddata" "$MODEL_BASE/$f.traineddata"
  fi
done
echo "== OCR deps ready"
