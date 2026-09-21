#!/usr/bin/env python
"""Turn a raw grass photo/render into a seamlessly tileable, mid-dark,
low-contrast texture:
  1. offset the image by half width/height (wrap) so the four original
     corners meet in the center, creating a visible cross-seam
  2. blend a soft-edged copy over the seam to hide it
  3. reduce contrast and darken/desaturate slightly so red targets pop
  4. verify by tiling 3x3 and saving a check image

Usage:
    python tool/art/make_seamless_grass.py art/raw/grass_tile_raw.png art/final/grass_tile.webp
"""
import sys
import numpy as np
from PIL import Image, ImageEnhance, ImageChops, ImageFilter


def offset_wrap(im: Image.Image) -> Image.Image:
    w, h = im.size
    return ImageChops.offset(im, w // 2, h // 2)


def hide_seam(im: Image.Image) -> Image.Image:
    """After offset_wrap, the seam sits along the center cross. Blend a
    lightly-blurred copy in over a wide, very softly-feathered band around
    the seam so the transition reads as natural texture variation rather
    than a visible line."""
    w, h = im.size
    blurred = im.filter(ImageFilter.GaussianBlur(3))

    yy, xx = np.mgrid[0:h, 0:w]
    band = max(w, h) * 0.16
    dist_v = np.abs(xx - w / 2)
    dist_h = np.abs(yy - h / 2)
    dist = np.minimum(dist_v, dist_h)
    mask = np.clip(1 - dist / band, 0, 1) ** 2  # smoother falloff (no hard band edge)
    mask_img = Image.fromarray((mask * 255).astype(np.uint8), mode="L").filter(ImageFilter.GaussianBlur(24))

    return Image.composite(blurred, im, mask_img)


def tone_down(im: Image.Image, brightness=0.82, contrast=0.78, saturation=0.85) -> Image.Image:
    im = ImageEnhance.Brightness(im).enhance(brightness)
    im = ImageEnhance.Contrast(im).enhance(contrast)
    im = ImageEnhance.Color(im).enhance(saturation)
    return im


def tile_preview(im: Image.Image, n=3) -> Image.Image:
    w, h = im.size
    sheet = Image.new("RGB", (w * n, h * n))
    for j in range(n):
        for i in range(n):
            sheet.paste(im, (i * w, j * h))
    return sheet


if __name__ == "__main__":
    src, dst = sys.argv[1], sys.argv[2]
    im = Image.open(src).convert("RGB")
    im = im.resize((512, 512), Image.LANCZOS)
    im = offset_wrap(im)
    im = hide_seam(im)
    im = tone_down(im)
    im.save(dst, "WEBP", quality=85)
    print(f"wrote {dst}")
    prev = tile_preview(im, 3)
    prev_path = dst.rsplit(".", 1)[0] + "_tiled3x3_check.png"
    prev.save(prev_path)
    print(f"wrote {prev_path}")
