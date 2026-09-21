#!/usr/bin/env python
"""Build a contact sheet: composite a list of images on a grass-green
background in a grid, so a batch of assets can be visually reviewed at once.

Usage:
    python tool/art/contact_sheet.py out.png img1.png img2.png ...
    python tool/art/contact_sheet.py out.png --dir art/raw --cols 5
"""
import sys
import os
import glob
from PIL import Image

BG = (58, 110, 48)  # mid-dark green, matches grass_tile target tone


def build(paths, out_path, cols=5, cell=280, pad=12):
    n = len(paths)
    rows = (n + cols - 1) // cols
    cw = cell + pad
    sheet = Image.new("RGB", (cols * cw + pad, rows * cw + pad), BG)
    for i, p in enumerate(paths):
        try:
            im = Image.open(p).convert("RGBA")
        except Exception as e:
            print(f"skip {p}: {e}")
            continue
        im.thumbnail((cell, cell))
        x = pad + (i % cols) * cw + (cell - im.width) // 2
        y = pad + (i // cols) * cw + (cell - im.height) // 2
        sheet.paste(im, (x, y), im)
    sheet.save(out_path)
    print(f"wrote {out_path} ({n} images, {cols}x{rows})")


if __name__ == "__main__":
    args = sys.argv[1:]
    out = args[0]
    rest = args[1:]
    cols = 5
    if "--cols" in rest:
        idx = rest.index("--cols")
        cols = int(rest[idx + 1])
        del rest[idx:idx + 2]
    if "--dir" in rest:
        idx = rest.index("--dir")
        d = rest[idx + 1]
        del rest[idx:idx + 2]
        paths = sorted(glob.glob(os.path.join(d, "*.png"))) + sorted(glob.glob(os.path.join(d, "*.webp")))
    else:
        paths = rest
    build(paths, out, cols=cols)
