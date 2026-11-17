#!/usr/bin/env bash
# Build + sign the APK without Gradle (Android SDK build-tools + JDK only).
# Usage: ./build.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/lib/android-sdk}}"
AJAR="$SDK/platforms/android-29/android.jar"
OUT="$ROOT/build"

BT_DIR="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)"
BT_DIR="${BT_DIR%/}"

if [ -z "$BT_DIR" ] || [ ! -d "$BT_DIR" ]; then
  echo "ERROR: Android build-tools not found under $SDK/build-tools" >&2
  echo "  apt-get install -y android-sdk-build-tools" >&2
  exit 1
fi
if [ ! -f "$AJAR" ]; then
  echo "ERROR: android.jar for API 29 not found at $AJAR" >&2
  echo "  apt-get install -y google-android-platform-29-installer" >&2
  exit 1
fi

if [ ! -f "$ROOT/deps/tesseract/classes.jar" ] || [ ! -s "$ROOT/assets/tessdata/chi_sim.traineddata" ]; then
  echo "== OCR deps missing, fetching"
  "$ROOT/fetch-ocr.sh"
fi

echo "== build-tools: $BT_DIR"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/gen" "$OUT/dex"

# ---------- resources ----------
RES_ARGS=()
if [ -d "$ROOT/res" ]; then
  "$BT_DIR/aapt2" compile --dir "$ROOT/res" -o "$OUT/res.zip"
  RES_ARGS+=(-R "$OUT/res.zip")
fi

ASSET_ARGS=()
if [ -d "$ROOT/assets" ]; then
  ASSET_ARGS+=(-A "$ROOT/assets")
fi

# ---------- link manifest + resources ----------
"$BT_DIR/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$AJAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  "${RES_ARGS[@]}" \
  "${ASSET_ARGS[@]}" \
  --java "$OUT/gen" \
  --version-code 11 \
  --version-name 2.3.1 \
  --auto-add-overlay

# ---------- compile java ----------
mapfile -t SRC < <(find "$ROOT/src" "$OUT/gen" -name '*.java' | sort)
echo "== javac: ${#SRC[@]} files"
TESS_JAR="$ROOT/deps/tesseract/classes.jar"
CP="$AJAR"
if [ -f "$TESS_JAR" ]; then
  CP="$CP:$TESS_JAR"
fi
javac -encoding UTF-8 --release 8 -Xlint:-options \
  -classpath "$CP" -d "$OUT/classes" "${SRC[@]}"

# ---------- dex ----------
mapfile -t CLASSES < <(find "$OUT/classes" -name '*.class' | sort)
D8=()
if [ -x "$BT_DIR/d8" ]; then
  D8=("$BT_DIR/d8")
else
  R8JAR="$ROOT/tools/r8.jar"
  if [ ! -f "$R8JAR" ]; then
    mkdir -p "$ROOT/tools"
    echo "== downloading r8.jar (d8 is not shipped by Debian build-tools)"
    for url in \
      "https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.2.42/r8-8.2.42.jar" \
      "https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.3.37/r8-8.3.37.jar" ; do
      if curl -fsSL --connect-timeout 15 -o "$R8JAR" "$url"; then
        break
      fi
    done
    [ -s "$R8JAR" ] || { echo "ERROR: cannot download r8.jar" >&2; exit 1; }
  fi
  D8=(java -cp "$R8JAR" com.android.tools.r8.D8)
fi
echo "== dex"
D8_INPUTS=("${CLASSES[@]}")
if [ -f "$TESS_JAR" ]; then
  D8_INPUTS+=("$TESS_JAR")
fi
"${D8[@]}" --release --lib "$AJAR" --min-api 26 --output "$OUT/dex" "${D8_INPUTS[@]}"

# ---------- package ----------
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes.dex)

SO_DIR="$ROOT/deps/tesseract/jni/arm64-v8a"
if [ -d "$SO_DIR" ]; then
  rm -rf "$OUT/apkroot"
  mkdir -p "$OUT/apkroot/lib/arm64-v8a"
  cp "$SO_DIR"/*.so "$OUT/apkroot/lib/arm64-v8a/"
  (cd "$OUT/apkroot" && zip -q -0 -r "$OUT/unsigned.apk" lib)
fi
"$BT_DIR/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# ---------- sign ----------
KS="$ROOT/keystore/debug.keystore"
if [ ! -f "$KS" ]; then
  mkdir -p "$ROOT/keystore"
  keytool -genkeypair -keystore "$KS" -alias dsh -storepass android -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ScreenWatch, O=DSH, C=CN" >/dev/null 2>&1
  echo "== created keystore/debug.keystore (password: android)"
fi
APK="$OUT/CloudGameAssistant-2.3.1.apk"
"$BT_DIR/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$APK" "$OUT/aligned.apk"
"$BT_DIR/apksigner" verify --print-certs "$APK" | head -4

echo
echo "OK -> $APK"
ls -lh "$APK"
