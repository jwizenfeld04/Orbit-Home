#!/bin/bash
# Build Orbit.apk and install it on the TV.   ./deploy.sh [tv-ip]   (or TV_IP, or TV_IP= in .env)
set -euo pipefail
P=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=scripts/tv-ip.sh
. "$P/scripts/tv-ip.sh"; tv_ip "${1:-}"
rm -f "$P/app/Orbit.apk"
docker run --rm -e HOST_UID="$(id -u)" -v "$P/app:/app" -v "$HOME/.cache/android-sdk:/sdk" -v "$P/keys:/keys" \
  eclipse-temurin:17-jdk bash /app/build.sh 2>&1 | grep -vE "debconf|^$" | grep -E -A3 "error|Orbit.apk|warning" || true
[ -f "$P/app/Orbit.apk" ] || { echo "build failed: nothing installed"; exit 1; }
adb connect "$TV:5555" >/dev/null 2>&1 || true
adb -s "$TV:5555" install -r "$P/app/Orbit.apk" | tail -1
adb -s "$TV:5555" shell am start -n home.jw.orbit/.OrbitActivity >/dev/null 2>&1 || true  # an update closes Orbit; bring it back
