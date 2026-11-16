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

echo "== build-tools: $BT_DIR"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/gen" "$OUT/dex"

# ---------- resources ----------
RES_ARGS=()
if [ -d "$ROOT/res" ]; then
  "$BT_DIR/aapt2" compile --dir "$ROOT/res" -o "$OUT/res.zip"
  RES_ARGS+=(-R "$OUT/res.zip")
fi

# ---------- link manifest + resources ----------
"$BT_DIR/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$AJAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  "${RES_ARGS[@]}" \
  --java "$OUT/gen" \
  --version-code 1 \
  --version-name 1.0 \
  --auto-add-overlay

# ---------- compile java ----------
mapfile -t SRC < <(find "$ROOT/src" "$OUT/gen" -name '*.java' | sort)
echo "== javac: ${#SRC[@]} files"
javac -encoding UTF-8 --release 8 -Xlint:-options \
  -classpath "$AJAR" -d "$OUT/classes" "${SRC[@]}"

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
      "https://dl.google.com/dl/android/maven2/com/android/tools/r8/3.0.73/r8-3.0.73.jar" \
      "https://maven.aliyun.com/repository/public/com/android/tools/r8/3.0.73/r8-3.0.73.jar" \
      "https://maven.aliyun.com/repository/google/com/android/tools/r8/3.0.73/r8-3.0.73.jar" ; do
      if curl -fsSL --connect-timeout 15 -o "$R8JAR" "$url"; then
        break
      fi
    done
    [ -s "$R8JAR" ] || { echo "ERROR: cannot download r8.jar" >&2; exit 1; }
  fi
  D8=(java -cp "$R8JAR" com.android.tools.r8.D8)
fi
echo "== dex"
"${D8[@]}" --release --lib "$AJAR" --min-api 26 --output "$OUT/dex" "${CLASSES[@]}"

# ---------- package ----------
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes.dex)
"$BT_DIR/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# ---------- sign ----------
KS="$ROOT/keystore/debug.keystore"
if [ ! -f "$KS" ]; then
  mkdir -p "$ROOT/keystore"
  keytool -genkeypair -keystore "$KS" -alias dsh -storepass android -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ScreenWatch, O=DSH, C=CN" >/dev/null 2>&1
  echo "== created keystore/debug.keystore (password: android)"
fi
APK="$OUT/ScreenWatch-1.0.apk"
"$BT_DIR/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$APK" "$OUT/aligned.apk"
"$BT_DIR/apksigner" verify --print-certs "$APK" | head -4

echo
echo "OK -> $APK"
ls -lh "$APK"
