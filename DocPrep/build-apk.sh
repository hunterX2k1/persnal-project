#!/usr/bin/env bash
# Builds DocPrep.apk with only the Android SDK command-line tools (no Gradle).
# Usage: ANDROID_HOME=/path/to/sdk ./build-apk.sh
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-/opt/android-sdk}
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-34/android.jar
O=${BUILD_DIR:-build-manual}
DL=libs-dl
rm -rf "$O"; mkdir -p "$O"/{res,gen,classes,dex} "$DL"

# PDF text extraction library (Apache PDFBox port for Android), downloaded once.
PDFBOX_V=2.0.27.0
if [ ! -s "$DL/pdfbox.aar" ]; then
  echo "Downloading PDFBox-Android $PDFBOX_V"
  curl -fsSL --retry 5 --retry-delay 10 -o "$DL/pdfbox.aar.tmp" \
    "https://repo1.maven.org/maven2/com/tom-roush/pdfbox-android/$PDFBOX_V/pdfbox-android-$PDFBOX_V.aar"
  mv "$DL/pdfbox.aar.tmp" "$DL/pdfbox.aar"
fi
[ -s "$DL/pdfbox.jar" ] || unzip -p "$DL/pdfbox.aar" classes.jar > "$DL/pdfbox.jar"
[ -d "$DL/assets" ] || (mkdir -p "$DL/x" && unzip -q -o "$DL/pdfbox.aar" 'assets/*' -d "$DL/x" && mv "$DL/x/assets" "$DL/assets" && rm -rf "$DL/x")

"$BT/aapt2" compile --dir app/src/main/res -o "$O/res/"
sed 's#<manifest #<manifest package="com.docprep.app" android:versionCode="1" android:versionName="1.0" #' \
    app/src/main/AndroidManifest.xml \
  | sed 's#<application#<uses-sdk android:minSdkVersion="24" android:targetSdkVersion="34"/>\n    <application#' \
  > "$O/AndroidManifest.xml"
"$BT/aapt2" link ${DEBUG_BUILD:+--debug-mode} -I "$JAR" --manifest "$O/AndroidManifest.xml" -A "$DL/assets" -o "$O/unsigned.apk" --java "$O/gen" "$O"/res/*.flat
javac --release 8 -nowarn -cp "$JAR:$DL/pdfbox.jar" -d "$O/classes" $(find app/src/main/java "$O/gen" -name '*.java')
# PDFBox's optional public-key encryption support needs BouncyCastle; it's not bundled (password-free files only).
"$BT/d8" --min-api 24 --lib "$JAR" --output "$O/dex" $(find "$O/classes" -name '*.class') "$DL/pdfbox.jar" 2>&1 \
  | grep -v -e "Missing class" -e "^Warning" -e "bouncycastle" || true
[ -s "$O/dex/classes.dex" ] || { echo "dex failed"; exit 1; }
(cd "$O/dex" && zip -q ../unsigned.apk classes*.dex)
"$BT/zipalign" -f 4 "$O/unsigned.apk" "$O/aligned.apk"

KS=${KEYSTORE:-signing.jks}
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias docprep \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=DocPrep"
mkdir -p ../apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out "${OUT_APK:-../apk/DocPrep.apk}" "$O/aligned.apk"
rm -f ../apk/DocPrep.apk.idsig
"$BT/apksigner" verify "${OUT_APK:-../apk/DocPrep.apk}"
echo "Built: ${OUT_APK:-$(cd ..; pwd)/apk/DocPrep.apk}"
