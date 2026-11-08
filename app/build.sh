#!/bin/bash
# Build XCPE Cleaner with the raw SDK tools (no Gradle). Output: $OUT/XCPE-Cleaner.apk
set -euo pipefail
SDK=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-33/android.jar
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-$HERE/build}
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)   # absolute, so the zip step works with a relative OUT
VERSION_CODE=${VERSION_CODE:-1}
VERSION_NAME=${VERSION_NAME:-1.0}
MANIFEST=${MANIFEST:-$HERE/AndroidManifest.xml}   # override for test variants
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/gen" "$OUT/dex"
"$BT/aapt2" compile --dir "$HERE/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" --manifest "$MANIFEST" -I "$JAR" --java "$OUT/gen" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" "$OUT/res.zip"
find "$HERE/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac --release 8 -cp "$JAR" -encoding UTF-8 -Xlint:-options -d "$OUT/classes" @"$OUT/sources.txt"
"$BT/d8" --release --min-api 21 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes.dex )
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$HERE/keystore/xcpe-release.jks" --ks-key-alias xcpe \
  --ks-pass "file:$HERE/keystore/password.txt" --key-pass "file:$HERE/keystore/password.txt" \
  --out "$OUT/XCPE-Cleaner.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/XCPE-Cleaner.apk"
ls -la "$OUT/XCPE-Cleaner.apk"
