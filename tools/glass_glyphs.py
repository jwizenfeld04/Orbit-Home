"""Liquid-glass clock digits, modelled on the iOS 26 Lock Screen "Glass" clock: tall heavy digits that
look like thick rounded glass. Each digit is treated as a slab with a rounded bevel; from that shape we
precompute everything the TV needs:

  <name>_mask.png    where the glass is (alpha)
  <name>_disp.png    refraction: where to look in the wallpaper for each pixel (R = dx, G = dy; 128 = 0)
  <name>_over.png    light on the glass: highlights on the top/left bevel, soft shade at the bottom,
                     frosting (premultiplied white/black, straight alpha PNG)
  <name>_shadow.png  a faint shadow under the slab

The TV (ClockRenderer.java, reference: preview_glass.py) samples the frosted wallpaper through the
displacement map, then lays the light on top.

It also makes a small set for the weather corner (glyph/wx/: digits, degree sign, minus, and weather
icons), same glass at about a quarter of the size; WeatherGlass.java draws those.

  python glass_glyphs.py <fonts_dir> <out_assets_dir>
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage

FONTS, OUT = sys.argv[1:3]
WEIGHTS = {500: "Medium", 600: "SemiBold", 700: "Bold", 800: "ExtraBold"}
CHARS = {"0": "0", "1": "1", "2": "2", "3": "3", "4": "4", "5": "5", "6": "6", "7": "7", "8": "8", "9": "9", ":": "colon"}
FONT_SIZE = 330
BASELINE = 440
LETTER_SPACING = 4
PAD = 40
SS = 2  # supersampling for clean edges

BEVEL = 22.0        # px: width of the rounded edge
REFRACT = 34.0      # px: how far the edge bends the wallpaper
DISP_SCALE = 3.0    # PNG units per px of displacement
LIGHT_AZ, LIGHT_EL = math.radians(235), math.radians(48)  # from the upper left


CLOCK = {"bevel": BEVEL, "refract": REFRACT, "round": 3.5, "shadow_blur": 10, "shadow_dy": 8}
# The weather corner: about a quarter of the clock's size, so a thinner bevel and less bending.
WX = {"bevel": 6.0, "refract": 10.0, "round": 1.2, "shadow_blur": 3, "shadow_dy": 2}
WX_FONT_SIZE, WX_PAD = 84, 12
WX_CHARS = dict(CHARS, **{"°": "deg", "-": "minus"})
del WX_CHARS[":"]
WX_ICON = 76  # px, the icon box


def glyph(font, ch, ascent, descent, pad=PAD, p=CLOCK):
    adv = font.getlength(ch) / SS
    w = int(math.ceil(adv)) + 2 * pad
    h = ascent + descent + 2 * pad
    big = Image.new("L", (w * SS, h * SS), 0)
    ImageDraw.Draw(big).text((pad * SS, (pad + ascent) * SS), ch, font=font, fill=255, anchor="ls")
    return (adv,) + slab(big, w, h, p)


def slab(big, w, h, p):
    """big: the shape's mask at SS x size. Returns mask, displacement, light overlay and shadow images."""
    bevel, refract = p["bevel"], p["refract"]
    A_big = np.asarray(big, np.float32) / 255
    A = np.asarray(big.resize((w, h), Image.LANCZOS), np.float32) / 255

    # Height: a rounded (quarter-circle) bevel `bevel` px wide, flat on top.
    d = ndimage.distance_transform_edt(A_big > 0.5) / SS
    d = np.asarray(Image.fromarray(d.astype(np.float32)).resize((w, h), Image.BILINEAR))
    t = np.clip(d / bevel, 0, 1)
    height = np.sqrt(1 - (1 - t) ** 2) * bevel
    height = ndimage.gaussian_filter(height, p["round"]) * (A > 0.02)  # round off creases at corners
    gy, gx = np.gradient(height)
    n = np.dstack([-gx, -gy, np.ones_like(gx)])
    n /= np.linalg.norm(n, axis=2, keepdims=True)
    nx, ny, nz = n[..., 0], n[..., 1], n[..., 2]
    slope = np.clip(1 - nz, 0, 1)  # 0 on the flat top, ~1 at the very edge

    # Refraction: the bevel bends light, so the wallpaper seen there comes from further in (magnified,
    # stretched along the edge). The flat face shows the wallpaper almost straight through.
    dx = -nx * refract * (0.35 + 0.65 * slope)
    dy = -ny * refract * (0.35 + 0.65 * slope)
    disp = np.dstack([
        np.clip(128 + dx * DISP_SCALE, 0, 255), np.clip(128 + dy * DISP_SCALE, 0, 255), np.full_like(dx, 128),
    ]).astype(np.uint8)

    L = np.array([math.cos(LIGHT_AZ) * math.cos(LIGHT_EL), math.sin(LIGHT_AZ) * math.cos(LIGHT_EL), math.sin(LIGHT_EL)])
    lxy = (nx * L[0] + ny * L[1]) / np.maximum(np.hypot(nx, ny), 1e-6)  # +1: bevel faces the light
    H = L + np.array([0, 0, 1.0])
    H /= np.linalg.norm(H)
    ndoth = np.clip(nx * H[0] + ny * H[1] + nz * H[2], 0, 1)

    spec = np.clip(ndoth ** 30 * 1.25, 0, 1) * slope ** 0.4           # crisp glint on the lit bevel
    lit = np.clip(lxy, 0, 1) * slope ** 0.7 * 0.6                    # top/left bevel catches light
    shade = np.clip(-lxy, 0, 1) * slope ** 0.9 * 0.28                 # bottom/right bevel goes darker
    edge = np.clip(A - ndimage.grey_erosion(A, size=(3, 3)), 0, 1) * 0.65
    glint = slope ** 1.5 * 0.18  # all-round sheen on the bevel: reads as glass on dark wallpapers  # thin bright outline
    ys = np.linspace(0, 1, h, dtype=np.float32)[:, None]
    frost = np.zeros_like(A)  # frosting is added on the TV (Glass strength setting), see ClockRenderer               # milky body, a bit brighter on top

    white = 1 - (1 - frost) * (1 - lit) * (1 - spec) * (1 - edge) * (1 - glint)
    white *= A
    shade *= A
    a = white + shade * (1 - white)
    lum = np.where(a > 1e-4, white / np.maximum(a, 1e-4), 0)
    v = (np.clip(lum, 0, 1) * 255 + 0.5).astype(np.uint8)
    over = np.dstack([v, v, v, (np.clip(a, 0, 1) * 255 + 0.5).astype(np.uint8)])

    sh = ndimage.shift(ndimage.gaussian_filter(A, p["shadow_blur"]), (p["shadow_dy"], 0), order=1) * 0.22 * (1 - A)

    def alpha_png(arr, color):
        out = np.zeros((h, w, 4), np.uint8)
        out[..., :3] = color
        out[..., 3] = (np.clip(arr, 0, 1) * 255 + 0.5).astype(np.uint8)
        return Image.fromarray(out, "RGBA")

    return alpha_png(A, 255), Image.fromarray(disp, "RGB"), Image.fromarray(over, "RGBA"), alpha_png(sh, 0)


def icon_mask(name):
    """Weather icons as solid shapes (drawn at SS x), turned into glass like the digits."""
    n, s = WX_ICON + 2 * WX_PAD, SS
    im = Image.new("L", (n * s, n * s), 0)
    dr = ImageDraw.Draw(im)
    c = n / 2

    def circle(x, y, r, fill=255, img=None):
        (ImageDraw.Draw(img) if img else dr).ellipse(((x - r) * s, (y - r) * s, (x + r) * s, (y + r) * s), fill=fill)

    def line(x0, y0, x1, y1, wd, img=None):
        d2 = ImageDraw.Draw(img) if img else dr
        d2.line((x0 * s, y0 * s, x1 * s, y1 * s), fill=255, width=int(wd * s))
        circle(x0, y0, wd / 2, img=img)
        circle(x1, y1, wd / 2, img=img)

    def sun(x, y, r, img=None):
        circle(x, y, r, img=img)
        for i in range(8):
            a = i * math.pi / 4
            line(x + math.cos(a) * (r + 7), y + math.sin(a) * (r + 7), x + math.cos(a) * (r + 14), y + math.sin(a) * (r + 14), 5.5, img)

    def moon(x, y, r, img=None):
        circle(x, y, r, img=img)
        circle(x + r * 0.45, y - r * 0.38, r * 0.86, fill=0, img=img)

    def cloud(x, y, k=1.0, img=None):
        circle(x - 15 * k, y + 4 * k, 13 * k, img=img)
        circle(x + 2 * k, y - 7 * k, 19 * k, img=img)
        circle(x + 18 * k, y + 4 * k, 13 * k, img=img)
        (ImageDraw.Draw(img) if img else dr).rounded_rectangle(
            ((x - 28 * k) * s, (y + 2 * k) * s, (x + 31 * k) * s, (y + 17 * k) * s), radius=8 * k * s, fill=255)

    def behind_cloud(body, cx, cy, k):
        """Sun or moon peeking out from behind a cloud, with a gap so both read as separate pieces of glass."""
        back = Image.new("L", im.size, 0)
        body(back)
        cl = Image.new("L", im.size, 0)
        cloud(cx, cy, k, img=cl)
        gap = Image.fromarray((ndimage.binary_dilation(np.asarray(cl) > 127, iterations=int(4 * s)) * 255).astype(np.uint8))
        out = np.maximum(np.asarray(back) * (np.asarray(gap) < 128), np.asarray(cl))
        return Image.fromarray(out.astype(np.uint8))

    if name == "sun":
        sun(c, c, 17)
    elif name == "moon":
        moon(c, c, 26)
    elif name == "cloud":
        cloud(c, c + 2)
    elif name in ("partly_day", "partly_night"):
        body = (lambda img: sun(c - 12, c - 11, 12, img)) if name == "partly_day" else (lambda img: moon(c - 10, c - 10, 17, img))
        im = behind_cloud(body, c + 7, c + 7, 0.85)
    elif name in ("rain", "snow", "storm"):
        cloud(c, c - 10)
        if name == "rain":
            for i in range(3):
                line(c - 13 + i * 13, c + 20, c - 18 + i * 13, c + 31, 5)
        elif name == "snow":
            for i in range(3):
                circle(c - 13 + i * 13, c + 25 + (i % 2) * 6, 4)
        else:
            dr.polygon([((c + x) * s, (c + 14 + y) * s) for x, y in
                        [(3, 0), (-8, 15), (0, 15), (-5, 29), (10, 10), (2, 10), (8, 0)]], fill=255)
    elif name == "fog":
        for i in range(3):
            line(c - 26 + i * 5, c - 14 + i * 14, c + 26 - i * 5, c - 14 + i * 14, 7)
    return im, n


def make_wx(fonts):
    """glyph/wx/: small glass digits for the temperature (all clock weights) and the weather icons."""
    d = f"{OUT}/glyph/wx"
    os.makedirs(d, exist_ok=True)
    for f in os.listdir(d):
        os.remove(os.path.join(d, f))
    meta = {"pad": WX_PAD, "dispScale": DISP_SCALE, "weights": {}, "icons": {}}

    def save(base, parts):
        for suffix, img in zip(("mask", "disp", "over", "shadow"), parts):
            img.save(f"{d}/{base}_{suffix}.png", optimize=True)

    for wt, style in WEIGHTS.items():
        font = ImageFont.truetype(os.path.join(fonts, f"BarlowCondensed-{style}.ttf"), WX_FONT_SIZE * SS)
        ascent, descent = (v // SS for v in font.getmetrics())
        info = {"ascent": ascent, "chars": {}}
        for ch, name in WX_CHARS.items():
            adv, *parts = glyph(font, ch, ascent, descent, WX_PAD, WX)
            save(f"w{wt}_{name}", parts)
            info["chars"][ch] = {"file": f"w{wt}_{name}", "advance": round(adv, 2)}
        meta["weights"][str(wt)] = info
    for name in ("sun", "moon", "cloud", "partly_day", "partly_night", "rain", "snow", "storm", "fog"):
        big, n = icon_mask(name)
        save(f"icon_{name}", slab(big, n, n, WX))
        meta["icons"][name] = {"file": f"icon_{name}", "size": n}
    with open(f"{d}/meta.json", "w") as f:
        json.dump(meta, f, indent=1)
    print("weather glass", len(meta["icons"]), "icons")


def main():
    meta = {"fontSize": FONT_SIZE, "baseline": BASELINE, "letterSpacing": LETTER_SPACING, "pad": PAD,
            "dispScale": DISP_SCALE, "clockRegion": [0, 100, 1920, 560],  # = make_assets.CLOCK_REGION
            "weights": {}}
    for wt, style in WEIGHTS.items():
        font = ImageFont.truetype(os.path.join(FONTS, f"BarlowCondensed-{style}.ttf"), FONT_SIZE * SS)
        ascent, descent = (v // SS for v in font.getmetrics())
        d = f"{OUT}/glyph/w{wt}"
        os.makedirs(d, exist_ok=True)
        for f in os.listdir(d):
            os.remove(os.path.join(d, f))
        info = {"ascent": ascent, "descent": descent, "chars": {}}
        for ch, name in CHARS.items():
            adv, mask, disp, over, shadow = glyph(font, ch, ascent, descent)
            mask.save(f"{d}/{name}_mask.png", optimize=True)
            disp.save(f"{d}/{name}_disp.png", optimize=True)
            over.save(f"{d}/{name}_over.png", optimize=True)
            shadow.save(f"{d}/{name}_shadow.png", optimize=True)
            info["chars"][ch] = {"file": name, "advance": round(adv, 2), "w": mask.width, "h": mask.height}
        meta["weights"][str(wt)] = info
        print("glyphs", wt)
    with open(f"{OUT}/glyph/meta.json", "w") as f:
        json.dump(meta, f, indent=1)
    make_wx(FONTS)


if __name__ == "__main__":
    main()
