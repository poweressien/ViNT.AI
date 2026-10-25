# Merging the required setup into your real workflow

**Layout:** the ZIP wraps everything in a top-level `pidgingpt/` folder, but your Actions log shows the repository root (`/home/runner/work/ViNT.AI/ViNT.AI`) holding `android-app/` directly. So all paths below are relative to the repo root (`WORKDIR: '.'`), not `pidgingpt/`. If you ever move the project into a subfolder, change `WORKDIR` and nothing else; the first step fails with a clear message when the layout is wrong.

I could not see your real workflow (it is not in the ZIP) and I have no access to GitHub from the build environment, so **nothing was merged, nothing was overwritten, and CI has not been run.**
Open your existing workflow (in `poweressien/ViNT`, under `.github/workflows/`) and make sure it does all of the following, in this order, **before** the Gradle step:

| Required before Gradle | Step to add if missing |
|---|---|
| Java 17 | `actions/setup-java@v4` (temurin 17) |
| Python | `actions/setup-python@v5`, then `pip install pytest pillow` |
| Local checks | `python -m pytest -q`, `python tools/audit_android_offline.py android-app`, `python tools/audit_brand_palette.py android-app` |
| Pinned llama.cpp | `bash tools/setup_llama_cpp.sh` (reads `docs/LLAMA_CPP_PIN.txt`; folder is git-ignored) |
| sherpa-onnx AAR | `python tools/fetch_voice_models.py --runtime` (SHA-256 verified; `app/libs/*.aar` is git-ignored) |
| Dependency check | the "Check required dependencies" step in `android-build.proposed.yml` |
| Android SDK | `android-actions/setup-android@v3`, accept licences, `platforms;android-34`, `build-tools;34.0.0`, `cmake;3.22.1`. Your first run failed at the SDK setup step: keep whatever fix you already made there. |
| Gradle | `gradle/actions/setup-gradle@v4` with `gradle-version: 8.9` (replaces the missing wrapper; no jar needs committing) |

Then the build commands, run from `android-app/`: `gradle :app:help`, then `gradle :app:lintDebug :app:testDebugUnitTest :app:assembleDebug`, then verify the APK exists, is non-empty and contains `lib/arm64-v8a/*.so`, upload it, and upload `app/build/reports` and `app/build/test-results` on failure.

Do not commit the AAR, llama.cpp or any `.gguf`: the repo `.gitignore` already excludes them and `tests/test_hybrid.py` fails if weights appear in the tree. If you paste your real workflow here I can merge it for you.
