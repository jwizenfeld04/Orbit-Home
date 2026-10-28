#!/bin/bash
# Rebuilds app/assets/wall/* and app/assets/themes.json from tools/themes.json (needs docker).
set -e
cd "$(dirname "$0")/.."
docker run --rm -v "$PWD":/p python:3.12-slim sh -c \
  "pip install -q pillow numpy 2>/dev/null && python -I /p/tools/make_assets.py /p/assets/wallpapers /p/tools/themes.json /p/tools/fonts/InterTight-VF.ttf /p/app/assets && chown -R $(id -u):$(id -g) /p/app/assets /p/app/res"
