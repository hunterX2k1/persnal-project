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

# ---- Tor engine: downloaded once into libs-dl/ (not committed, ~13 MB) ----
TOR_V=0.4.9.6   # newer builds need JDK 24 to compile against
DL=libs-dl
ABIS=${ABIS:-arm64-v8a armeabi-v7a}
fetch() { [ -s "$2" ] || { echo "Downloading $1"; curl -fsSL --retry 5 --retry-delay 10 -o "$2.tmp" "$1" && mv "$2.tmp" "$2"; }; }
mkdir -p "$DL"
fetch "https://repo1.maven.org/maven2/info/guardianproject/tor-android/$TOR_V/tor-android-$TOR_V.aar" "$DL/tor-android.aar"
fetch "https://repo1.maven.org/maven2/info/guardianproject/jtorctl/0.4.5.7/jtorctl-0.4.5.7.jar" "$DL/jtorctl.jar"
fetch "https://dl.google.com/android/maven2/androidx/localbroadcastmanager/localbroadcastmanager/1.1.0/localbroadcastmanager-1.1.0.aar" "$DL/lbm.aar"
[ -s "$DL/tor-android.jar" ] || unzip -p "$DL/tor-android.aar" classes.jar > "$DL/tor-android.jar"
[ -s "$DL/lbm.jar" ] || unzip -p "$DL/lbm.aar" classes.jar > "$DL/lbm.jar"
for abi in $ABIS; do
  mkdir -p "$O/native/lib/$abi"
  unzip -p "$DL/tor-android.aar" "jni/$abi/libtor.so" > "$O/native/lib/$abi/libtor.so"
done

"$BT/aapt2" compile --dir app/src/main/res -o "$O/res/"
sed 's#<manifest #<manifest package="com.opensearch.app" android:versionCode="3" android:versionName="3.0" #' \
    app/src/main/AndroidManifest.xml \
  | sed 's#<application#<uses-sdk android:minSdkVersion="24" android:targetSdkVersion="34"/>\n    <application#' \
  > "$O/AndroidManifest.xml"
"$BT/aapt2" link -I "$JAR" --manifest "$O/AndroidManifest.xml" -o "$O/unsigned.apk" --java "$O/gen" "$O"/res/*.flat
LIBS=$(ls libs/*.jar $DL/*.jar | tr '\n' ':')
javac --release 8 -nowarn -cp "$JAR:$LIBS" -d "$O/classes" $(find app/src/main/java "$O/gen" -name '*.java')
"$BT/d8" --min-api 24 --lib "$JAR" --output "$O/dex" $(find "$O/classes" -name '*.class') libs/*.jar $DL/*.jar
(cd "$O/dex" && zip -q ../unsigned.apk classes.dex)
(cd "$O/native" && zip -qr ../unsigned.apk lib)
"$BT/zipalign" -f 4 "$O/unsigned.apk" "$O/aligned.apk"

KS=${KEYSTORE:-signing.jks}
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias opensearch \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=OpenSearch"
mkdir -p ../apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out ../apk/OpenSearch.apk "$O/aligned.apk"
rm -f ../apk/OpenSearch.apk.idsig
"$BT/apksigner" verify ../apk/OpenSearch.apk
echo "Built: $(cd ..; pwd)/apk/OpenSearch.apk"
