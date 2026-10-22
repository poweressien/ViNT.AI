#!/usr/bin/env bash
# Host-side (Linux x86_64) verification of the JNI bridge + Kotlin engine layer against the pinned llama.cpp.
# Prereqs: JDK 17+ with headers (jni.h), cmake, ninja, kotlinc, python3 + `pip install gguf numpy`, llama.cpp checked out in android-app/third_party/llama.cpp.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; W="${WORK:-/tmp/vint-hv}"; mkdir -p "$W"
JH="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"; L="$ROOT/android-app/third_party/llama.cpp"; K="${KOTLINC:-kotlinc}"
SRC="$ROOT/android-app/app/src/main"
if [ ! -f "$W/build/src/libllama.a" ]; then
  cmake -S "$L" -B "$W/build" -G Ninja -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DLLAMA_CURL=OFF \
    -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_TOOLS=OFF -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON
  cmake --build "$W/build" --target llama -j"$(nproc)"
fi
B="$W/build"
g++ -std=c++17 -O1 -shared -fPIC -Wall -Wextra -I"$JH/include" -I"$JH/include/linux" -I"$L/include" -I"$L/ggml/include" \
  "$SRC/cpp/pidgin_jni.cpp" -o "$W/libpidgin_jni.so" -Wl,--start-group "$B/src/libllama.a" "$B/ggml/src/libggml.a" \
  "$B/ggml/src/libggml-cpu.a" "$B/ggml/src/libggml-base.a" -Wl,--end-group -lpthread -ldl -lm
python3 "$ROOT/tools/host-verify/make_mechanics_model.py" "$W/mechanics-test.gguf"
J="$SRC/java/ng/pidgingpt"
"$K" -nowarn -d "$W/classes" "$J/data/LanguageMode.kt" "$J/data/GgufInfo.kt" "$J/inference/InferenceEngine.kt" "$J/inference/LlamaEngine.kt" \
  "$J/inference/Prompts.kt" "$ROOT/tools/host-verify/HostVerify.kt"
KLIB="$(dirname "$(command -v "$K")")/../lib/kotlin-stdlib.jar"
java -Djava.library.path="$W" -cp "$W/classes:$KLIB" HostVerifyKt "$W/mechanics-test.gguf"
