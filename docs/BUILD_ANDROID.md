# Build ViNT for Android (Windows 10/11)

## Build status: **no APK exists.** The build could not be run in the sandbox (checked again 2026-10-21)
Exact failures from the sandbox (DNS is blocked for every Android/Gradle repository):
```
dl.google.com / maven.google.com / repo.maven.apache.org / plugins.gradle.org / services.gradle.org  ->  curl: Could not resolve host
```
Without Maven Google/Central, Gradle cannot fetch the Android Gradle Plugin, Compose, AndroidX or Kotlin plugin, so `assembleDebug` cannot run there.
What the sandbox **could** reach (GitHub release assets, PyPI) was used to compile and test everything that does not need Gradle: see `HOST_VERIFICATION.md`.
**Never compiled:** the Compose UI (`Theme.kt`, `Screens.kt`, `VoiceScreen.kt`), `ChatViewModel.kt`, `MainActivity.kt`, Gradle/AGP configuration, and the NDK build of llama.cpp.
Expect first-build fixes. Send me the exact Gradle output and I will fix them.

## 1. Install (once)
1. **Android Studio** (current stable). It bundles JDK 17 (JBR).
2. In Android Studio → *SDK Manager*: SDK Platform **34**; SDK Tools: **Android SDK Build-Tools**, **NDK (Side by side)**, **CMake 3.22.1**, **Platform-Tools**.
3. **Git for Windows**. Python 3.10+ (only for the data/eval tools).
4. A phone with Developer options → USB debugging on (ARM64 phones; the app builds `arm64-v8a` only).
5. Put the project in a **short path**, e.g. `C:\vint\pidgingpt` (long-path errors are common with the NDK on Windows).

## 2. Fetch native/third-party inputs (PowerShell)
Voice runtime (50 MB AAR from the sherpa-onnx GitHub release, SHA-256 verified; it is a local file dependency, not a Maven download):
```powershell
python tools\fetch_voice_models.py --runtime        # -> android-app\app\libs\sherpa-onnx-1.13.8.aar
```
llama.cpp at the pinned commit:
```powershell
cd C:\vint\pidgingpt
powershell -ExecutionPolicy Bypass -File tools\setup_llama_cpp.ps1     # pinned: see docs\LLAMA_CPP_PIN.txt
```
## 3. Gradle wrapper (no wrapper jar is committed; CI uses `gradle/actions/setup-gradle` instead, see `docs/ci/`)
```powershell
winget install Gradle.Gradle            # or install Gradle 8.9 manually
cd android-app
gradle wrapper --gradle-version 8.9
```
Create `android-app\local.properties` with your SDK path, e.g. `sdk.dir=C\:\\Users\\YOU\\AppData\\Local\\Android\\Sdk`
(Android Studio creates it automatically when you open the folder).

## 4. Build the debug APK
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```
Expected output path (if it builds): `android-app\app\build\outputs\apk\debug\app-debug.apk`

## 5. Install on a connected phone
```powershell
adb devices                                   # phone must be listed as "device"
adb install -r app\build\outputs\apk\debug\app-debug.apk
```
## 6. Provide models (M4: the app downloads a model ONLY if you tap Download and confirm; importing still works)
**Speech models** (make ZIPs on your PC, copy them to the phone, import in ViNT → mic icon → Voice → Speech models):
```powershell
python tools\fetch_voice_models.py --out dist\voice-models --only sherpa-onnx-moonshine-tiny-en-int8 vits-piper-en_GB-northern_english_male-medium-int8
adb push dist\voice-models\*.zip /sdcard/Download/
```
Each download is checked against the pinned SHA-256 in `tools\voice_models.json`.

**Language model:**
Get a `.gguf` (see `MODEL_SELECTION.md`), then `adb push mymodel.gguf /sdcard/Download/` (or copy by USB/cloud). In ViNT: ⋮ → **Model** → *Import .gguf model* → **Load**.
Until the model status says **Ready**, chat is disabled and the app says so.

## Likely first-build problems
- **NDK/CMake not found** → install the exact versions above; `externalNativeBuild.cmake.version` is `3.22.1`.
- **llama.cpp CMake options changed** → options are set in `app/src/main/cpp/CMakeLists.txt`; adjust to the pinned commit.
- **Compose API mismatch** → BOM `2024.09.00`, Kotlin/Compose plugin `2.0.20`, AGP `8.5.2`. `Theme.kt`/`Screens.kt` use experimental `RippleConfiguration`.
- **Voice: `NoSuchMethodError` / `UnsatisfiedLinkError` for sherpa** → keep the AAR at exactly `app/libs/sherpa-onnx-1.13.8.aar`; ProGuard keep rules are in `proguard-rules.pro`; TTS callbacks must stay explicit `Function1` objects (see `SherpaEngines.kt`).
- **Microphone permission** is the only permission; if voice mode will not start, check Settings → Apps → ViNT → Permissions.
- **Slow/OOM on device** → lower context in Settings; use a smaller or more heavily quantized model.

## Application ID
`ng.pidgingpt` (unchanged). `com.inwekobong.visualdial` from the brief is not in this repo: see `BRAND.md` → *Identifiers*.

## Host checks you can run without Android
```bash
python -m pytest tests -q                       # 43 passed + 1 env-gated (44 with LLAMA_SERVER and MECHANICS_GGUF)
python tools/audit_android_offline.py android-app
python tools/audit_brand_palette.py android-app
tools/host-verify/run_host_verify.sh            # JNI + engine against pinned llama.cpp (Linux/WSL)
tools/host-verify/run_voice_verify.sh           # real speech models + voice pipeline (Linux/WSL; env vars in the script header)
```
