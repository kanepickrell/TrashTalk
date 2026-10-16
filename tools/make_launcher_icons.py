#!/usr/bin/env python3
"""Turn the TrashTalk brand mark into Android launcher assets.

The source art is a flat two-tone JPEG: a dark green line drawing of a waste bin
that doubles as a megaphone, on white. Three things have to happen before it can
be an adaptive icon:

  1. Cut the background. JPEG compression leaves the "white" ringing between
     about 235 and 255, so the alpha comes from a luminance ramp rather than an
     equality test, which also anti-aliases the edge for free.
  2. Trim and re-centre. Adaptive icons reserve the outer 1/3 of the canvas for
     masking and parallax, so the glyph is fitted inside the 66% safe zone --
     otherwise launchers that use a circular mask would clip the sound waves.
  3. Emit both tones. The dark green reads well on the light icon background but
     disappears on the app's near-black surfaces, so a light variant is written
     for in-app use.

Run after changing the source art:  python tools/make_launcher_icons.py
"""

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT.parent / "TrashTalk.jpg"
RES = ROOT / "app/src/main/res"

# Adaptive icon foregrounds are authored on a 108dp canvas; these are the pixel
# sizes per density bucket.
DENSITIES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}

# Fraction of the canvas the glyph may occupy. The adaptive-icon safe zone is
# 66%; staying a little inside it keeps the mark clear of every mask shape.
SAFE_ZONE = 0.58

# The brand green, sampled from the source art, and a light variant lifted for
# legibility on the app's near-black surfaces.
BRAND_DARK = (26, 92, 26)
BRAND_LIGHT = (122, 231, 142)

# Luminance window over which the background fades out.
ALPHA_FLOOR, ALPHA_CEIL = 110, 238


def load_glyph(path: Path) -> Image.Image:
    """Load the mark as RGBA with the white background removed and trimmed."""
    src = Image.open(path).convert("RGB")
    w, h = src.size
    px = src.load()

    glyph = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    gp = glyph.load()
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            lum = 0.299 * r + 0.587 * g + 0.114 * b
            if lum >= ALPHA_CEIL:
                continue
            # Ramp rather than threshold: keeps the stroke edges smooth.
            alpha = 255 if lum <= ALPHA_FLOOR else int(
                255 * (ALPHA_CEIL - lum) / (ALPHA_CEIL - ALPHA_FLOOR)
            )
            gp[x, y] = (r, g, b, alpha)

    bbox = glyph.getbbox()
    if bbox is None:
        raise SystemExit("no glyph found - is the source art blank?")
    return glyph.crop(bbox)


def recolor(glyph: Image.Image, rgb: tuple) -> Image.Image:
    """Replace the colour while keeping the anti-aliased alpha."""
    out = Image.new("RGBA", glyph.size, rgb + (0,))
    out.putalpha(glyph.getchannel("A"))
    return out


def render(glyph: Image.Image, canvas_px: int) -> Image.Image:
    """Fit the glyph inside the safe zone of a square transparent canvas."""
    target = int(canvas_px * SAFE_ZONE)
    gw, gh = glyph.size
    scale = min(target / gw, target / gh)
    size = (max(1, round(gw * scale)), max(1, round(gh * scale)))
    scaled = glyph.resize(size, Image.LANCZOS)

    canvas = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
    canvas.paste(
        scaled,
        ((canvas_px - size[0]) // 2, (canvas_px - size[1]) // 2),
        scaled,
    )
    return canvas


def main() -> None:
    if not SOURCE.exists():
        raise SystemExit(f"source art not found: {SOURCE}")

    glyph = load_glyph(SOURCE)
    print(f"trimmed glyph: {glyph.size[0]}x{glyph.size[1]}")

    dark = recolor(glyph, BRAND_DARK)
    light = recolor(glyph, BRAND_LIGHT)

    for bucket, px in DENSITIES.items():
        out_dir = RES / f"mipmap-{bucket}"
        out_dir.mkdir(parents=True, exist_ok=True)
        render(dark, px).save(out_dir / "ic_launcher_foreground.png")
        print(f"  mipmap-{bucket}/ic_launcher_foreground.png  {px}x{px}")

    # One light copy for in-app use (headers, the about panel).
    brand_dir = RES / "drawable-xxxhdpi"
    brand_dir.mkdir(parents=True, exist_ok=True)
    render(light, 432).save(brand_dir / "brand_mark.png")
    print("  drawable-xxxhdpi/brand_mark.png  432x432 (light variant)")


if __name__ == "__main__":
    main()
