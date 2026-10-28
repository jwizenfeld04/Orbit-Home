"""Builds a custom theme: photos that live on the TV instead of inside the app (see README, "Custom themes").

  python custom_theme.py <theme_dir> <out_dir>

<theme_dir>/theme.json is one theme, like an entry of tools/themes.json plus its id:
  {"id": "starwars", "name": "Star Wars", "photos": [{"src": "src/x.jpg", "phase": "night", "y": 0.5,
   "caption": "...", "credit": "...", "license": "..."}]}
src paths are relative to <theme_dir>. Writes <out_dir>/<id>/: <pid>.jpg, <pid>_blur.jpg, <pid>_clock.jpg
for each photo and the theme.json the app reads.
"""
import json
import os
import re
import shutil
import sys

import make_assets as m


def main():
    src_dir, out_dir = sys.argv[1:3]
    t = json.load(open(os.path.join(src_dir, "theme.json")))
    tid = t["id"]
    assert re.fullmatch(r"[a-z0-9_-]{1,40}", tid), f"theme id must be lowercase letters/digits/-/_: {tid}"
    d = os.path.join(out_dir, tid)
    shutil.rmtree(d, ignore_errors=True)
    entry = {"id": tid, "name": t["name"], "photos": []}
    for spec in t["photos"]:
        pid = m.photo_id(spec)
        assert pid not in {p["id"] for p in entry["photos"]}, f"duplicate photo id {pid}"
        assert spec["phase"] in m.PHASES, spec
        m.build(tid, pid, spec, src_dir, d)
        entry["photos"].append({k: spec.get(k, "") for k in ("phase", "caption", "credit", "license")} | {"id": pid})
    with open(os.path.join(d, "theme.json"), "w") as f:
        json.dump(entry, f, indent=1, ensure_ascii=False)
    print("theme", tid, len(entry["photos"]), "photos ->", d)


if __name__ == "__main__":
    main()
