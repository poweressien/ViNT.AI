#!/usr/bin/env bash
# Verifies the debug APK that Gradle actually produced and stages it for upload in $RUNNER_TEMP/apk-out.
set -euo pipefail
cd "$PROJECT_ROOT/android-app"
mapfile -t apks < <(find app/build/outputs/apk/debug -name '*.apk' -type f 2>/dev/null)
[ "${#apks[@]}" -ge 1 ] || { echo "::error::no APK found under app/build/outputs/apk/debug"; exit 1; }
apk="${apks[0]}"; [ -s "$apk" ] || { echo "::error::$apk is empty"; exit 1; }
unzip -tq "$apk" >/dev/null || { echo "::error::$apk is not a valid zip/APK"; exit 1; }
echo "APK: $apk ($(stat -c%s "$apk") bytes)"

aapt2="$(ls -d "$ANDROID_HOME"/build-tools/34.0.0/aapt2)"
badging="$("$aapt2" dump badging "$apk")"
pkg="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging")"
[ "$pkg" = "$APP_ID" ] || { echo "::error::APK package is '$pkg', expected '$APP_ID'"; exit 1; }
echo "applicationId OK: $pkg"
if grep -q "uses-permission: name='android.permission.INTERNET'" <<<"$badging"; then echo "::error::APK requests INTERNET"; exit 1; fi

listing="$(unzip -Z1 "$apk")"
grep -qx 'lib/arm64-v8a/libpidgin_jni.so' <<<"$listing" || { echo "::error::libpidgin_jni.so missing from APK (native build did not run or was not packaged)"; exit 1; }
grep -q '^lib/arm64-v8a/libsherpa-onnx-jni.so$' <<<"$listing" || { echo "::error::sherpa-onnx JNI library missing from APK"; exit 1; }
if grep -Eqi '\.(gguf|safetensors)$' <<<"$listing"; then echo "::error::model weights found inside the APK"; exit 1; fi
echo "native libraries packaged OK (arm64-v8a)"

work="$(mktemp -d)"; unzip -q -j "$apk" 'lib/arm64-v8a/libpidgin_jni.so' -d "$work"
nm="$ANDROID_HOME/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm"
syms="$("$nm" -D --defined-only "$work/libpidgin_jni.so")"
missing=0
for fn in $(grep -oP 'external fun \K\w+' app/src/main/java/ng/pidgingpt/inference/LlamaEngine.kt); do
  grep -q "Java_ng_pidgingpt_inference_LlamaEngine_${fn}\$" <<<"$syms" && echo "JNI symbol OK: $fn" || { echo "::error::JNI symbol missing: $fn"; missing=1; }
done
[ "$missing" = 0 ]

out="$RUNNER_TEMP/apk-out"; mkdir -p "$out"; cp "$apk" "$out/ViNT-debug.apk"
( cd "$out" && sha256sum ViNT-debug.apk > ViNT-debug.apk.sha256
  { echo "ViNT debug APK"; echo "commit: $GITHUB_SHA"; echo "run: $GITHUB_RUN_ID"; echo "applicationId: $pkg"
    echo "llama.cpp: $(git -C "$PROJECT_ROOT/android-app/third_party/llama.cpp" rev-parse HEAD)"
    echo "NDK: $NDK_VERSION  CMake: $CMAKE_VERSION"
    echo "NOTE: no language model and no speech models are included; import them in the app (see docs/BUILD_ANDROID.md)."; } > BUILD_INFO.txt )
{ echo "### APK checks"; echo "- package \`$pkg\`, size $(stat -c%s "$apk") bytes"; echo "- native libs + all 5 JNI symbols present; no INTERNET permission; no model weights inside"; } >> "$GITHUB_STEP_SUMMARY"
