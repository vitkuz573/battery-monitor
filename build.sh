#!/bin/bash
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_HOME="$HOME/android-sdk"
PLATFORM="$ANDROID_HOME/platforms/android-35"
BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
JAVA_HOME="/usr/lib/jvm/java-17-openjdk"

AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
APKSIGNER="$BUILD_TOOLS/apksigner"
ZIPALIGN="$BUILD_TOOLS/zipalign"
ANDROID_JAR="$PLATFORM/android.jar"
JAVAC="$JAVA_HOME/bin/javac"

rm -rf "$PROJECT_DIR/build"
mkdir -p "$PROJECT_DIR/build/obj"
mkdir -p "$PROJECT_DIR/build/dex"
mkdir -p "$PROJECT_DIR/build/apk"

echo "=== Compiling resources ==="
"$AAPT2" compile --dir "$PROJECT_DIR/res" -o "$PROJECT_DIR/build/resources.zip"

echo "=== Linking APK (generating R.java) ==="
"$AAPT2" link -o "$PROJECT_DIR/build/apk/unaligned.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$PROJECT_DIR/AndroidManifest.xml" \
    --java "$PROJECT_DIR/build/obj" \
    "$PROJECT_DIR/build/resources.zip"

echo "=== Compiling Java sources ==="
"$JAVAC" -d "$PROJECT_DIR/build/obj" \
    -classpath "$ANDROID_JAR" \
    -source 17 -target 17 \
    "$PROJECT_DIR/build/obj/com/batterymonitor/R.java" \
    src/com/batterymonitor/MainActivity.java \
    src/com/batterymonitor/MonitorService.java

echo "=== Converting to DEX ==="
find "$PROJECT_DIR/build/obj" -name "*.class" -exec echo {} \; > "$PROJECT_DIR/build/classes.txt"
"$D8" --lib "$ANDROID_JAR" \
    --min-api 26 \
    --output "$PROJECT_DIR/build/dex" \
    @"$PROJECT_DIR/build/classes.txt"

echo "=== Adding DEX to APK ==="
cd "$PROJECT_DIR/build/dex"
zip -q "$PROJECT_DIR/build/apk/unaligned.apk" classes.dex

echo "=== Aligning ==="
"$ZIPALIGN" -f -p 4 \
    "$PROJECT_DIR/build/apk/unaligned.apk" \
    "$PROJECT_DIR/build/apk/aligned.apk"

echo "=== Signing ==="
DEBUG_KEYSTORE="$HOME/.android/debug.keystore"
if [ ! -f "$DEBUG_KEYSTORE" ]; then
    "$JAVA_HOME/bin/keytool" -genkey -v -keystore "$DEBUG_KEYSTORE" \
        -alias androiddebugkey -keyalg RSA -keysize 2048 \
        -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" \
        -storepass android -keypass android -noprompt 2>/dev/null || true
fi

"$APKSIGNER" sign \
    --ks "$DEBUG_KEYSTORE" \
    --ks-pass pass:android \
    --ks-key-alias androiddebugkey \
    --key-pass pass:android \
    --out "$PROJECT_DIR/build/apk/battery-monitor.apk" \
    "$PROJECT_DIR/build/apk/aligned.apk"

echo ""
echo "=== SUCCESS ==="
echo "APK: $PROJECT_DIR/build/apk/battery-monitor.apk"
ls -lh "$PROJECT_DIR/build/apk/battery-monitor.apk"
