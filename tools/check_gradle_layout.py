#!/usr/bin/env python3
"""Static check of the Gradle plugin/toolchain configuration, run in CI BEFORE Gradle so a mismatch is explained instead of guessed at.
Fails (exit 1) and prints the three build files with line numbers when:
  - settings.gradle.kts has no pluginManagement.repositories with google(), mavenCentral(), gradlePluginPortal(), or does not include(":app");
  - the Android/Kotlin/Compose plugins do not have EXACTLY ONE explicit version (in the root build.gradle.kts, with `apply false`);
  - the app module declares a plugin version of its own, or lacks one of the plugin ids;
  - gradle.properties lacks the vint.* toolchain keys that CI and build.gradle.kts both read.
Static only: it cannot prove the plugins resolve (that needs Gradle and network)."""
import re, sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else "android-app")
IDS = ["com.android.application", "org.jetbrains.kotlin.android", "org.jetbrains.kotlin.plugin.compose"]
KEYS = ["vint.compileSdk", "vint.buildTools", "vint.ndk", "vint.cmake"]
files = {"settings": root / "settings.gradle.kts", "root": root / "build.gradle.kts", "app": root / "app/build.gradle.kts", "props": root / "gradle.properties"}
problems = []
for k, p in files.items():
    if not p.exists(): problems.append(f"missing file: {p}")
if not problems:
    t = {k: p.read_text() for k, p in files.items()}
    pm = re.search(r"pluginManagement\s*\{(.*?)\n\}|pluginManagement\s*\{(.*?)\}\s*(?:\n|$)", t["settings"], re.S)
    block = (pm.group(0) if pm else "")
    if not pm: problems.append("settings.gradle.kts has no pluginManagement block")
    for r in ("google()", "mavenCentral()", "gradlePluginPortal()"):
        if r not in block: problems.append(f"pluginManagement.repositories is missing {r}")
    if 'include(":app")' not in t["settings"]: problems.append('settings.gradle.kts does not include(":app")')
    versions = {}
    for i in IDS:
        decl_root = re.findall(rf'id\("{re.escape(i)}"\)\s+version\s+"([^"]+)"', t["root"])
        decl_other = [n for n in ("settings", "app") if re.search(rf'id\("{re.escape(i)}"\)\s+version\s', t[n])]
        if len(decl_root) != 1: problems.append(f"{i}: expected exactly one version in build.gradle.kts, found {len(decl_root)}")
        else: versions[i] = decl_root[0]
        if decl_other: problems.append(f"{i}: also declares a version in {decl_other} (duplicate)")
        if not re.search(rf'id\("{re.escape(i)}"\)\s+version\s+"[^"]+"\s+apply\s+false', t["root"]): problems.append(f"{i}: root declaration must end with `apply false`")
        if not re.search(rf'id\("{re.escape(i)}"\)\s*$', t["app"], re.M): problems.append(f"{i}: app/build.gradle.kts must apply it WITHOUT a version")
    for k in KEYS:
        if not re.search(rf"^{re.escape(k)}=\S+", t["props"], re.M): problems.append(f"gradle.properties is missing {k}")
        if f'gradleProperty("{k}")' not in t["app"]: problems.append(f"app/build.gradle.kts does not read {k}")
    if 'applicationId = "ng.pidgingpt"' not in t["app"]: problems.append("applicationId is not ng.pidgingpt")
if problems:
    print("GRADLE LAYOUT CHECK FAILED")
    for p in problems: print(" -", p)
    for k in ("settings", "root", "app"):
        if files[k].exists():
            print(f"\n===== {files[k]} =====")
            for n, line in enumerate(files[k].read_text().splitlines(), 1): print(f"{n:3}| {line}")
    sys.exit(1)
print("GRADLE LAYOUT CHECK PASSED (static): " + ", ".join(f"{k.split('.')[-1]}={v}" for k, v in versions.items()) + "; plugin repositories google/mavenCentral/gradlePluginPortal; one version declaration per plugin.")
