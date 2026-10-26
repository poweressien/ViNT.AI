#!/usr/bin/env python3
"""ViNT mark generator (milestone 5: the bubble-V + robot-head logo supplied by the owner, docs/brand-assets/vint-logo-reference.png).
Proportions and the neon-green / near-black / off-white logo colours were MEASURED from that image. The in-app UI keeps the strict 4-colour palette
(Theme.kt); only the launcher/splash PNGs carry the logo's own neon art colours. Generates: launcher PNGs (legacy + round + adaptive foreground),
splash PNG, a monochrome (themed icon) vector, SVG-free preview renders. Run from the repo root:  python tools/make_brand_assets.py"""
import math, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

BG, SURFACE, GREEN, BORDER = "#0F1115", "#20242C", "#2FA36B", "#373D4A"   # keep in sync with Theme.kt / docs/BRAND.md (UI palette)
ART_BG, ART_GREEN, ART_WHITE, ART_INK = "#05090C", "#2EE59D", "#F6F7F9", "#0A0A0A"   # logo art colours sampled from the supplied image
ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android-app/app/src/main/res"
BRAND = ROOT / "docs/brand-assets"
SS = 4

# Geometry in the reference's own pixel units (the 375 px app-icon tile). Origin top-left of the tile.
BUBBLE = (67, 102, 245, 280, 38)            # x0, y0, x1, y1, corner radius
TAIL = [(84, 262), (63, 296), (122, 276)]
V = [(101, 150), (156, 248), (211, 150)]; V_W = 26
HEAD = (268, 108, 46, 8)                    # cx, cy, outer radius, ring stroke
EAR_H, EAR_W, EAR_OFF = 40, 13, 52          # ear piece size and centre offset from head centre
EYES = [(252, 100), (284, 100)]; EYE_R, PUPIL_R, PUPIL_DY = 11, 5.5, 3
NOSE = [(263, 116), (273, 116), (268, 125)]
SMILE = (268, 124, 19, 14, 20, 160, 5)       # cx, cy, rx, ry, start, end, width


def _rline(d, pts, w, fill):
    d.line(pts, fill=fill, width=int(round(w)), joint="curve")
    for x, y in (pts[0], pts[-1]): d.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill=fill)


def draw_art(size, s, center, glow=True):
    """RGBA of the mark: art unit (x,y) -> pixel ((x-cx)*s + size/2). Returns (image, solid_alpha_image)."""
    k = SS; N = size * k
    def T(x, y): return ((x - center[0]) * s + size / 2) * k, ((y - center[1]) * s + size / 2) * k
    def L(v): return v * s * k
    solid = Image.new("RGBA", (N, N), (0, 0, 0, 0)); d = ImageDraw.Draw(solid)
    x0, y0, x1, y1, r = BUBBLE; (a, b), (c, e) = T(x0, y0), T(x1, y1)
    d.rounded_rectangle((a, b, c, e), radius=L(r), fill=ART_WHITE); d.polygon([T(*p) for p in TAIL], fill=ART_WHITE)
    _rline(d, [T(*p) for p in V], L(V_W), ART_INK)
    hx, hy, hr, hw = HEAD; ringlayer = Image.new("RGBA", (N, N), (0, 0, 0, 0)); rd = ImageDraw.Draw(ringlayer)
    for sx in (-1, 1):                                              # ears first, head over them
        cx, cy = T(hx + sx * EAR_OFF, hy); w, h = L(EAR_W), L(EAR_H)
        for dd, fill, inset in ((d, ART_BG, 0), (rd, ART_GREEN, 0)):
            pass
        rd.rounded_rectangle((cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), radius=w / 2, fill=ART_GREEN)
        d.rounded_rectangle((cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), radius=w / 2, fill=ART_GREEN)
        iw = L(4.5); d.rounded_rectangle((cx - w / 2 + iw, cy - h / 2 + iw, cx + w / 2 - iw, cy + h / 2 - iw), radius=max(1, w / 2 - iw), fill=ART_BG)
    cx, cy = T(hx, hy); R = L(hr); Wd = L(hw)
    d.ellipse((cx - R, cy - R, cx + R, cy + R), fill=ART_GREEN); rd.ellipse((cx - R, cy - R, cx + R, cy + R), fill=ART_GREEN)
    d.ellipse((cx - R + Wd, cy - R + Wd, cx + R - Wd, cy + R - Wd), fill=ART_BG)
    for ex, ey in EYES:
        px, py = T(ex, ey); er = L(EYE_R); d.ellipse((px - er, py - er, px + er, py + er), fill=ART_WHITE)
        pp = L(PUPIL_R); qy = py + L(PUPIL_DY); d.ellipse((px - pp, qy - pp, px + pp, qy + pp), fill=ART_GREEN)
    d.polygon([T(*p) for p in NOSE], fill=ART_GREEN)
    sx_, sy_, rx, ry, st, en, sw = SMILE; mx, my = T(sx_, sy_)
    d.arc((mx - L(rx), my - L(ry), mx + L(rx), my + L(ry)), st, en, fill=ART_WHITE, width=int(round(L(sw))))
    for ang in (st, en):
        ex_ = mx + L(rx) * math.cos(math.radians(ang)); ey_ = my + L(ry) * math.sin(math.radians(ang)); rr = L(sw) / 2
        d.ellipse((ex_ - rr, ey_ - rr, ex_ + rr, ey_ + rr), fill=ART_WHITE)
    out = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    if glow:
        g = ringlayer.filter(ImageFilter.GaussianBlur(L(7)))
        g.putalpha(g.getchannel("A").point(lambda v: min(255, int(v * 1.1))))
        out = Image.alpha_composite(out, g)
    out = Image.alpha_composite(out, solid)
    return out.resize((size, size), Image.LANCZOS), solid.getchannel("A").resize((size, size), Image.LANCZOS)


def art_center():
    big, a = draw_art(1000, 1.0, (200, 180), glow=False); bb = a.point(lambda v: 255 if v > 40 else 0).getbbox()
    return (bb[0] + bb[2]) / 2 - 500 + 200, (bb[1] + bb[3]) / 2 - 500 + 180, bb


def farthest(alpha, size):
    px = alpha.load(); far = 0.0
    for y in range(size):
        for x in range(size):
            if px[x, y] > 40: far = max(far, math.hypot(x + .5 - size / 2, y + .5 - size / 2))
    return far


def legacy_tile(n, center, round_=False):
    k = SS; big = Image.new("RGBA", (n * k, n * k), (0, 0, 0, 0)); d = ImageDraw.Draw(big)
    rad = n * k * 0.24; bw = max(2, n * k * 0.012)
    d.rounded_rectangle((0, 0, n * k - 1, n * k - 1), radius=rad, fill=ART_GREEN)
    d.rounded_rectangle((bw, bw, n * k - 1 - bw, n * k - 1 - bw), radius=rad - bw, fill=ART_BG)
    tile = big.resize((n, n), Image.LANCZOS)
    art, _ = draw_art(n, 0.70 * n / 300.0, center); tile = Image.alpha_composite(tile, art)
    if round_:
        m = Image.new("L", (n * k, n * k), 0); ImageDraw.Draw(m).ellipse((0, 0, n * k - 1, n * k - 1), fill=255)
        tile.putalpha(Image.composite(tile.getchannel("A"), Image.new("L", (n, n), 0), m.resize((n, n), Image.LANCZOS)))
    return tile


def mono_vector():
    """Themed-icon layer: single colour, strokes only (the system tints it). 108 viewport, art mapped like the adaptive foreground."""
    cx, cy, _ = art_center(); sc = FG_SCALE * 108 / 432.0
    def X(x): return (x - cx) * sc + 54
    def Y(y): return (y - cy) * sc + 54
    p = []
    def rr(x0, y0, x1, y1, r):
        a, b, c, d_ = X(x0), Y(y0), X(x1), Y(y1); rr_ = r * sc
        return f"M{a + rr_:.2f},{b:.2f} H{c - rr_:.2f} A{rr_:.2f},{rr_:.2f} 0 0 1 {c:.2f},{b + rr_:.2f} V{d_ - rr_:.2f} A{rr_:.2f},{rr_:.2f} 0 0 1 {c - rr_:.2f},{d_:.2f} H{a + rr_:.2f} A{rr_:.2f},{rr_:.2f} 0 0 1 {a:.2f},{d_ - rr_:.2f} V{b + rr_:.2f} A{rr_:.2f},{rr_:.2f} 0 0 1 {a + rr_:.2f},{b:.2f} Z"
    x0, y0, x1, y1, r = BUBBLE
    tail = "M" + " L".join(f"{X(a):.2f},{Y(b):.2f}" for a, b in TAIL) + " Z"
    vv = "M" + " L".join(f"{X(a):.2f},{Y(b):.2f}" for a, b in V)
    hx, hy, hr, hw = HEAD; hcx, hcy, hrr = X(hx), Y(hy), (hr - hw / 2) * sc
    ring = f"M{hcx - hrr:.2f},{hcy:.2f} A{hrr:.2f},{hrr:.2f} 0 1 0 {hcx + hrr:.2f},{hcy:.2f} A{hrr:.2f},{hrr:.2f} 0 1 0 {hcx - hrr:.2f},{hcy:.2f} Z"
    eyes = " ".join(f"M{X(ex) - EYE_R * sc:.2f},{Y(ey):.2f} a{EYE_R * sc:.2f},{EYE_R * sc:.2f} 0 1 0 {2 * EYE_R * sc:.2f},0 a{EYE_R * sc:.2f},{EYE_R * sc:.2f} 0 1 0 {-2 * EYE_R * sc:.2f},0 Z" for ex, ey in EYES)
    smile = f"M{X(SMILE[0] - SMILE[2] * 0.94):.2f},{Y(SMILE[1] + SMILE[3] * 0.34):.2f} Q{X(SMILE[0]):.2f},{Y(SMILE[1] + SMILE[3] * 1.6):.2f} {X(SMILE[0] + SMILE[2] * 0.94):.2f},{Y(SMILE[1] + SMILE[3] * 0.34):.2f}"
    ears = " ".join(rr(hx + s_ * EAR_OFF - EAR_W / 2, hy - EAR_H / 2, hx + s_ * EAR_OFF + EAR_W / 2, hy + EAR_H / 2, EAR_W / 2) for s_ in (-1, 1))
    def path(d, extra): return f'    <path android:pathData="{d}" android:strokeColor="#FFFFFF" android:strokeWidth="{extra}" android:strokeLineCap="round" android:strokeLineJoin="round"/>'
    out = ['<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
           path(rr(x0, y0, x1, y1, BUBBLE[4]) + " " + tail, f"{4.2 * sc:.2f}"), path(vv, f"{V_W * sc:.2f}"), path(ring + " " + ears, f"{5 * sc:.2f}"),
           f'    <path android:pathData="{eyes}" android:fillColor="#FFFFFF"/>', path(smile, f"{4 * sc:.2f}"), "</vector>", ""]
    return "\n".join(out)


FG_SCALE = 0.94


def main():
    for dname in ("drawable", "drawable-nodpi", "mipmap-anydpi-v26", "values"): (RES / dname).mkdir(parents=True, exist_ok=True)
    BRAND.mkdir(parents=True, exist_ok=True)
    cx, cy, bb = art_center(); center = (cx, cy)
    # adaptive foreground 432 px (108 dp @ xxxhdpi). Every solid pixel must sit inside the 72x72 dp circle that every launcher mask keeps (radius 144 px).
    global FG_SCALE
    s = 1.0
    while True:
        img, alpha = draw_art(432, s, center); far = farthest(alpha, 432)
        if far <= 144 or s < 0.3: break
        s -= 0.02
    FG_SCALE = s
    img.save(RES / "drawable-nodpi/ic_launcher_fg.png", optimize=True)
    splash, _ = draw_art(576, s * 1.15, center); splash.save(RES / "drawable-nodpi/ic_splash.png", optimize=True)    # splash icon circle = 2/3 of 288 dp
    (RES / "drawable/ic_launcher_monochrome.xml").write_text(mono_vector())
    for old in ("drawable/ic_launcher_foreground.xml", "drawable/ic_splash.xml"):
        if (RES / old).exists(): (RES / old).unlink()
    for dpi, n in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        (RES / f"mipmap-{dpi}").mkdir(exist_ok=True)
        legacy_tile(n, center).save(RES / f"mipmap-{dpi}/ic_launcher.png", optimize=True)
        legacy_tile(n, center, round_=True).save(RES / f"mipmap-{dpi}/ic_launcher_round.png", optimize=True)
    for name in ("ic_launcher", "ic_launcher_round"):
        (RES / f"mipmap-anydpi-v26/{name}.xml").write_text('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@color/ic_launcher_background"/>\n    <foreground android:drawable="@drawable/ic_launcher_fg"/>\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n</adaptive-icon>\n')
    legacy_tile(512, center).save(BRAND / "vint-icon-512.png", optimize=True)
    big = Image.new("RGBA", (900, 480), ART_BG); big.alpha_composite(draw_art(480, 1.6, center)[0], (210, 0)); big.convert("RGB").save(BRAND / "vint-mark-preview.png")
    print(f"foreground scale {s:.2f}; farthest solid pixel {far:.0f} px of the 144 px (36 dp) always-visible radius; art bbox {bb}")
    return 0 if far <= 144 else 1


if __name__ == "__main__":
    sys.exit(main())
