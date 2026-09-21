#!/usr/bin/env python
"""Composite one or more RGBA images onto a dark-green background so
alpha-edge quality (white halos, jaggies) can be inspected visually.

Usage:
    python tool/art/composite_check.py out.png img1.webp img2.webp ...
    python tool/art/composite_check.py out.png --dir art/final --cols 5
"""
import sys
import os
import glob
from PIL import Image

DARK_GREEN = (34, 68, 30)


def build(paths, out_path, cols=5, cell=260, pad=10):
    n = len(paths)
    rows = (n + cols - 1) // cols
    cw = cell + pad
    sheet = Image.new("RGB", (cols * cw + pad, rows * cw + pad), DARK_GREEN)
    for i, p in enumerate(paths):
        im = Image.open(p).convert("RGBA")
        im.thumbnail((cell, cell))
        x = pad + (i % cols) * cw + (cell - im.width) // 2
        y = pad + (i // cols) * cw + (cell - im.height) // 2
        sheet.paste(im, (x, y), im)
    sheet.save(out_path)
    print(f"wrote {out_path} ({n} images)")


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
        paths = sorted(glob.glob(os.path.join(d, "*.webp"))) + sorted(glob.glob(os.path.join(d, "*.png")))
    else:
        paths = rest
    build(paths, out, cols=cols)
