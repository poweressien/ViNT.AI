#!/usr/bin/env bash
# Voice-layer verification on a Linux x86_64 JVM with REAL sherpa-onnx models and the real llama.cpp JNI engine.
# Prereqs: everything in run_host_verify.sh, plus: python tools/fetch_voice_models.py --out $WORK/zips  (or --from-dir),
#          sherpa-onnx AAR (android-app/app/libs) and the linux-x64 native jar from the sherpa-onnx v1.13.8 release, android.jar (API 34).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; W="${WORK:-/tmp/vint-hv}"; K="${KOTLINC:-kotlinc}"
AAR_CLASSES="${AAR_CLASSES:?path to classes.jar extracted from sherpa-onnx-1.13.8.aar}"; ANDROID_JAR="${ANDROID_JAR:?path to android.jar}"
NATIVE_DIR="${SHERPA_NATIVE_DIR:?dir containing libsherpa-onnx-jni.so and libonnxruntime.so (linux-x64)}"; WAVS="${TEST_WAVS:?dir with 0.wav 1.wav 8k.wav trans.txt}"
J="$ROOT/android-app/app/src/main/java/ng/pidgingpt"
"$K" -nowarn -classpath "$ANDROID_JAR:$AAR_CLASSES" -d "$W/vclasses" "$J/data/LanguageMode.kt" "$J/inference/InferenceEngine.kt" "$J/inference/LlamaEngine.kt" "$J/inference/Prompts.kt" "$J/inference/ThinkFilter.kt" "$J"/voice/*.kt "$ROOT/tools/host-verify/VoiceHostVerify.kt"
KLIB="$(dirname "$(command -v "$K")")/../lib/kotlin-stdlib.jar"
LD_LIBRARY_PATH="$NATIVE_DIR:$W" java -Djava.library.path="$NATIVE_DIR:$W" -cp "$W/vclasses:$KLIB:$AAR_CLASSES:$ANDROID_JAR" VoiceHostVerifyKt \
  "$W/zips" "$W/mechanics-test.gguf" "$ROOT/android-app/app/src/main/assets/voices/voice_profiles.json" "$W/voice-tmp" "$WAVS"
