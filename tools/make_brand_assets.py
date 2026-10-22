#!/usr/bin/env python3
"""Single source of truth for the ViNT mark. Geometry was MEASURED from docs/brand-assets/reference-play-icon-512.png
(512-unit grid). Generates: Android adaptive-icon vectors, splash vector, SVG, a PNG render, and a pixel diff
against the reference. Run from the repo root:  python tools/make_brand_assets.py"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw

BG, SURFACE, GREEN, BORDER = "#0F1115", "#20242C", "#2FA36B", "#373D4A"   # keep in sync with Theme.kt / docs/BRAND.md
ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android-app/app/src/main/res"
BRAND = ROOT / "docs/brand-assets"

# (name, kind, geometry, fill, stroke)  geometry: pill=(x0,y0,x1,y1) ellipse=(cx,cy,rx,ry)
SHAPES = [
    ("toggle_on", "pill", (103, 98, 410, 185), GREEN, None),
    ("knob", "ellipse", (361.8, 140.8, 35.2, 26.0), BG, None),
    # pill outlines: measured OUTER box inset by half the 3-unit stroke (SVG/Android strokes are centred on the path)
    ("pill_mid", "pill", (104.1, 214.1, 408.5, 298.5), SURFACE, BORDER),
    ("pill_short", "pill", (104.1, 329.1, 292.5, 413.5), SURFACE, BORDER),
]
STROKE_W = 3.0
CENTER = (256.3, 256.5)      # centre of the content bounding box (102.6..410, 98..415)
MAX_RADIUS = 203             # farthest shape point from CENTER, in units (computed below and asserted)


def pill_path(x0, y0, x1, y1):
    r = (y1 - y0) / 2
    return (f"M{x0 + r:g},{y0:g} H{x1 - r:g} A{r:g},{r:g} 0 0 1 {x1 - r:g},{y1:g} "
            f"H{x0 + r:g} A{r:g},{r:g} 0 0 1 {x0 + r:g},{y0:g} Z")


def ellipse_path(cx, cy, rx, ry):
    return (f"M{cx - rx:g},{cy:g} A{rx:g},{ry:g} 0 1 0 {cx + rx:g},{cy:g} "
            f"A{rx:g},{ry:g} 0 1 0 {cx - rx:g},{cy:g} Z")


def path_of(kind, g): return pill_path(*g) if kind == "pill" else ellipse_path(*g)


def check_safe_zone():
    import math
    far = 0
    for _, kind, g, _, _ in SHAPES:
        if kind == "pill":
            x0, y0, x1, y1 = g; r = (y1 - y0) / 2
            for cx in (x0 + r, x1 - r):
                far = max(far, math.hypot(cx - CENTER[0], (y0 + r) - CENTER[1]) + r)
        else:
            cx, cy, rx, ry = g; far = max(far, math.hypot(cx - CENTER[0], cy - CENTER[1]) + max(rx, ry))
    return far


def vector_xml(viewport, scale, fill_override=None, mono=False):
    tx, ty = viewport / 2 - CENTER[0] * scale, viewport / 2 - CENTER[1] * scale
    out = [f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{viewport}dp" android:height="{viewport}dp" '
           f'android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">',
           f'    <group android:scaleX="{scale}" android:scaleY="{scale}" android:translateX="{tx:.3f}" android:translateY="{ty:.3f}">']
    for name, kind, g, fill, stroke in SHAPES:
        if mono:   # themed-icon layer: single colour (system tints it); knob is knocked out via transparency
            if name == "knob": continue
            alpha = "1" if name == "toggle_on" else "0.55"
            d = path_of(kind, g) + (" " + ellipse_path(*SHAPES[1][2]) if name == "toggle_on" else "")
            out.append(f'        <path android:pathData="{d}" android:fillColor="#FFFFFF" android:fillAlpha="{alpha}" android:fillType="evenOdd"/>')
            continue
        s = f' android:strokeColor="{stroke}" android:strokeWidth="{STROKE_W}"' if stroke else ""
        out.append(f'        <path android:pathData="{path_of(kind, g)}" android:fillColor="{fill}"{s}/>')
    out += ["    </group>", "</vector>", ""]
    return "\n".join(out)


def svg():
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="512" height="512">',
             f'<rect width="512" height="512" rx="96" fill="{BG}"/>']
    for _, kind, g, fill, stroke in SHAPES:
        s = f' stroke="{stroke}" stroke-width="{STROKE_W}"' if stroke else ""
        parts.append(f'<path d="{path_of(kind, g)}" fill="{fill}"{s}/>')
    parts.append("</svg>"); return "\n".join(parts) + "\n"


def render_png(size=512, ss=4):
    k = ss; im = Image.new("RGB", (size * k, size * k), BG); d = ImageDraw.Draw(im)
    for _, kind, g, fill, stroke in SHAPES:
        if kind == "pill":
            x0, y0, x1, y1 = [v * k * size / 512 for v in g]
            h = STROKE_W / 2 * k * size / 512 if stroke else 0     # expand centred-stroke path to its outer box
            d.rounded_rectangle((x0 - h, y0 - h, x1 + h, y1 + h), radius=(y1 - y0) / 2 + h, fill=fill, outline=stroke, width=int(round(STROKE_W * k)) if stroke else 0)
        else:
            cx, cy, rx, ry = [v * k * size / 512 for v in g]; d.ellipse((cx - rx, cy - ry, cx + rx, cy + ry), fill=fill)
    return im.resize((size, size), Image.LANCZOS)


def main():
    for d in (RES / "drawable", RES / "mipmap-anydpi-v26", RES / "values"): d.mkdir(parents=True, exist_ok=True)
    far = check_safe_zone(); assert far <= MAX_RADIUS + 1, far
    scale = 0.16                                  # 108dp adaptive canvas: far*scale must be <= 33dp (safe-zone circle d=66dp)
    assert far * scale <= 33, (far, far * scale)
    (RES / "drawable/ic_launcher_foreground.xml").write_text(vector_xml(108, scale))
    (RES / "drawable/ic_launcher_monochrome.xml").write_text(vector_xml(108, scale, mono=True))
    (RES / "drawable/ic_splash.xml").write_text(vector_xml(288, 0.4))     # splash icon circle = 192dp -> far*0.4 = 80.8dp <= 96
    BRAND.mkdir(parents=True, exist_ok=True)
    (BRAND / "vint-mark.svg").write_text(svg())
    png = render_png(); png.save(BRAND / "vint-mark-render-512.png")
    ref = Image.open(BRAND / "reference-play-icon-512.png").convert("RGB")
    # compare only inside the inner 96..416 square so the reference's black corner pixels are excluded
    box = (96, 96, 416, 416); a, b = ref.crop(box), png.crop(box)
    diffs = [sum(abs(p - q) for p, q in zip(x, y)) / 3 for x, y in zip(a.getdata(), b.getdata())]
    mean = sum(diffs) / len(diffs); close = sum(d <= 8 for d in diffs) / len(diffs)
    print(f"safe-zone farthest point: {far:.1f} units = {far * scale:.1f} dp (limit 33 dp); splash {far * 0.4:.1f} dp (limit 96)")
    print(f"render vs reference (inner box): mean abs channel diff {mean:.2f}/255; {close * 100:.2f}% of pixels within 8/255")
    return 0 if close > 0.97 else 1


if __name__ == "__main__":
    sys.exit(main())
