#!/usr/bin/env bash
# Builds OpenSearch.apk using only the Android SDK command-line tools (no Gradle needed).
# Usage: ANDROID_HOME=/path/to/sdk ./build-apk.sh
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-/opt/android-sdk}
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-34/android.jar
O=${BUILD_DIR:-build-manual}
rm -rf "$O"; mkdir -p "$O"/{res,gen,classes,dex}

"$BT/aapt2" compile --dir app/src/main/res -o "$O/res/"
sed 's#<manifest #<manifest package="com.opensearch.app" android:versionCode="2" android:versionName="2.0" #' \
    app/src/main/AndroidManifest.xml \
  | sed 's#<application#<uses-sdk android:minSdkVersion="21" android:targetSdkVersion="34"/>\n    <application#' \
  > "$O/AndroidManifest.xml"
"$BT/aapt2" link -I "$JAR" --manifest "$O/AndroidManifest.xml" -o "$O/unsigned.apk" --java "$O/gen" "$O"/res/*.flat
LIBS=$(ls libs/*.jar | tr '\n' ':')
javac --release 8 -nowarn -cp "$JAR:$LIBS" -d "$O/classes" $(find app/src/main/java "$O/gen" -name '*.java')
"$BT/d8" --min-api 21 --lib "$JAR" --output "$O/dex" $(find "$O/classes" -name '*.class') libs/*.jar
(cd "$O/dex" && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$O/unsigned.apk" "$O/aligned.apk"

KS=${KEYSTORE:-signing.jks}
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias opensearch \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=OpenSearch"
mkdir -p ../apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out ../apk/OpenSearch.apk "$O/aligned.apk"
rm -f ../apk/OpenSearch.apk.idsig
"$BT/apksigner" verify ../apk/OpenSearch.apk
echo "Built: $(cd ..; pwd)/apk/OpenSearch.apk"
