#!/usr/bin/env python
"""Turn a target-face PNG (round character on a light background) into a
perfect circular WebP sprite: the face's bounding circle touches the image
edges (fills the frame), 2px feathered alpha edge, fixed output size.

Also normalizes brightness/saturation across a set of faces so they read as
equally salient reaction-test stimuli (fairness constraint), and prints a
table of the per-face stats it computed and applied.

Usage:
    python tool/art/circle_crop.py art/raw/target_face_*.png --out-dir art/final --size 256
"""
import sys
import glob
import colorsys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

sys.path.insert(0, __file__.rsplit("/", 1)[0].rsplit("\\", 1)[0])
from remove_white_bg import flood_fill_bg_alpha, despeckle_alpha  # noqa: E402


def bounding_circle(alpha: np.ndarray):
    ys, xs = np.where(alpha > 10)
    if len(xs) == 0:
        h, w = alpha.shape
        return w / 2, h / 2, min(w, h) / 2
    cx, cy = xs.mean(), ys.mean()
    # radius = furthest opaque pixel from centroid (covers the whole head)
    r = np.sqrt((xs - cx) ** 2 + (ys - cy) ** 2).max()
    return cx, cy, r


def crop_to_circle(im_rgba: Image.Image, size: int, feather_px: int = 2, pad_frac: float = 0.03):
    alpha = np.asarray(im_rgba.split()[-1])
    cx, cy, r = bounding_circle(alpha)
    r *= (1.0 + pad_frac)  # tiny pad so the feather doesn't clip the head

    # Crop a square around the circle in source-resolution space
    left, top = cx - r, cy - r
    box_size = r * 2
    src = im_rgba.crop((int(left), int(top), int(left + box_size), int(top + box_size)))
    # Pad if crop went out of bounds (Image.crop pads with transparent-ish black; fix below)
    if src.size != (int(box_size), int(box_size)):
        fixed = Image.new("RGBA", (int(box_size), int(box_size)), (0, 0, 0, 0))
        fixed.paste(src, (0, 0))
        src = fixed

    src = src.resize((size, size), Image.LANCZOS)

    # Circular mask so the face fills the frame exactly (touches all 4 edges)
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.ellipse((0, 0, size - 1, size - 1), fill=255)
    if feather_px > 0:
        mask = mask.filter(ImageFilter.GaussianBlur(feather_px))

    out_alpha = np.minimum(np.asarray(src.split()[-1], dtype=np.int16),
                            np.asarray(mask, dtype=np.int16)).astype(np.uint8)
    out = src.copy()
    out.putalpha(Image.fromarray(out_alpha, mode="L"))
    return out


def luminance_saturation(im_rgba: Image.Image):
    arr = np.asarray(im_rgba.convert("RGBA"), dtype=np.float32) / 255.0
    a = arr[..., 3]
    mask = a > 0.5
    if mask.sum() == 0:
        return 0.5, 0.5
    rgb = arr[..., :3][mask]
    lum = (0.299 * rgb[:, 0] + 0.587 * rgb[:, 1] + 0.114 * rgb[:, 2]).mean()
    sats = []
    for r, g, b in rgb[::max(1, len(rgb) // 2000)]:  # sample for speed
        _, s, _ = colorsys.rgb_to_hsv(r, g, b)
        sats.append(s)
    sat = float(np.mean(sats)) if sats else 0.5
    return float(lum), sat


def normalize_brightness(im_rgba: Image.Image, target_lum: float, cur_lum: float, max_scale=0.05):
    if cur_lum <= 0:
        return im_rgba
    scale = target_lum / cur_lum
    scale = max(1 - max_scale, min(1 + max_scale, scale))
    arr = np.asarray(im_rgba.convert("RGBA"), dtype=np.float32)
    rgb = arr[..., :3] * scale
    rgb = np.clip(rgb, 0, 255)
    arr[..., :3] = rgb
    return Image.fromarray(arr.astype(np.uint8), mode="RGBA")


def process_set(paths, out_dir, size=256, prefix="target_face_"):
    import os
    os.makedirs(out_dir, exist_ok=True)
    circles = []
    for p in paths:
        im = Image.open(p)
        rgba = flood_fill_bg_alpha(im, tolerance=30, feather=1)
        rgba = despeckle_alpha(rgba)
        circ = crop_to_circle(rgba, size)
        circles.append((p, circ))

    stats = [(p, *luminance_saturation(c)) for p, c in circles]
    med_lum = float(np.median([s[1] for s in stats]))
    print(f"{'file':<28}{'lum':>8}{'sat':>8}{'adj_lum':>10}")
    results = []
    for (p, c), (_, lum, sat) in zip(circles, stats):
        c2 = normalize_brightness(c, med_lum, lum)
        lum2, _ = luminance_saturation(c2)
        print(f"{p:<28}{lum:8.3f}{sat:8.3f}{lum2:10.3f}")
        results.append((p, c2))

    for i, (p, c2) in enumerate(results, start=1):
        out_path = f"{out_dir}/{prefix}{i:02d}.webp"
        c2.save(out_path, "WEBP", quality=90)
        print(f"wrote {out_path}")
    return results


if __name__ == "__main__":
    args = sys.argv[1:]
    out_dir = "art/final"
    size = 256
    if "--out-dir" in args:
        i = args.index("--out-dir")
        out_dir = args[i + 1]
        del args[i:i + 2]
    if "--size" in args:
        i = args.index("--size")
        size = int(args[i + 1])
        del args[i:i + 2]
    paths = []
    for a in args:
        paths.extend(sorted(glob.glob(a)))
    process_set(paths, out_dir, size=size)
