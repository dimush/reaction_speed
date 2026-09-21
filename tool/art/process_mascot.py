#!/usr/bin/env python
"""Process a raw mascot/sticker PNG (character on light background) into a
transparent-background WebP master: edge flood-fill bg removal, despeckle,
trim to content bbox with margin, resize/pad to a square canvas.

Usage:
    python tool/art/process_mascot.py art/raw/mascot_tier_1_sloth.png art/final/mascot_tier_1_sloth.webp --size 512
"""
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from remove_white_bg import flood_fill_bg_alpha, despeckle_alpha  # noqa: E402
from PIL import Image


def process(src, dst, size=512, margin_frac=0.04, tolerance=30):
    im = Image.open(src)
    rgba = flood_fill_bg_alpha(im, tolerance=tolerance, feather=2)
    rgba = despeckle_alpha(rgba)

    bbox = rgba.getbbox()
    if bbox:
        rgba = rgba.crop(bbox)

    w, h = rgba.size
    side = max(w, h) * (1 + margin_frac * 2)
    canvas = Image.new("RGBA", (int(side), int(side)), (0, 0, 0, 0))
    canvas.paste(rgba, (int((side - w) / 2), int((side - h) / 2)), rgba)
    canvas = canvas.resize((size, size), Image.LANCZOS)
    canvas.save(dst, "WEBP", quality=92)
    print(f"wrote {dst}")


if __name__ == "__main__":
    args = sys.argv[1:]
    src, dst = args[0], args[1]
    size = 512
    if "--size" in args:
        size = int(args[args.index("--size") + 1])
    process(src, dst, size=size)
