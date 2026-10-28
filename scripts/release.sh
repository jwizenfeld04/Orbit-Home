#!/bin/bash
# Publish a release of the merged main branch: tag vX.Y.Z, build the signed APK, create the GitHub release.
#   scripts/release.sh 1.2.0
# Before running: a PR merged into main that moved the "Unreleased" changelog entries under "## [1.2.0] - <date>"
# and set android:versionName="1.2.0" / versionCode = major*10000 + minor*100 + patch (here 10200).
# Needs gh (logged in), docker, and the signing key in keys/ (never in git). See RELEASING.md.
set -euo pipefail
P=$(cd "$(dirname "$0")/.." && pwd)
cd "$P"
V=${1:?usage: scripts/release.sh X.Y.Z}
[[ $V =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || { echo "version must be X.Y.Z"; exit 1; }
CODE=$((BASH_REMATCH[1] * 10000 + BASH_REMATCH[2] * 100 + BASH_REMATCH[3]))

git fetch -q origin
[ "$(git branch --show-current)" = main ] || { echo "switch to main first"; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "working tree not clean"; exit 1; }
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "main differs from origin/main: pull first"; exit 1; }
git rev-parse -q --verify "refs/tags/v$V" >/dev/null && { echo "v$V already exists"; exit 1; }
grep -q "^## \[$V\] - " CHANGELOG.md || { echo "CHANGELOG.md has no '## [$V] - <date>' section"; exit 1; }
grep -q "android:versionName=\"$V\"" app/AndroidManifest.xml || { echo "AndroidManifest versionName isn't $V"; exit 1; }
grep -q "android:versionCode=\"$CODE\"" app/AndroidManifest.xml || { echo "AndroidManifest versionCode isn't $CODE"; exit 1; }
[ -f keys/orbit.jks ] || { echo "keys/orbit.jks missing: restore it from your backup (never make a new one: the TV can only update with the same key)"; exit 1; }

# Release notes = this version's changelog section.
NOTES=$(mktemp); trap 'rm -f "$NOTES"' EXIT
awk -v v="$V" '$0 ~ "^## \\[" v "\\]" {on=1; next} on && /^## \[/ {exit} on && !/^\[.*\]: / {print}' CHANGELOG.md > "$NOTES"

echo "building signed APK…"
rm -f app/Orbit.apk
docker run --rm -e HOST_UID="$(id -u)" -v "$P/app:/app" -v "$HOME/.cache/android-sdk:/sdk" -v "$P/keys:/keys" \
  eclipse-temurin:17-jdk bash /app/build.sh >/dev/null 2>&1 || true
[ -f app/Orbit.apk ] || { echo "build failed"; exit 1; }
cp app/Orbit.apk "Orbit-$V.apk"; trap 'rm -f "$NOTES" "$P/Orbit-$V.apk"' EXIT

git tag -a "v$V" -m "Orbit $V"
git push -q origin "v$V"
gh release create "v$V" "Orbit-$V.apk" --title "Orbit $V" --notes-file "$NOTES" --verify-tag
echo "released v$V. Install on the TV with ./deploy.sh"
