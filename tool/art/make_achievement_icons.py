#!/usr/bin/env python
"""Compose the 10 Play Games achievement icons (512x512 opaque PNG) from a
single SDXL-generated blank gold badge medallion, recolored per tier via
hue-rotation, with a simple PIL-drawn glyph (star / lightning / check /
level-icon) and, where useful, a number rendered with a real TTF (Impact) --
text from SDXL is unreliable, but PIL text rendering is exact.

Usage:
    python tool/art/make_achievement_icons.py
"""
import os
import colorsys
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BASE_PATH = os.path.join(ROOT, "art", "raw", "badge_base.png")
OUT_DIR = os.path.join(ROOT, "store", "achievements")
FONT_PATH = "C:/Windows/Fonts/impact.ttf"
SIZE = 512
# The big blank disc in the top-left of the SDXL grid, measured on the image (x 4..272,
# y 10..289 -- slightly oval). An elliptical mask cuts it cleanly out of the neighbouring
# badges, which the old rectangular badge_base_crop.png did not: it was off-centre, clipped
# at the right/bottom and had a neighbour badge bleeding in.
DISC_CX, DISC_CY, DISC_RX, DISC_RY = 138.0, 149.5, 131.0, 137.0
GLYPH_OUTLINE = (60, 30, 0, 255)  # dark rim so white glyphs stay visible on silver
MARGIN_FRAC = 0.04  # breathing room so the rim never touches the icon edge


def load_badge_rgba():
    src = Image.open(BASE_PATH).convert("RGB")
    scale = 4  # supersampled mask for an anti-aliased rim
    mask = Image.new("L", (src.width * scale, src.height * scale), 0)
    ImageDraw.Draw(mask).ellipse(
        ((DISC_CX - DISC_RX) * scale, (DISC_CY - DISC_RY) * scale,
         (DISC_CX + DISC_RX) * scale, (DISC_CY + DISC_RY) * scale), fill=255)
    mask = mask.resize(src.size, Image.LANCZOS)
    disc = src.convert("RGBA")
    disc.putalpha(mask)
    box = (round(DISC_CX - DISC_RX), round(DISC_CY - DISC_RY),
           round(DISC_CX + DISC_RX), round(DISC_CY + DISC_RY))
    disc = disc.crop(box)
    inner = round(SIZE * (1 - 2 * MARGIN_FRAC))
    disc = disc.resize((inner, inner), Image.LANCZOS)
    rgba = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    off = (SIZE - inner) // 2
    rgba.paste(disc, (off, off), disc)
    return rgba


def recolor(badge_rgba: Image.Image, hue_shift: float, sat_scale: float = 1.0, val_scale: float = 1.0):
    """Rotate hue (0..1 fraction of 360deg) and scale saturation/value to
    turn the gold badge into bronze/silver/platinum/teal/etc while keeping
    its shading and rim highlights intact."""
    arr = np.asarray(badge_rgba.convert("RGBA")).astype(np.float32) / 255.0
    r, g, b, a = arr[..., 0], arr[..., 1], arr[..., 2], arr[..., 3]
    h, s, v = np.vectorize(colorsys.rgb_to_hsv)(r, g, b)
    h = (h + hue_shift) % 1.0
    s = np.clip(s * sat_scale, 0, 1)
    v = np.clip(v * val_scale, 0, 1)
    r2, g2, b2 = np.vectorize(colorsys.hsv_to_rgb)(h, s, v)
    out = np.stack([r2, g2, b2, a], axis=-1)
    out = (out * 255).astype(np.uint8)
    return Image.fromarray(out, mode="RGBA")


def draw_star(draw, cx, cy, r_outer, color, points=5, rotation=-90, inner_ratio=0.45):
    import math
    r_inner = r_outer * inner_ratio
    angle = math.radians(rotation)
    step = math.pi / points
    pts = []
    for i in range(points * 2):
        rad = r_outer if i % 2 == 0 else r_inner
        a = angle + i * step
        pts.append((cx + rad * math.cos(a), cy + rad * math.sin(a)))
    draw.polygon(pts, fill=color, outline=GLYPH_OUTLINE, width=6)


def draw_lightning(draw, cx, cy, scale, color):
    pts = [
        (cx - 0.12 * scale, cy - 0.5 * scale),
        (cx + 0.15 * scale, cy - 0.5 * scale),
        (cx - 0.05 * scale, cy - 0.05 * scale),
        (cx + 0.2 * scale, cy - 0.05 * scale),
        (cx - 0.15 * scale, cy + 0.5 * scale),
        (cx + 0.02 * scale, cy + 0.05 * scale),
        (cx - 0.25 * scale, cy + 0.05 * scale),
    ]
    draw.polygon(pts, fill=color, outline=GLYPH_OUTLINE, width=6)


def draw_check(draw, cx, cy, scale, color, width_frac=0.09):
    w = scale * width_frac
    pts = [(cx - 0.32 * scale, cy + 0.02 * scale),
           (cx - 0.08 * scale, cy + 0.28 * scale),
           (cx + 0.35 * scale, cy - 0.28 * scale)]
    draw.line(pts, fill=color, width=int(w), joint="curve")
    r = w / 2
    for p in (pts[0], pts[-1]):
        draw.ellipse((p[0] - r, p[1] - r, p[0] + r, p[1] + r), fill=color)


def draw_level_icon(draw, cx, cy, scale, color):
    """A simple 'steady hand' glyph: a horizontal balance line with a
    centered dot, like a level/spirit-level bubble."""
    w = scale * 0.06
    draw.line((cx - 0.4 * scale, cy, cx + 0.4 * scale, cy), fill=color, width=int(w))
    r = scale * 0.09
    draw.ellipse((cx - r, cy - r, cx + r, cy + r), fill=color)
    for x in (cx - 0.4 * scale, cx + 0.4 * scale):
        tr = scale * 0.05
        draw.ellipse((x - tr, cy - tr, x + tr, cy + tr), fill=color)


def draw_flag(draw, cx, cy, scale, color):
    pole_w = scale * 0.045
    draw.rectangle((cx - 0.28 * scale - pole_w, cy - 0.35 * scale,
                     cx - 0.28 * scale, cy + 0.35 * scale), fill=color)
    pts = [(cx - 0.28 * scale, cy - 0.32 * scale),
           (cx + 0.32 * scale, cy - 0.18 * scale),
           (cx - 0.28 * scale, cy - 0.02 * scale)]
    draw.polygon(pts, fill=color)


def add_number(im, text, cy_frac, color, size_frac=0.24):
    draw = ImageDraw.Draw(im)
    font = ImageFont.truetype(FONT_PATH, int(SIZE * size_frac))
    bbox = draw.textbbox((0, 0), text, font=font, stroke_width=int(SIZE * 0.012))
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    x = SIZE / 2 - tw / 2 - bbox[0]
    y = SIZE * cy_frac - th / 2 - bbox[1]
    draw.text((x, y), text, font=font, fill=color,
               stroke_width=int(SIZE * 0.012), stroke_fill=(60, 30, 0, 255))


def compose(badge, glyph_fn=None, glyph_color=(255, 255, 255, 255), glyph_scale=0.5, glyph_cy=0.5,
            number=None, number_color=(255, 255, 255, 255), number_cy=0.6):
    im = badge.copy()
    draw = ImageDraw.Draw(im)
    cx, cy = SIZE / 2, SIZE * glyph_cy
    if glyph_fn:
        glyph_fn(draw, cx, cy, SIZE * glyph_scale, glyph_color)
    if number:
        add_number(im, number, number_cy, number_color)
    # Flatten to opaque (white) background -- achievement icons need no alpha.
    bg = Image.new("RGB", (SIZE, SIZE), (255, 255, 255))
    bg.paste(im, (0, 0), im)
    return bg


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    base = load_badge_rgba()

    tiers = [
        # name, hue_shift, sat_scale, val_scale
        ("bronze", 0.02, 0.75, 0.72),
        ("silver", 0.0, 0.06, 1.05),
        ("gold", 0.0, 1.0, 1.0),
        ("platinum", 0.5, 0.35, 1.05),
        ("diamond", 0.55, 0.55, 1.05),
        ("green", 0.28, 0.85, 0.95),
        ("blue", 0.58, 0.75, 0.95),
        ("purple", 0.75, 0.65, 0.9),
        ("teal", 0.45, 0.7, 0.95),
        ("red", 0.89, 1.0, 0.88),
    ]
    palette = {name: recolor(base, hue, sat, val) for name, hue, sat, val in tiers}

    jobs = [
        ("achievement_first_series", palette["green"], draw_flag, "1st"),
        ("achievement_under_350", palette["bronze"], draw_lightning, "350"),
        ("achievement_under_300", palette["silver"], draw_lightning, "300"),
        ("achievement_under_250", palette["gold"], draw_lightning, "250"),
        ("achievement_under_220", palette["platinum"], draw_lightning, "220"),
        ("achievement_series_10", palette["blue"], draw_star, "10"),
        ("achievement_series_50", palette["purple"], draw_star, "50"),
        ("achievement_series_200", palette["diamond"], draw_star, "200"),
        ("achievement_steady_hand", palette["teal"], draw_level_icon, None),
        ("achievement_flawless", palette["red"], draw_check, None),
    ]

    for name, badge, glyph_fn, number in jobs:
        if number and glyph_fn in (draw_lightning,):
            out = compose(badge, glyph_fn=glyph_fn, glyph_color=(255, 255, 255, 255),
                           glyph_scale=0.34, glyph_cy=0.35,
                           number=number, number_color=(80, 40, 0, 255), number_cy=0.66)
        elif number and glyph_fn in (draw_star,):
            out = compose(badge, glyph_fn=glyph_fn, glyph_color=(255, 255, 255, 200),
                           glyph_scale=0.36, glyph_cy=0.47,
                           number=number, number_color=(80, 40, 0, 255), number_cy=0.5)
        elif number:  # flag / first series
            out = compose(badge, glyph_fn=glyph_fn, glyph_color=(255, 255, 255, 255),
                           glyph_scale=0.55, glyph_cy=0.42,
                           number=number, number_color=(60, 60, 0, 255), number_cy=0.68)
        else:
            out = compose(badge, glyph_fn=glyph_fn, glyph_color=(255, 255, 255, 255),
                           glyph_scale=0.6, glyph_cy=0.5)
        path = os.path.join(OUT_DIR, f"{name}.png")
        out.save(path, "PNG")
        print(f"wrote {path}")


if __name__ == "__main__":
    main()
