#!/usr/bin/env python
"""Remove a plain light background from a raster image via edge flood-fill,
producing a soft, feathered alpha channel. Never a global brightness
threshold (that would eat white teeth/eyes/sneakers/etc inside the subject) -
only pixels connected to the image border through near-background colors
become transparent.

Usage as a library:
    from remove_white_bg import flood_fill_bg_alpha
    rgba = flood_fill_bg_alpha(im_rgb, tolerance=28, feather=2)
"""
import numpy as np
from scipy import ndimage
from PIL import Image, ImageFilter


def flood_fill_bg_alpha(im: Image.Image, tolerance: int = 28, feather: int = 2,
                         bg_sample: str = "corners") -> Image.Image:
    """Return an RGBA image with the background (connected to the border,
    similar in color to the sampled background color) made transparent.

    - bg_sample: "corners" averages the 4 corner pixels as the bg color.
    - tolerance: max per-channel-ish color distance to treat as background.
    - feather: gaussian blur radius (px) applied to the alpha edge for a
      soft, non-jagged cutout.
    """
    rgb = np.asarray(im.convert("RGB"), dtype=np.int16)
    h, w = rgb.shape[:2]

    if bg_sample == "corners":
        pts = [rgb[0, 0], rgb[0, w - 1], rgb[h - 1, 0], rgb[h - 1, w - 1]]
        bg_color = np.mean(pts, axis=0)
    else:
        bg_color = np.array([255, 255, 255])

    dist = np.sqrt(((rgb - bg_color) ** 2).sum(axis=2))
    is_bg_like = dist < tolerance

    # Flood-fill (connected-component) from the border only, so interior
    # light pixels (teeth, eye whites, sneaker soles) are never touched.
    labeled, _ = ndimage.label(is_bg_like)
    border_labels = set(labeled[0, :].tolist()) | set(labeled[-1, :].tolist()) \
        | set(labeled[:, 0].tolist()) | set(labeled[:, -1].tolist())
    border_labels.discard(0)
    bg_mask = np.isin(labeled, list(border_labels))

    alpha = np.where(bg_mask, 0, 255).astype(np.uint8)
    alpha_img = Image.fromarray(alpha, mode="L")
    if feather > 0:
        alpha_img = alpha_img.filter(ImageFilter.GaussianBlur(feather))

    out = im.convert("RGBA")
    out.putalpha(alpha_img)
    return out


def despeckle_alpha(im_rgba: Image.Image, min_area: int = 24) -> Image.Image:
    """Remove small isolated opaque specks (area < min_area px) that survive
    flood-fill (stray anti-aliasing flecks outside the main subject).

    Keeps every connected opaque component whose area is >= min_area, so
    intentionally-disconnected parts of a subject (a detached leaf, a
    separated tail tip, a prop held away from the body) survive -- only
    truly tiny specks are dropped. This is deliberately not "keep only the
    largest component": that would silently delete such parts with no
    warning, which is unsafe for a reusable script."""
    a = np.asarray(im_rgba.split()[-1])
    mask = a > 10
    labeled, n = ndimage.label(mask)
    if n == 0:
        return im_rgba
    sizes = ndimage.sum(mask, labeled, range(1, n + 1))
    keep_labels = [i + 1 for i, s in enumerate(sizes) if s >= min_area]
    keep = np.isin(labeled, keep_labels)
    a2 = np.where(keep, a, 0).astype(np.uint8)
    out = im_rgba.copy()
    out.putalpha(Image.fromarray(a2, mode="L"))
    return out


if __name__ == "__main__":
    import sys
    src, dst = sys.argv[1], sys.argv[2]
    tol = int(sys.argv[3]) if len(sys.argv) > 3 else 28
    im = Image.open(src)
    out = flood_fill_bg_alpha(im, tolerance=tol)
    out = despeckle_alpha(out)
    out.save(dst)
    print(f"wrote {dst}")
