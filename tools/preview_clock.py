"""Composes the glass clock exactly as the TV does (reference for ClockRenderer.java).
  python preview_clock.py <assets_dir> <phase> <time> <weight> <out.png>"""
import json
import sys

from PIL import Image

assets, phase, text, weight, out = sys.argv[1:6]
meta = json.load(open(f"{assets}/glyph/meta.json"))
info = meta["weights"][weight]
pad, ls = meta["pad"], meta["letterSpacing"]
BASELINE_Y, CENTER_X = meta["baseline"], 960

wall = Image.open(f"{assets}/wall/{phase}.jpg").convert("RGBA")
x0, y0, x1, y1 = meta["clockRegion"]
backdrop = Image.new("RGBA", (1920, 1080), (0, 0, 0, 0))
backdrop.paste(Image.open(f"{assets}/wall/{phase}_clock.jpg").convert("RGBA"), (x0, y0))

chars = [info["chars"][c] for c in text]
total = sum(c["advance"] for c in chars) + ls * (len(chars) - 1)
pen = CENTER_X - total / 2
placed = []
for c in chars:
    placed.append((c, int(round(pen - pad)), int(round(BASELINE_Y - info["ascent"] - pad))))
    pen += c["advance"] + ls

load = lambda c, kind: Image.open(f"{assets}/glyph/w{weight}/{c['file']}_{kind}.png").convert("RGBA")
frame = wall.copy()
for c, x, y in placed:  # 1. shadows
    frame.alpha_composite(load(c, "shadow"), (x, y))
mask = Image.new("L", (1920, 1080), 0)
for c, x, y in placed:  # 2. glass body: the magnified backdrop, cut to the digits
    m = load(c, "mask").split()[3]
    region = mask.crop((x, y, x + m.width, y + m.height))
    mask.paste(Image.composite(m, region, m), (x, y))
body = backdrop.copy()
body.putalpha(Image.fromarray(__import__("numpy").minimum(__import__("numpy").asarray(mask), __import__("numpy").asarray(backdrop.split()[3]))))
frame.alpha_composite(body)
for c, x, y in placed:  # 3. edges, highlight, frosting
    frame.alpha_composite(load(c, "over"), (x, y))
frame.convert("RGB").save(out)
