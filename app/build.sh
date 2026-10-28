#!/bin/bash
# Builds Orbit.apk inside eclipse-temurin:17-jdk. Mounts: /app (this dir), /sdk (SDK cache), /keys (signing key).
#   docker run --rm -e HOST_UID=1000 -v <repo>/app:/app -v ~/.cache/android-sdk:/sdk \
#     -v <repo>/keys:/keys eclipse-temurin:17-jdk bash /app/build.sh
# Assets (wallpapers, glass digits, banner) come from ../tools/make_assets.py and are committed as files.
set -euo pipefail
apt-get -qq update >/dev/null && apt-get -qq install -y unzip zip curl >/dev/null
if [ ! -x /sdk/cmdline-tools/latest/bin/sdkmanager ]; then
  curl -fsSL -o /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  mkdir -p /sdk/cmdline-tools && unzip -q /tmp/clt.zip -d /sdk/cmdline-tools && mv /sdk/cmdline-tools/cmdline-tools /sdk/cmdline-tools/latest
fi
yes | /sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/sdk --licenses >/dev/null || true
/sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/sdk "build-tools;34.0.0" "platforms;android-28" >/dev/null
BT=/sdk/build-tools/34.0.0; JAR=/sdk/platforms/android-28/android.jar
cd /app; rm -rf out; mkdir -p out/classes
$BT/aapt2 compile --dir res -o out/res.zip
$BT/aapt2 link -I $JAR --manifest AndroidManifest.xml -A assets -0 jpg -0 png -o out/base.apk out/res.zip --java out/gen
# shellcheck disable=SC2046  # one argument per source file is the point
javac -Xlint:-options --release 8 -classpath $JAR -d out/classes $(find src out/gen -name '*.java')
# shellcheck disable=SC2046
$BT/d8 --release --min-api 26 --lib $JAR --output out $(find out/classes -name '*.class')
cp out/base.apk out/unsigned.apk && (cd out && zip -q unsigned.apk classes.dex)
$BT/zipalign -f 4 out/unsigned.apk out/aligned.apk
# Signing key + its random password live only in /keys (gitignored, 0600). Keep both: updates must use the same key.
if [ ! -f /keys/orbit.jks ]; then
  umask 077; pw=$(head -c 24 /dev/urandom | base64 | tr -d '/+='); printf '%s\n%s\n' "$pw" "$pw" > /keys/orbit.pass  # line 1 keystore, line 2 key
  keytool -genkeypair -keystore /keys/orbit.jks -storepass:file /keys/orbit.pass -keypass:file /keys/orbit.pass -alias orbit -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=Orbit" >/dev/null 2>&1
fi
$BT/apksigner sign --ks /keys/orbit.jks --ks-pass file:/keys/orbit.pass --key-pass file:/keys/orbit.pass --out Orbit.apk out/aligned.apk
$BT/apksigner verify Orbit.apk && chown -R $HOST_UID:$HOST_UID /app /keys && ls -l Orbit.apk
