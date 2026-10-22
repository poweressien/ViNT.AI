# ViNT — Your Pidgin. Your AI. Offline.
(Repository folder and some script names still say *pidgingpt*; all user-facing text says ViNT.)

## Milestone 3 report (honest)
| Question | Answer |
|---|---|
| **APK built?** | **No. No APK path exists.** Android/Gradle/Maven hosts are unreachable from the sandbox (`Could not resolve host`: dl.google.com, maven.google.com, repo.maven.apache.org, plugins.gradle.org, services.gradle.org). Windows steps and the exact blocker: `docs/BUILD_ANDROID.md`. |
| What compiled? | JNI C++ (host, pinned llama.cpp); all `voice/*.kt`, `LlamaEngine`, `GgufInfo`, `Prompts`, `ThinkFilter` (kotlinc, against the **real sherpa-onnx AAR classes** and `android.jar`); data layer (type-checked). **Never compiled:** Compose screens (incl. `VoiceScreen`), `ChatViewModel`, `MainActivity`, Gradle, NDK build. |
| Tests passed | **43 pytest passed + 1 env-gated test** (the real-`llama-server` test; it was run with the env vars set and passed: 44 of 44) · **35** JNI/engine host checks · **103** voice host checks (real ASR/TTS engines). |
| Model files actually obtained | Speech: Whisper-tiny, Moonshine-tiny-EN, Piper (2 voices), Kokoro, sherpa-onnx AAR (SHA-256 pinned in `tools/voice_models.json`). Language model: **none** (Hugging Face unreachable). |
| Real language model tested? | **No.** Only a random test model for plumbing. Provisional selection: Qwen3-1.7B, with a gate (`docs/MODEL_SELECTION.md`). No Pidgin evidence exists for it. |
| ASR and TTS work offline? | **Yes on a Linux host with real models** (no network used; real English speech WER 0.04–0.08 on n=3; TTS produces audio). **Not on a phone.** Pidgin ASR accuracy was **not measured**; a synthetic round trip shows English recognisers mis-hear Pidgin words. |
| Voice profiles genuinely available | 2 English voices (UK male, UK female, Piper) + 1 experimental (Kokoro) once imported. **No Nigerian accent voices.** 5 regional Pidgin profiles are listed as *unavailable* placeholders. |
| Airplane-mode test on a phone? | **Not done.** |
| Phone latency / memory | **Not measured.** Host numbers only (`docs/VOICE.md`). |
| Biggest new risk found | **GPL:** the sherpa library contains eSpeak NG (GPL-3.0+) code for Piper/Kokoro; resolve before public distribution (`docs/VOICE.md`). |

## What was implemented in milestone 3
- **Voice chat in source:** voice screen (permission handling, hold-to-talk + hands-free tap, level meter, transcripts, stop/interrupt, voice list, speech-model import, text-chat fallback), `VoiceController` (mic only while Listening; released on stop/background), energy VAD, turn state machine, sentence-by-sentence TTS, shared LLM path with text chat, think-block filter + `/no_think` for Qwen3.
- **Voice profile system:** JSON catalog + policy, unavailable placeholders, validators and tests; consent-aware recording manifests, speaker-level splits, gated per-group reporting, revocation (`ai-training/voice/`).
- **Speech model tooling:** pinned manifest, verify-and-repack to import ZIPs, safe ZIP import (zip-slip tested on real model ZIPs).
- **Baseline evaluation tool** for the language model (tested end to end against a real `llama-server`).
- Bugs found by real-engine testing and fixed: sherpa TTS callback `NoSuchMethodError`; engine-release race; JNI prompt-size handling already fixed in M2.

## Run what works now
```bash
pip install pytest pillow && python -m pytest tests -q                  # 43 passed, 1 skipped unless LLAMA_SERVER + MECHANICS_GGUF are set
python tools/audit_android_offline.py android-app                        # only RECORD_AUDIO allowed
python tools/audit_brand_palette.py android-app
tools/host-verify/run_host_verify.sh                                     # JNI + engine (35 checks)
tools/host-verify/run_voice_verify.sh                                    # voice layer with real speech models (103 checks)
python tools/fetch_voice_models.py --out dist/voice-models               # verified speech model ZIPs
```
## Next (details in `TODO.md`)
1. Build on Windows (`docs/BUILD_ANDROID.md`), send the first Gradle error log. 2. Get Qwen3-1.7B GGUF + run the baseline (`docs/MODEL_SELECTION.md`). 3. Phone tests (`docs/OFFLINE_VERIFICATION.md`). 4. Decide the GPL/eSpeak question. 5. Collect consented Pidgin speech for a Pidgin-capable recogniser/voice (`docs/VOICE_PROFILES.md`).

Docs: `BRAND.md` `BUILD_ANDROID.md` `HOST_VERIFICATION.md` `LICENSES.md` `MODEL_SELECTION.md` `OFFLINE_VERIFICATION.md` `VOICE.md` `VOICE_PROFILES.md`. Layout: `android-app/ ai-training/ datasets/ models/ tools/ tests/ docs/ voices/`
