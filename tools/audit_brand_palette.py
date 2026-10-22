#!/usr/bin/env python3
"""ViNT brand audit. Fails (exit 1) when the Android UI source uses a colour outside the palette, a gradient, a Material
named colour, or a stock widget without explicit colours. Also exposes contrast maths used by tests/test_brand.py.
usage: python tools/audit_brand_palette.py [android-app]"""
import re, sys
from pathlib import Path

PALETTE = {"0F1115": "background", "2FA36B": "green", "20242C": "surface", "373D4A": "border", "FFFFFF": "white"}
FORBIDDEN_NAMED = re.compile(r"\bColor\.(Red|Blue|Green|Yellow|Cyan|Magenta|Gray|DarkGray|LightGray|Black)\b")
GRADIENT_KT = re.compile(r"\bBrush\.(linear|vertical|horizontal|radial|sweep)Gradient\b|\bShader\b")
WIDGETS = ["Switch", "Slider", "RadioButton", "Checkbox", "LinearProgressIndicator", "CircularProgressIndicator", "OutlinedTextField",
           "TextField", "AlertDialog", "Button", "OutlinedButton", "TextButton", "FilledIconButton", "IconButton", "AssistChip", "Scaffold",
           "TopAppBar", "DropdownMenu", "SmallFloatingActionButton"]
COLOR_ARGS = ("colors", "Colors", "containerColor", "contentColor")


def strip_comments(t):
    t = re.sub(r"/\*.*?\*/", "", t, flags=re.S); t = re.sub(r"<!--.*?-->", "", t, flags=re.S)
    return re.sub(r"(?m)(^|\s)//.*$", "", t)


def hex_colors_kt(t):
    return [m.group(1).upper()[-6:] for m in re.finditer(r"Color\(\s*0x([0-9A-Fa-f]{8}|[0-9A-Fa-f]{6})\s*\)", t)]


def hex_colors_xml(t):
    return [m.group(1).upper()[-6:] for m in re.finditer(r"#([0-9A-Fa-f]{8}|[0-9A-Fa-f]{6})\b", t)]


def audit(root: Path):
    problems = []
    for f in sorted(root.rglob("*")):
        if f.suffix not in (".kt", ".xml") or "build" in f.parts or "third_party" in f.parts: continue
        t = strip_comments(f.read_text())
        cols = hex_colors_kt(t) if f.suffix == ".kt" else hex_colors_xml(t)
        problems += [f"{f}: colour #{c} is not in the ViNT palette" for c in cols if c not in PALETTE]
        if f.suffix == ".xml" and "<gradient" in t: problems.append(f"{f}: gradient is not allowed")
        if f.suffix == ".kt":
            if FORBIDDEN_NAMED.search(t): problems.append(f"{f}: Material named colour used: {FORBIDDEN_NAMED.search(t).group(0)}")
            if GRADIENT_KT.search(t): problems.append(f"{f}: gradient/shader is not allowed")
            if f.name != "Theme.kt":
                lines = t.splitlines()
                for i, ln in enumerate(lines):
                    for w in WIDGETS:
                        if re.search(rf"(?<![\w.]){w}\(", ln) and not re.search(rf"fun\s+{w}\b", ln):
                            window = "\n".join(lines[i:i + 8])
                            if not any(a in window for a in COLOR_ARGS):
                                problems.append(f"{f}:{i + 1}: {w}( without explicit colours")
    return problems


# ---- contrast maths (WCAG 2.x)
def _lin(c): c /= 255; return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
def lum(h): r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4)); return 0.2126 * _lin(r) + 0.7152 * _lin(g) + 0.0722 * _lin(b)
def blend(fg, bg, a): return "".join(f"{round(int(fg[i:i + 2], 16) * a + int(bg[i:i + 2], 16) * (1 - a)):02X}" for i in (0, 2, 4))
def contrast(a, b): la, lb = sorted((lum(a), lum(b)), reverse=True); return (la + 0.05) / (lb + 0.05)


if __name__ == "__main__":
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "android-app")
    probs = audit(root)
    if probs:
        print("BRAND AUDIT FAILED"); [print(" -", p) for p in probs]; sys.exit(1)
    print(f"BRAND AUDIT PASSED for {root}: only palette colours, no gradients, no Material named colours, widgets have explicit colours.")
