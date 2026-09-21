#!/usr/bin/env python
"""Compose the 1024x500 Play Store feature graphic from already-processed
assets: tiled grass background, a few target faces, the home dino mascot,
and the game title rendered with a real bold TTF (Impact), white fill with
a dark outline for legibility over the busy grass texture.

Usage:
    python tool/art/make_feature_graphic.py
"""
import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FINAL = os.path.join(ROOT, "art", "final")
OUT = os.path.join(ROOT, "store", "feature-graphic.png")
FONT_PATH = "C:/Windows/Fonts/impact.ttf"

W, H = 1024, 500


def tiled_grass(w, h):
    tile = Image.open(os.path.join(FINAL, "grass_tile.webp")).convert("RGB")
    tw, th = tile.size
    bg = Image.new("RGB", (w, h))
    for y in range(0, h, th):
        for x in range(0, w, tw):
            bg.paste(tile, (x, y))
    return bg


def main():
    bg = tiled_grass(W, H)

    # Slight vignette/darken at the very top for text legibility
    overlay = Image.new("L", (W, H), 0)
    od = ImageDraw.Draw(overlay)
    od.rectangle((0, 0, W, 150), fill=70)
    bg = Image.composite(Image.new("RGB", (W, H), (10, 20, 8)), bg, overlay)

    dino = Image.open(os.path.join(FINAL, "mascot_home_dino.webp")).convert("RGBA")
    dino_h = int(H * 0.86)
    scale = dino_h / dino.height
    dino = dino.resize((int(dino.width * scale), dino_h), Image.LANCZOS)
    bg.paste(dino, (W - dino.width - 10, H - dino.height), dino)

    faces = ["target_face_01", "target_face_06", "target_face_09"]
    positions = [(60, 320), (280, 240), (420, 350)]
    sizes = [130, 95, 85]
    for name, (x, y), s in zip(faces, positions, sizes):
        f = Image.open(os.path.join(FINAL, f"{name}.webp")).convert("RGBA")
        f = f.resize((s, s), Image.LANCZOS)
        bg.paste(f, (x, y), f)

    draw = ImageDraw.Draw(bg)
    title = "Reaction Speed"
    font = ImageFont.truetype(FONT_PATH, 92)
    bbox = draw.textbbox((0, 0), title, font=font, stroke_width=8)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    tx = 40 - bbox[0]
    ty = 30 - bbox[1]
    draw.text((tx, ty), title, font=font, fill=(255, 255, 255, 255),
               stroke_width=8, stroke_fill=(20, 40, 10, 255))

    sub_font = ImageFont.truetype(FONT_PATH, 34)
    subtitle = "Tap the red targets. Beat your time."
    sbbox = draw.textbbox((0, 0), subtitle, font=sub_font, stroke_width=5)
    sx = 44 - sbbox[0]
    sy = ty + th + 20 - sbbox[1]
    draw.text((sx, sy), subtitle, font=sub_font, fill=(255, 244, 200, 255),
               stroke_width=5, stroke_fill=(20, 40, 10, 255))

    bg.save(OUT, "PNG")
    print(f"wrote {OUT} size={bg.size}")


if __name__ == "__main__":
    main()
