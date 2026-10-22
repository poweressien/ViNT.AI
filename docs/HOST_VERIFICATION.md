# Host-side verification (what was actually run in the sandbox)

Date: 2026-10-21 · Linux x86_64, 1 CPU, no GPU, no Android SDK/NDK · llama.cpp `52ffa02…` (see `docs/LLAMA_CPP_PIN.txt`).

| Step | Result |
|---|---|
| Build `libllama` from the pinned commit (CPU only) | succeeded (69/69 targets) |
| Compile the real `pidgin_jni.cpp` (g++ `-Wall -Wextra`) against the pinned headers | succeeded, 0 warnings. **First draft did NOT compile**: `llama_sampler_init_penalties` now takes `n_vocab` first; fixed. |
| Compile the real `LlamaEngine.kt`, `GgufInfo.kt`, `Prompts.kt` with kotlinc 2.0.20 | succeeded |
| Type-check `ConversationStore`, `SlangRepository`, `ModelManager`, `Settings` against `android.jar` (API 34) | succeeded |
| Run `tools/host-verify/HostVerify.kt` on a JVM through the real JNI library | **35 passed, 0 failed** (`docs/host-verify-result.txt`) |

What the 35 checks cover: GGUF header parsing and rejection of bad/truncated/empty files; clean failure when loading a missing or corrupt model;
loading a GGUF; chat-template application; streaming generation; emoji + Yoruba-diacritic prompts crossing JNI as UTF-8 bytes; cancellation from
inside the stream and from another thread; reuse after cancel; clear error for an oversized prompt; unload during generation; reload and 5x repeated
load/unload; prompt windowing.

## What this does NOT prove
- The model used is a **tiny randomly-initialised mechanics model** (`tools/host-verify/make_mechanics_model.py`). It outputs gibberish. It proves the
  plumbing works, **not** that any language model works, and says nothing about Pidgin quality.
- Nothing here ran on Android: no NDK compile (ARM64 flags, bionic), no Compose compile, no APK, no device. Compose/UI source (`Theme.kt`, `Screens.kt`,
  `ChatViewModel.kt`, `MainActivity.kt`) has never been compiled. Expect first-build fixes.
- The "tokens/s" printed by the harness (a few per second) is not a performance figure: debug logging was on, on one shared CPU core.

Reproduce: `tools/host-verify/run_host_verify.sh` (needs JDK with headers, cmake, ninja, kotlinc, `pip install gguf numpy`, and `tools/setup_llama_cpp.sh`).

---
# Milestone 3 additions (2026-10-21)

| Step | Result |
|---|---|
| Voice Kotlin (`voice/*.kt`) compiled with kotlinc against `android.jar` (API 34) and the **real sherpa-onnx 1.13.8 AAR classes** | succeeded |
| `VoiceHostVerify.kt` on a JVM with the **real** sherpa-onnx ASR (Moonshine, Whisper) and TTS (Piper x2, Kokoro), the real llama.cpp JNI engine (random test model), real recorded English speech | **103 passed, 0 failed** (`docs/host-verify-result-voice.txt`) |
| Same-process benchmark of the ASR/TTS models (`ai-training/voice/host_bench.py`) | results in `docs/voice-host-bench.json` (see `VOICE.md` for the caveats) |
| `ai-training/evaluation/baseline_llamaserver.py` end to end with the prebuilt `llama-server` b11791 and the random test model | ran; output is gibberish by design and is **not** a result |
| Pytest | 43 passed + 1 env-gated (44 of 44 when run with a real llama-server) |
| Gradle / AGP / Compose / NDK build, APK, device run | **not possible in the sandbox** (see `BUILD_ANDROID.md`) |

Bugs found by these checks and fixed: sherpa TTS callback `NoSuchMethodError` (Kotlin 2.x lambda vs typed `invoke`), a controller race that could free freshly loaded speech engines after a quick stop/start,
and a per-callback allocation in hold-to-talk. Everything stated in the first part of this file still holds.
**Still unproven:** microphone capture, audio playback, the Compose screens, latency and memory on a phone, and any language-model quality.
