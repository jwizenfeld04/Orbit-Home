#!/bin/bash
# Custom themes: build one from a folder and put it on the TV, or remove one (needs docker + adb).
#   tools/custom_theme.sh <theme_dir> [tv-ip]      build <theme_dir>/theme.json and install it
#   tools/custom_theme.sh --remove <id> [tv-ip]    take a custom theme off the TV
#   tools/custom_theme.sh --list [tv-ip]           custom themes on the TV
# Orbit restarts so it sees the change. The built files go to <theme_dir>/build/<id>/.
set -euo pipefail
P=$(cd "$(dirname "$0")/.." && pwd)
DEST=/sdcard/Android/data/home.jw.orbit/files/themes
restart() { adb -s "$T" shell am force-stop home.jw.orbit; adb -s "$T" shell am start -n home.jw.orbit/.OrbitActivity >/dev/null; }
# shellcheck source=scripts/tv-ip.sh
. "$P/scripts/tv-ip.sh"
connect() { tv_ip "${1:-}"; T="$TV:5555"; adb connect "$T" >/dev/null 2>&1 || true; }
case "${1:-}" in
  ""|-h|--help) sed -n 2,6p "$0"; exit 0 ;;
  --list) connect "${2:-}"; adb -s "$T" shell "ls $DEST 2>/dev/null" || true; exit 0 ;;
  --remove)
    id=${2:?theme id}; [[ $id =~ ^[a-z0-9_-]+$ ]] || { echo "bad id: $id"; exit 1; }
    connect "${3:-}"; adb -s "$T" shell rm -rf "$DEST/$id"; restart; echo "removed $id"; exit 0 ;;
esac
SRC=$(cd "$1" && pwd)
connect "${2:-}"
id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$SRC/theme.json")
[[ $id =~ ^[a-z0-9_-]{1,40}$ ]] || { echo "bad id: $id"; exit 1; }
docker run --rm -v "$P/tools":/tools:ro -v "$SRC":/theme python:3.12-slim sh -c \
  "pip install -q pillow numpy 2>/dev/null && cd /tools && python /tools/custom_theme.py /theme /theme/build && chown -R $(id -u):$(id -g) /theme/build"
adb -s "$T" shell "rm -rf $DEST/$id && mkdir -p $DEST"
adb -s "$T" push "$SRC/build/$id" "$DEST/" | tail -1
restart
echo "installed $id: pick it in Settings → Theme"
