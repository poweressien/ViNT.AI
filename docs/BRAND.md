# ViNT brand and theme

**Name:** ViNT  **Tagline:** Your Pidgin. Your AI. Offline.  **Category:** offline AI assistant for Nigerian Pidgin.
Reference asset: `docs/brand-assets/reference-play-icon-512.png` (untouched copy of the supplied `play-icon-512.png`, same SHA-256).

## Official palette (the only UI colours)
| Role | Final hex | Brief's starting value | How it was chosen |
|---|---|---|---|
| Background | `#0F1115` | `#0F1014` | most frequent pixel colour in the reference (183,009 px) |
| Primary green | `#2FA36B` | `#2FA36F` | pill fill in the reference |
| Surface | `#20242C` | `#20242D` | fill of the two charcoal pills |
| Border | `#373D4A` | `#383E49` | peak colour of the pill outlines |
| Text | `#FFFFFF` | | white; hierarchy by alpha only (secondary 66%, disabled 38%) |

The refined values differ from the brief by at most 4 per channel. Defined in `ui/Theme.kt` (`Palette`), `res/values/colors.xml`
and `tools/make_brand_assets.py`; `tests/test_brand.py` fails if they drift apart or from this table.
Alpha variants of these exact colours are allowed for text hierarchy, disabled states, ripples and scrims. Nothing else.

## Rules
- Screens use **Background**; cards, fields, menus, dialogs, nav surfaces use **Surface** with a 1 dp **Border** outline.
- **Green** marks primary buttons, selected/active states, progress and the status dot. It is not a background for ordinary components.
- Shapes: pills (fully rounded) for buttons, toggles, chips, search fields; 22 dp rounded cards and bubbles.
- No gradients, glows, or extra accent colours. **No red**: errors and warnings are shown with an outline-info icon, a white label and a white border.
- Text on green is **Background** colour (5.9:1); white on green is only 3.2:1, so it is not used.
- Every widget is given explicit colours (theme `colorScheme`, ripple, text selection, cursor, switch, slider, radio, progress, dialog, menu, system bars). `tools/audit_brand_palette.py` enforces this.

## Measured contrast (WCAG)
| Combination | Ratio | Use |
|---|---|---|
| white on Background | 18.9:1 | body text |
| white on Surface | 15.6:1 | card text |
| white 66% on Background / Surface | 8.5:1 / 7.6:1 | secondary text |
| Background on Green | 5.9:1 | button and user-bubble text |
| Green on Background / Surface | 5.9:1 / 4.9:1 | icons, active states, links |
| Border on Background | 1.7:1 | outlines only: **not legible as text**, so secondary text uses white alpha instead of `#373D4A` (the brief allowed this fallback) |
| white 38% on Surface | 3.5:1 | disabled controls (exempt from WCAG) |

## Icon and splash
Geometry was measured from the reference (512-unit grid) and lives in `tools/make_brand_assets.py`, which generates the adaptive icon
foreground, themed (monochrome) icon, splash vector and `docs/brand-assets/vint-mark.svg`. Result vs reference: mean error 0.60/255,
97.9% of pixels within 8/255 (remaining differences are anti-aliasing at curved edges). Safe zone: farthest shape point = 32.4 dp of the
33 dp adaptive-icon safe radius. The icon background is the Background colour, so the reference's black outer corners (pure `#000000`, outside
the palette) are replaced by the launcher mask. Launcher icon not yet viewed on a device or emulator.
Regenerate: `python tools/make_brand_assets.py`.

## Identifiers
Repo applicationId and namespace are `ng.pidgingpt`. The brief mentions `com.inwekobong.visualdial`, which appears **nowhere in this repo**, so it
was not changed or introduced. Display name is `@string/app_name` = ViNT. Changing the applicationId makes Android treat the app as a different app
(no upgrade path, separate data); the Kotlin `package` names need not change. If `com.inwekobong.visualdial` is the intended ID, change only
`applicationId` in `app/build.gradle.kts`.

## Voice screen (milestone 3)
Same palette and rules: Surface cards with Border outlines, pill talk button (Surface + Border when idle, **Green fill with Background-colour text** while listening), green level meter on a Border track,
green status dot only while voice mode is on, unavailable voices shown as plain outlined pills (no red). Checked by `tools/audit_brand_palette.py`; not yet viewed on a device.

## Milestone 5: new logo (supplied by the owner)
Reference: `docs/brand-assets/vint-logo-reference.png` (bubble with a V, robot head as the dot of the "i", "iNT" wordmark). Geometry and the three logo-art colours (near-black `#05090C`,
neon green `#2EE59D`, off-white `#F6F7F9`) were measured from it by `tools/make_brand_assets.py`, which draws the launcher icons (legacy, round, adaptive foreground), the splash PNG and the
monochrome themed-icon vector. **Those art colours appear only in the launcher/splash PNGs.** The in-app UI keeps the 4-colour palette above: `BotHead` and `BrandMark` in `Theme.kt` redraw
the same mark in palette colours (no neon glow, which would need a colour outside the palette). The old toggle-pill icon (`reference-play-icon-512.png`) is kept only as history.
Robot head moods: Idle (static), Thinking (bobbing + pulsing ring, shown while a reply loads or generates), Listening, Speaking (mouth animates). It is the voice-chat button in the chat header.
