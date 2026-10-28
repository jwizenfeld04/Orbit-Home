"""Builds the launcher's wallpaper assets from tools/themes.json, for every theme and time of day:

  wall/<theme>/<id>.jpg        the wallpaper, 1920x1080 (id = the source file's name)
  wall/<theme>/<id>_blur.jpg   blurred copy behind the glass panels (quarter size)
  wall/<theme>/<id>_clock.jpg  frosted copy seen through the glass clock (the clock band only)
  themes.json                     names, captions and credits for the app
  ../res/drawable-xhdpi/banner.png  the app's own tile

The glass digits come from glass_glyphs.py. Run in a container with pillow + numpy:
  python make_assets.py <wallpapers_dir> <themes.json> <font.ttf> <out_assets_dir>
"""
import json
import os
import shutil
import sys

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont

Image.MAX_IMAGE_PIXELS = None
WALL_DIR = THEMES = FONT = OUT = None  # set by main() from the command line
PHASES = ["dawn", "day", "golden", "dusk", "night"]
MAGNIFY = 1.06
MAG_ORIGIN = (960, 330)
CLOCK_REGION = (0, 100, 1920, 560)  # the band the clock can cover (glass_glyphs.py meta says the same)


def save_jpg(im, path, q=90):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path, quality=q, optimize=True)


def crop_16x9(im, y=0.5):
    """Full width (or height), positioned vertically by y (0 top .. 1 bottom)."""
    w, h = im.size
    ch = int(w * 9 / 16)
    if ch <= h:
        top = int((h - ch) * y)
        return im.crop((0, top, w, top + ch))
    cw = int(h * 16 / 9)
    return im.crop(((w - cw) // 2, 0, (w - cw) // 2 + cw, h))


def photo_id(spec):
    return os.path.splitext(os.path.basename(spec["src"]))[0].replace("-4k", "")


def build(theme, pid, spec, src_dir=None, d=None):
    """d = where the three files go (default: the app's assets/wall/<theme>)."""
    src = Image.open(os.path.join(src_dir or WALL_DIR, spec["src"])).convert("RGB")
    wall = crop_16x9(src, spec.get("y", 0.5)).resize((1920, 1080), Image.LANCZOS)
    d = d or f"{OUT}/wall/{theme}"
    save_jpg(wall, f"{d}/{pid}.jpg", 92)

    # Glass panels: blur(28px) saturate(160%), at quarter size (it is blurred anyway).
    small = wall.resize((480, 270), Image.LANCZOS)
    small = ImageEnhance.Color(small).enhance(1.6).filter(ImageFilter.GaussianBlur(7))
    save_jpg(small, f"{d}/{pid}_blur.jpg", 92)

    # Seen through the glass clock: frosted (soft blur), a touch magnified and brighter.
    # The bending at the digit edges is done per pixel from each digit's displacement map.
    cx, cy = MAG_ORIGIN
    inv = 1 / MAGNIFY
    mag = wall.transform((1920, 1080), Image.AFFINE, (inv, 0, cx - cx * inv, 0, inv, cy - cy * inv), Image.BICUBIC)
    mag = mag.filter(ImageFilter.GaussianBlur(3.5))
    mag = ImageEnhance.Color(mag).enhance(1.25)
    mag = ImageEnhance.Brightness(mag).enhance(1.12)
    save_jpg(mag.crop(CLOCK_REGION), f"{d}/{pid}_clock.jpg", 92)


def banner():
    wall = Image.open(f"{OUT}/wall/orbit/night.jpg").convert("RGB")
    b = wall.crop((240, 300, 1680, 1110)).resize((640, 360), Image.LANCZOS)
    b = ImageEnhance.Brightness(b).enhance(1.15)
    font = ImageFont.truetype(FONT, 132)
    font.set_variation_by_axes([300])
    d = ImageDraw.Draw(b)
    d.text((322, 150), "Orbit", font=font, fill=(0, 0, 0), anchor="mm")
    d.text((320, 146), "Orbit", font=font, fill=(255, 255, 255), anchor="mm")
    os.makedirs(f"{OUT}/../res/drawable-xhdpi", exist_ok=True)
    b.save(f"{OUT}/../res/drawable-xhdpi/banner.png", optimize=True)


def main():
    global WALL_DIR, THEMES, FONT, OUT
    WALL_DIR, THEMES, FONT, OUT = sys.argv[1:5]
    themes = json.load(open(THEMES))
    app = []
    for tid, t in themes.items():
        if tid.startswith("_"):
            continue
        shutil.rmtree(f"{OUT}/wall/{tid}", ignore_errors=True)  # no leftovers from removed photos
        entry = {"id": tid, "name": t["name"], "photos": []}
        ids = set()
        for spec in t["photos"]:
            pid = photo_id(spec)
            assert pid not in ids, f"duplicate photo id {tid}/{pid}"
            assert spec["phase"] in PHASES, spec
            ids.add(pid)
            build(tid, pid, spec)
            entry["photos"].append({"id": pid, "phase": spec["phase"], "caption": spec.get("caption", ""),
                                    "credit": spec.get("credit", ""), "license": spec.get("license", "")})
        app.append(entry)
        print("theme", tid, len(ids), "photos")
    with open(f"{OUT}/themes.json", "w") as f:
        json.dump(app, f, indent=1, ensure_ascii=False)
    banner()


if __name__ == "__main__":
    main()
