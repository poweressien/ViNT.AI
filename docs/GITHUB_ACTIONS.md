# Building ViNT with GitHub Actions (primary build path)

Workflow: `.github/workflows/android-build.yml`. No Android Studio is needed. **Status: the workflow has never run on GitHub yet.** It passed `actionlint` and its Python/native-fetch steps were exercised offline (see "Verification status" below), but no APK has been produced by it. The first run may surface real compile errors (the Compose UI, `ChatViewModel`, `MainActivity` and the NDK build have never been compiled anywhere; see `BUILD_ANDROID.md`).

## Triggers
- push to `main` (automatic)
- pull request targeting `main`
- manual: **Actions → Android build → Run workflow**

If nothing appears under *Actions*: repository **Settings → Actions → General → Allow all actions and reusable workflows** (or allow the pinned actions listed below), and make sure Actions is not disabled. Nothing in this repo changes these settings.

## Download the APK
1. Open the repository → **Actions** → **Android build**.
2. **Run workflow** (branch `main`) → wait for the run to finish (first run: expect 20–45 min; llama.cpp compiles for arm64).
3. Open the finished run. The **Summary** page shows PASSED/FAILED per stage.
4. Under **Artifacts** at the bottom, download **ViNT-debug-apk** (a ZIP). Kept 30 days.
5. Extract it: `ViNT-debug.apk`, `ViNT-debug.apk.sha256`, `BUILD_INFO.txt`. Verify with `sha256sum -c ViNT-debug.apk.sha256` if you like.
6. Copy the APK to your phone (ARM64 only) and open it; allow "install from this source" if Android asks.

It is a **debug-signed** APK. It contains **no language model and no speech models**.

## Jobs
| Job | What it proves | What it does **not** prove |
|---|---|---|
| `python-checks` | `pytest tests` (43 pass + 1 env-gated skip), offline-permission audit, brand audit | anything about Android |
| `jni-host-verify` | `pidgin_jni.cpp` + `LlamaEngine` against the pinned llama.cpp on the **host x86 CPU**, using a tiny random "mechanics" GGUF (existing `tools/host-verify/run_host_verify.sh`) | the Android NDK build; real model output |
| `android-debug` | Gradle config, Kotlin/Compose compile, NDK+CMake build of llama.cpp and `libpidgin_jni.so` (arm64-v8a), unit tests (none exist yet: Gradle reports NO-SOURCE), APK checks | running on a phone; any AI inference |
| `summary` | Reports the three results separately; fails unless all pass | |

APK checks (`.github/scripts/verify_apk.sh`): APK exists and is a valid zip; `aapt2` package equals `ng.pidgingpt`; no `INTERNET` permission; `lib/arm64-v8a/libpidgin_jni.so` and sherpa-onnx JNI library are packaged; all 5 `LlamaEngine` JNI symbols are exported; no `.gguf`/`.safetensors` inside.

## Versions
| Item | Value | Where defined |
|---|---|---|
| JDK | Temurin 17 | workflow `JAVA_VERSION` (AGP 8.5.2 needs 17) |
| Gradle | 8.9 (wrapper, SHA-256 pinned) | `android-app/gradle/wrapper/gradle-wrapper.properties` |
| AGP / Kotlin / Compose plugin / BOM | 8.5.2 / 2.0.20 / 2.0.20 / 2024.09.00 | existing Gradle files, unchanged |
| compileSdk / minSdk / targetSdk | 34 / 26 / 34 | `app/build.gradle.kts` |
| Build-tools | 34.0.0 | workflow |
| NDK | 26.1.10909125 | `ndkVersion` in `app/build.gradle.kts` (added; it equals AGP 8.5.2's default). The workflow reads it from there |
| CMake | 3.22.1 | `externalNativeBuild` in `app/build.gradle.kts` |
| ABI | arm64-v8a only | `abiFilters` (unchanged) |
| llama.cpp | `52ffa027455662ea59f78d0e4e5a8f38ee63a5fe` | `docs/LLAMA_CPP_PIN.txt`; CI fails if the checkout differs |
| sherpa-onnx AAR | 1.13.8, SHA-256 `633c2432…bd96` | `tools/voice_models.json`; verified by `tools/fetch_voice_models.py --runtime` |
| Kotlin compiler (host-verify job only) | 2.0.20, SHA-256 `5f5d2a8a…5931` | workflow |
| Python | 3.12 | workflow |

**Gradle wrapper:** `gradle-wrapper.jar` is byte-identical to the one in the `gradle/gradle` repository at tag `v8.9.0`. The distribution checksum `d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab` was computed from the official `gradle-8.9-bin.zip` release asset (github.com/gradle/gradle-distributions); `services.gradle.org` itself was unreachable from the authoring sandbox, so cross-check it against https://gradle.org/release-checksums/ . `setup-gradle` also validates the wrapper jar on every run.

## Pinned actions (full commit SHAs)
`actions/checkout` v7.0.1 · `actions/setup-java` v6.1.0 · `actions/setup-python` v7.1.0 · `actions/cache` v6.1.0 · `actions/upload-artifact` v7.0.2 · `gradle/actions/setup-gradle` v6.4.0 (`cache-provider: basic`, the open-source cache) · `android-actions/setup-android` v4.0.4. Permissions: `contents: read` only. No secrets are used. Timeouts: 15 / 60 / 75 min per job, 60 min for the Gradle step.

## Network
Needed: GitHub (actions, llama.cpp, sherpa AAR, Kotlin compiler), Google Maven + `dl.google.com` (AGP, AndroidX, SDK/NDK), Maven Central, Gradle Plugin Portal, `services.gradle.org`, PyPI. The app itself has no network code.

## Diagnosing a failed run
Open the failed step's log first. On failure the artifact **ViNT-build-diagnostics** holds `gradle-build.log`, Gradle HTML reports, and the CMake logs (`app/.cxx`). Typical suspects: Compose API mismatch (`RippleConfiguration` is experimental), llama.cpp CMake options that changed at the pinned commit (set in `app/src/main/cpp/CMakeLists.txt`), NDK 26 vs the pinned llama.cpp (if the toolchain is rejected, bump `ndkVersion` in one place), ProGuard is **not** involved in debug builds.

## Language model and speech models (separate from the build)
CI never downloads or packages them. The APK starts without a model; chat and voice are disabled and the app says "No model is loaded yet" until a `.gguf` is imported and **Ready**. To add one later: obtain the GGUF (see `MODEL_SELECTION.md`: provisional Qwen3-1.7B, **unverified**, read its license first), record its SHA-256 from the Hugging Face file page, check it on your PC (`sha256sum`), copy it to the phone, then ViNT → ⋮ → Model → Import → Load. Speech models: `python tools/fetch_voice_models.py --out dist/voice-models` (SHA-256 verified), then import the ZIPs in the app.

## Verification status
**Verified (offline, authoring sandbox):** workflow passes `actionlint`; `pytest` 43 passed + 1 skipped; both audits pass; `tools/setup_llama_cpp.sh` fetches exactly the pinned commit on a clean Linux box; wrapper generated and jar compared with upstream.
**Not verified:** any GitHub-hosted run; Gradle/AGP resolution; Kotlin/Compose compile; NDK/CMake build; APK packaging checks (`verify_apk.sh` has never executed against a real APK); the `ndkVersion` choice against this llama.cpp commit; unit tests (none exist); everything on a phone.
