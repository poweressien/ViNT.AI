#!/usr/bin/env bash
# Finds the ViNT project root (the directory that contains android-app/settings.gradle.kts) so the workflow works
# whether the repository root IS the project or contains it in a subfolder (e.g. pidgingpt/).
# With --toolchain it also reads the pinned toolchain versions from the Gradle files (single source of truth).
set -euo pipefail
hit="$(find . -maxdepth 3 -path ./.git -prune -o -path '*/android-app/settings.gradle.kts' -print | head -n1)"
[ -n "$hit" ] || { echo "::error::android-app/settings.gradle.kts not found within 3 levels of the repository root"; exit 1; }
root="$(cd "$(dirname "$hit")/.." && pwd)"
ws="$(pwd)"; rel="${root#"$ws"}"; rel="${rel#/}"; prefix="${rel:+$rel/}"
echo "PROJECT_ROOT=$root"  >> "$GITHUB_ENV"
echo "PROJECT_PREFIX=$prefix" >> "$GITHUB_ENV"
echo "Project root: $root"
if [ "${1:-}" = "--toolchain" ]; then
  g="$root/android-app/app/build.gradle.kts"
  ndk="$(grep -oP 'ndkVersion\s*=\s*"\K[^"]+' "$g" || true)"
  cmk="$(grep -oP 'path = file\([^)]*\);\s*version = "\K[^"]+' "$g" || true)"
  sdk="$(grep -oP 'compileSdk\s*=\s*\K[0-9]+' "$g" || true)"
  app="$(grep -oP 'applicationId\s*=\s*"\K[^"]+' "$g" || true)"
  for v in ndk cmk sdk app; do [ -n "${!v}" ] || { echo "::error::could not read '$v' from $g"; exit 1; }; done
  { echo "NDK_VERSION=$ndk"; echo "CMAKE_VERSION=$cmk"; echo "COMPILE_SDK=$sdk"; echo "APP_ID=$app"; } >> "$GITHUB_ENV"
  echo "NDK=$ndk CMake=$cmk compileSdk=$sdk applicationId=$app"
  w="$root/android-app"
  for f in gradlew gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties; do
    [ -f "$w/$f" ] || { echo "::error::Gradle wrapper file missing: android-app/$f"; exit 1; }
  done
  if [ ! -x "$w/gradlew" ]; then
    echo "::warning::android-app/gradlew lacks the executable bit in git (common when committing from Windows); fixing it for this run. Permanent fix: git update-index --chmod=+x android-app/gradlew"
    chmod +x "$w/gradlew"
  fi
fi
