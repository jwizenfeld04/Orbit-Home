"""Composes the glass clock exactly as the TV does (reference for ClockRenderer.java).
  python preview_glass.py <assets_dir> <phase> <time> <weight> <out.png>"""
import json
import sys

import numpy as np
from PIL import Image

assets, phase, text, weight, out = sys.argv[1:6]
FROST = float(sys.argv[6]) if len(sys.argv) > 6 else 0.13  # Glass strength: 0.06 / 0.13 / 0.24
meta = json.load(open(f"{assets}/glyph/meta.json"))
info = meta["weights"][weight]
pad, ls, k = meta["pad"], meta["letterSpacing"], meta["dispScale"]
wall = np.asarray(Image.open(f"{assets}/wall/{phase}.jpg").convert("RGB"), np.float32)
region = json.load(open(f"{assets}/wall/region.json")) if False else (0, 100, 1920, 560)
back = np.zeros((1080, 1920, 3), np.float32)
back[region[1]:region[3]] = np.asarray(Image.open(f"{assets}/wall/{phase}_clock.jpg").convert("RGB"), np.float32)

chars = [info["chars"][c] for c in text]
total = sum(c["advance"] for c in chars) + ls * (len(chars) - 1)
pen = 960 - total / 2
frame = wall.copy()
load = lambda c, kind: np.asarray(Image.open(f"{assets}/glyph/w{weight}/{c['file']}_{kind}.png").convert("RGBA" if kind != "disp" else "RGB"), np.float32)
placed = []
for c in chars:
    placed.append((c, int(round(pen - pad)), int(round(meta["baseline"] - info["ascent"] - pad))))
    pen += c["advance"] + ls


def over(dst, src, x, y):
    h, w = src.shape[:2]
    a = src[..., 3:4] / 255
    dst[y:y + h, x:x + w] = src[..., :3] * a + dst[y:y + h, x:x + w] * (1 - a)


for c, x, y in placed:
    over(frame, load(c, "shadow"), x, y)
for c, x, y in placed:  # glass body: frosted wallpaper seen through the bent edges
    m, d = load(c, "mask")[..., 3] / 255, load(c, "disp")
    h, w = m.shape
    yy, xx = np.mgrid[0:h, 0:w]
    sx = np.clip(x + xx + (d[..., 0] - 128) / k, 0, 1919).astype(int)
    sy = np.clip(y + yy + (d[..., 1] - 128) / k, 0, 1079).astype(int)
    body = back[sy, sx]
    frost = (FROST + 0.07 * (1 - yy / h))[..., None]  # same as ClockRenderer: milkier toward the top
    body = body * (1 - frost) + 255 * frost
    frame[y:y + h, x:x + w] = body * m[..., None] + frame[y:y + h, x:x + w] * (1 - m[..., None])
for c, x, y in placed:
    over(frame, load(c, "over"), x, y)
Image.fromarray(np.clip(frame, 0, 255).astype(np.uint8)).save(out)
