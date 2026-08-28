#!/usr/bin/env python3
"""
Clean the habit icon PNGs:

1. Remove the baked-in semi-transparent whitish "checkerboard haze" background
   (a leftover of an imperfect background removal — invisible on the cream/light
   theme but rendered as a gray checkered tile on the dark theme).
2. Trim, re-center and scale-normalize the artwork so every icon has the same
   visual weight and sits aligned in the UI grid.
3. Downscale 1024px sources to 512px and emit them into drawable-nodpi/ so
   Android does not density-upscale the bitmaps in memory.

Algorithm per image:
- "whitish" = near-white pixel (min channel >= 185, chroma spread <= 42).
- Flood fill from the canvas border across whitish-or-transparent pixels:
  everything reached is background -> alpha 0. This safely kills haze that
  touches the edges regardless of its alpha (it goes up to ~236 in places).
- Any remaining whitish pixel with alpha < 210 (haze checker squares enclosed
  inside artwork loops) -> alpha 0. Genuine white artwork fills are >= 240
  alpha, so they are preserved.
- Trim to the artwork bounding box, scale so the longest side is CONTENT_RATIO
  of the canvas, and center it.
"""
import os
import sys
import numpy as np
from PIL import Image
from scipy import ndimage

SRC = "app/src/main/res/drawable"
DST = "app/src/main/res/drawable-nodpi"
OUT_SIZE = 512
CONTENT_RATIO = 0.84  # longest artwork side as fraction of canvas

WHITISH_MIN = 185
WHITISH_SPREAD = 42
ENCLOSED_HAZE_ALPHA = 210


def whitish_mask(rgba: np.ndarray) -> np.ndarray:
    r, g, b = (rgba[..., i].astype(np.int16) for i in range(3))
    mn = np.minimum(np.minimum(r, g), b)
    mx = np.maximum(np.maximum(r, g), b)
    return (mn >= WHITISH_MIN) & ((mx - mn) <= WHITISH_SPREAD)


def clean(rgba: np.ndarray) -> np.ndarray:
    a = rgba[..., 3]
    whitish = whitish_mask(rgba)

    # Region the flood fill may travel through: transparent OR whitish haze.
    passable = whitish | (a <= 10)

    # Flood fill from all four borders.
    labeled, _ = ndimage.label(passable)
    border_labels = np.unique(
        np.concatenate([labeled[0, :], labeled[-1, :], labeled[:, 0], labeled[:, -1]])
    )
    border_labels = border_labels[border_labels != 0]
    background = np.isin(labeled, border_labels)

    out = rgba.copy()
    # Border-connected haze -> fully transparent.
    out[..., 3][background & whitish] = 0
    # Enclosed haze checker squares (inside artwork loops) -> transparent.
    enclosed_haze = whitish & ~background & (a < ENCLOSED_HAZE_ALPHA)
    out[..., 3][enclosed_haze] = 0
    return out


def normalize(rgba: np.ndarray, out_size: int, ratio: float) -> Image.Image:
    a = rgba[..., 3]
    ys, xs = np.where(a > 10)
    if len(xs) == 0:
        raise ValueError("icon became empty after cleaning")
    y0, y1, x0, x1 = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1
    crop = Image.fromarray(rgba[y0:y1, x0:x1], "RGBA")

    target = int(out_size * ratio)
    scale = target / max(crop.width, crop.height)
    new_w = max(1, round(crop.width * scale))
    new_h = max(1, round(crop.height * scale))
    crop = crop.resize((new_w, new_h), Image.LANCZOS)

    canvas = Image.new("RGBA", (out_size, out_size), (0, 0, 0, 0))
    canvas.paste(crop, ((out_size - new_w) // 2, (out_size - new_h) // 2), crop)
    return canvas


def main() -> int:
    root = os.path.dirname(os.path.abspath(__file__))
    src = os.path.join(root, "..", SRC)
    dst = os.path.join(root, "..", DST)
    os.makedirs(dst, exist_ok=True)

    files = sorted(f for f in os.listdir(src) if f.endswith(".png"))
    for name in files:
        rgba = np.array(Image.open(os.path.join(src, name)).convert("RGBA"))
        cleaned = clean(rgba)
        icon = normalize(cleaned, OUT_SIZE, CONTENT_RATIO)
        icon.save(os.path.join(dst, name), optimize=True)
        os.remove(os.path.join(src, name))
        print(f"ok  {name}")
    print(f"\nprocessed {len(files)} icons -> {DST} @ {OUT_SIZE}px")
    return 0


if __name__ == "__main__":
    sys.exit(main())
