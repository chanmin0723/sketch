#!/usr/bin/env bash
# Builds the APK without the Android SDK, using:
#   aapt2 (from apktool), android.jar (Sable/android-platforms), d8 (converted to JVM), uber-apk-signer
set -euo pipefail
cd "$(dirname "$0")"
T="${TOOLS_DIR:-$(pwd)/.toolchain}"
AAPT2=$T/aapt/aapt2_64
ANDROID_JAR=$T/android-34.jar
D8="java -XX:+UnlockDiagnosticVMOptions -XX:-BytecodeVerificationRemote -XX:-BytecodeVerificationLocal -cp $T/d8-fixed.jar com.android.tools.r8.D8"

rm -rf build/apk && mkdir -p build/apk/{res,gen,classes,dex}

echo "== aapt2 compile/link"
$AAPT2 compile --dir res -o build/apk/res/res.zip
$AAPT2 link -I "$ANDROID_JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 29 --target-sdk-version 34 \
  --version-code 1 --version-name 1.0 \
  --java build/apk/gen -o build/apk/unsigned.apk build/apk/res/res.zip

echo "== javac"
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" -d build/apk/classes \
  $(find src build/apk/gen -name '*.java')

echo "== strip MethodParameters"
java -cp $T/fixitf:$T/asm.jar StripParams build/apk/classes

echo "== d8"
$D8 --release --min-api 29 --output build/apk/dex $(find build/apk/classes -name '*.class')

echo "== package"
(cd build/apk/dex && zip -q -X ../unsigned.apk classes.dex)

echo "== align + sign"
java -jar $T/uber-apk-signer-1.3.0.jar -a build/apk/unsigned.apk -o build/apk/signed --allowResign
ls -la build/apk/signed
