import re, shutil, subprocess, sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import audit_brand_palette as A

PAL = {"background": "0F1115", "green": "2FA36B", "surface": "20242C", "border": "373D4A"}
THEME = (ROOT / "android-app/app/src/main/java/ng/pidgingpt/ui/Theme.kt").read_text()
COLORS = (ROOT / "android-app/app/src/main/res/values/colors.xml").read_text()
BRAND = (ROOT / "docs/BRAND.md").read_text()


def test_real_project_passes_brand_audit():
    assert A.audit(ROOT / "android-app") == []


def test_palette_is_identical_everywhere():
    for name, hexv in PAL.items():
        assert f"val {name.capitalize()} = Color(0xFF{hexv})" in THEME, name
        assert f'name="vint_{name}">#{hexv}<' in COLORS, name
        assert f"`#{hexv}`" in BRAND, name
    gen = (ROOT / "tools/make_brand_assets.py").read_text()
    assert f'BG, SURFACE, GREEN, BORDER = "#{PAL["background"]}", "#{PAL["surface"]}", "#{PAL["green"]}", "#{PAL["border"]}"' in gen


def test_audit_catches_violations(tmp_path):
    d = tmp_path / "a"; shutil.copytree(ROOT / "android-app", d, ignore=shutil.ignore_patterns("third_party"))
    s = d / "app/src/main/java/ng/pidgingpt/ui/Screens.kt"; base = s.read_text()
    cases = {
        "hex": "val x = Color(0xFFFF0000)\n",
        "named": "val y = Color.Red\n",
        "gradient": "val z = Brush.verticalGradient(listOf())\n",
        "default widget": "fun q() { Button(onClick = {}) { } }\n",
    }
    for label, snippet in cases.items():
        s.write_text(base + "\n" + snippet)
        assert A.audit(d), f"audit missed: {label}"
    s.write_text(base)
    x = d / "app/src/main/res/values/colors.xml"; x.write_text(x.read_text().replace("</resources>", '<color name="bad">#FF5722</color></resources>'))
    assert any("FF5722" in p for p in A.audit(d))


def test_contrast_claims_in_brand_doc_are_true():
    bg, su, gr, wh = PAL["background"], PAL["surface"], PAL["green"], "FFFFFF"
    assert A.contrast(wh, bg) >= 7 and A.contrast(wh, su) >= 7
    assert A.contrast(A.blend(wh, bg, .66), bg) >= 4.5 and A.contrast(A.blend(wh, su, .66), su) >= 4.5      # secondary text
    assert A.contrast(bg, gr) >= 4.5                                                                         # text on green
    assert A.contrast(wh, gr) < 4.5                                                                          # why white-on-green is not used
    assert A.contrast(PAL["border"], bg) < 3                                                                 # why border is not used for text
    assert A.contrast(gr, bg) >= 4.5 and A.contrast(gr, su) >= 4.5


def test_icon_assets_regenerate_and_fit_the_launcher_safe_zone():
    """Milestone 5 replaced the toggle-pill icon with the owner's bubble-V + robot-head logo. The old reference stays in docs/ as history
    (see test_original_reference_asset_untouched); the generator now guarantees the mark fits inside the always-visible 72 dp circle of the adaptive icon."""
    r = subprocess.run([sys.executable, str(ROOT / "tools/make_brand_assets.py")], capture_output=True, text=True)
    assert r.returncode == 0, r.stdout + r.stderr
    assert "farthest solid pixel" in r.stdout
    res = ROOT / "android-app/app/src/main/res"
    for f in ("drawable-nodpi/ic_launcher_fg.png", "drawable-nodpi/ic_splash.png", "drawable/ic_launcher_monochrome.xml",
              "mipmap-anydpi-v26/ic_launcher.xml", "mipmap-anydpi-v26/ic_launcher_round.xml", "values/themes.xml", "values/strings.xml",
              "mipmap-mdpi/ic_launcher.png", "mipmap-xxxhdpi/ic_launcher.png", "mipmap-xxxhdpi/ic_launcher_round.png"):
        assert (res / f).exists(), f
    assert not (res / "drawable/ic_launcher_foreground.xml").exists() and not (res / "drawable/ic_splash.xml").exists()
    assert "@drawable/ic_launcher_fg" in (res / "mipmap-anydpi-v26/ic_launcher.xml").read_text()
    assert 'name="app_name">ViNT<' in (res / "values/strings.xml").read_text()
    manifest = (ROOT / "android-app/app/src/main/AndroidManifest.xml").read_text()
    assert '@string/app_name' in manifest and '@mipmap/ic_launcher' in manifest and "PidginGPT" not in manifest
    assert (ROOT / "docs/brand-assets/vint-logo-reference.png").exists()


def test_original_reference_asset_untouched():
    import hashlib
    h = hashlib.sha256((ROOT / "docs/brand-assets/reference-play-icon-512.png").read_bytes()).hexdigest()
    assert h == "9c0f3a86d87412483a8b2ae1521f66c752e7a0b8ca6f3a88782f461286413bca"


def test_no_user_facing_pidgingpt_in_app_sources():
    for f in (ROOT / "android-app/app/src").rglob("*"):
        if f.suffix in (".kt", ".xml") and "PidginGPT" in f.read_text():
            raise AssertionError(f"old product name in {f}")
