#!/bin/bash
# Set up a TV (new or factory-reset) for Orbit: build and install it, and grant its two one-time permissions.
#
#   ./setup-tv.sh [tv-ip] [--debloat]        (tv-ip: or TV_IP, or TV_IP= in .env)
#
# Before: turn on ADB debugging on the TV. Fire TV: Settings > My Fire TV > About > click the name 7 times,
# then Developer options > ADB debugging. Give the TV a fixed IP (a DHCP reservation on your router).
# Options:
#   --debloat              Fire TV: disable Amazon's shopping, ad and demo apps (pm disable-user; undo with
#                          `adb shell pm enable <package>`) and speed up animations.
#   FALLBACK_APK=<file>    also install another launcher (e.g. FLauncher) as a fallback home screen.
# Safe to run again.
set -uo pipefail
P=$(cd "$(dirname "$0")" && pwd)
DEBLOAT=0; ARG=""
for a in "$@"; do case "$a" in --debloat) DEBLOAT=1 ;; *) ARG=$a ;; esac; done
# shellcheck source=scripts/tv-ip.sh
. "$P/scripts/tv-ip.sh"; tv_ip "$ARG"
A="adb -s $TV:5555"

echo "== Connecting (approve 'Allow USB debugging' on the TV if it asks, tick 'Always allow')"
adb connect "$TV:5555"
for _ in $(seq 1 30); do $A get-state >/dev/null 2>&1 && break; sleep 2; done
$A get-state >/dev/null || { echo "The TV didn't accept ADB"; exit 1; }
MODEL=$($A shell getprop ro.product.model | tr -d '\r')
FIRE=0; $A shell pm path com.amazon.tv.launcher >/dev/null 2>&1 && FIRE=1
echo "   $MODEL ($([ $FIRE = 1 ] && echo "Fire TV" || echo "Android TV / Google TV"))"

echo "== Orbit (build + install)"
"$P/deploy.sh" "$TV" || { echo "Orbit didn't build or install; stopping here."; exit 1; }
# Lets Orbit see what music is playing (now-playing card, ⏯ / ◀◀ ▶▶ on the remote). It reads no notifications.
$A shell cmd notification allow_listener home.jw.orbit/home.jw.orbit.MediaListener
# NFL score alerts over other apps.
$A shell appops set home.jw.orbit SYSTEM_ALERT_WINDOW allow

if [ -n "${FALLBACK_APK:-}" ]; then
  echo "== Fallback launcher: $($A install -r "$FALLBACK_APK" | tail -1)"
fi

if [ $DEBLOAT = 1 ] && [ $FIRE = 1 ]; then
  echo "== De-bloat (undo any one with: adb shell pm enable <package>)"
  for p in com.amazon.shoptv.client com.amazon.shoptv.firetv.client com.amazon.sneakpeek com.amazon.minitv.android.app \
           com.amazon.storm.lightning.tutorial com.amazon.tmm.tutorial com.amazon.tv.democontent.provider; do
    $A shell pm path "$p" >/dev/null 2>&1 && $A shell pm disable-user --user 0 "$p" >/dev/null && echo "   disabled $p"
  done
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do $A shell settings put global $k 0.5; done
fi

echo
echo "== Next"
echo " - Open Orbit from the TV's app list and go through the setup screens."
if [ $FIRE = 1 ]; then
  echo " - Fire TV always opens Amazon's home screen on Home. To send Home to Orbit, run Orbit-Helper on a"
  echo "   home server (it keeps homewatch.sh running on the TV), or see 'Make Home open Orbit' in README.md."
else
  echo " - Press Home and pick Orbit as the home app ('Always')."
fi
