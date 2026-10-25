# ViNT — Your Pidgin. Your AI. Offline.
(Repository folder and some script names still say *pidgingpt*; all user-facing text says ViNT.)

## Run #4 finding
CI stopped at `gradle :app:help` with "plugin com.android.application not found ... must include a version number". That message means the root `android-app/build.gradle.kts` Gradle evaluated had **no version** on the plugin, but the file in this project declares `version "8.5.2" apply false` exactly once and settings has the plugin repositories, so the checked-out file differs from this project. New `tools/check_gradle_layout.py` runs in CI before Gradle, prints the files as checked out, and fails with a precise reason.

## CI workflow (rewritten after run #3 failed at SDK setup)
Run #3 died in `android-actions/setup-android@v3`, whose default package list still asks for the removed `tools` package. The workflow no longer uses that action: it uses the SDK preinstalled on `ubuntu-latest` and installs exactly the packages named in `android-app/gradle.properties` (`vint.compileSdk/buildTools/ndk/cmake`, also read by `build.gradle.kts`). Jobs: python, host-verify (JNI), android, summary (honest). Still **never run to completion**.

`.github/workflows/android-build.yml` is new (you deleted the old one). Written but **never run**; push it and send me the log of the first run.

## Correction pass (after milestone 4)
* Fixed: milestone 4 had changed `private const val VOICE_NOTE` to public, which broke `tests/test_baseline.py::test_system_prompts_json_matches_kotlin_exactly`. The literal is private again (the test is unchanged) and `Prompts.voiceNote()` exposes it read-only. The prompt text is byte-identical to `ai-training/evaluation/system_prompts.json`.
* Local results: `python -m pytest -q` equivalent: **0 failures**, 1 skipped (needs `LLAMA_SERVER`); both audits pass. `pytest` was not installable offline, so a stub-based runner was used: re-run the real `python -m pytest -q` on your side.
* **CI: NOT run.** I have no access to your GitHub repository or Actions. `docs/ci/MERGE_INTO_EXISTING_WORKFLOW.md` lists what your real workflow needs. **Do not treat the project as build-ready** until Gradle configuration, Kotlin and native compilation, `testDebugUnitTest` and APK verification succeed in CI.

## Milestone 4 report (honest): hybrid AI
| Question | Answer |
|---|---|
| **Does it build? Is there an APK?** | **Unknown / No.** No Kotlin compiler, Gradle, Android SDK or network in the sandbox. Nothing Kotlin was compiled this milestone, and the M3 Compose/ViewModel code was never compiled either. Expect first-build fixes. |
| What was implemented (source only) | Provider architecture (`ai/`, `net/`): `AIProvider`/Local/Online, `AIProviderManager`, `ConnectivityMonitor` (NetworkCallback, validated-internet), `LocalModelHost`, `ModelProfile`/`ModelRegistry`, `LanguageRouter`, OpenAI-compatible online provider, resumable checksum-checked downloader, Keystore `SecretStore`, AI-settings screen, status line, retry, per-message provider labels. See `docs/HYBRID_AI.md`. |
| Tests written | 89 JUnit tests in `android-app/app/src/test` (fake providers/transport, no key needed). **Written, never executed.** |
| Tests actually run | Python static tests: **26 passed** (`test_audit.py`, `test_hybrid.py`, `test_brand.py`) + existing voice/eval/pipeline tests pass; network audit and brand audit pass; bracket-balance check on all 40+ Kotlin files. `pytest` itself was unavailable offline, so a minimal runner executed them. `test_baseline.py` was not run. |
| Real GGUF loaded? | **No.** Qwen2.5-1.5B is in the catalog but was never downloaded or loaded; checksum **not pinned** (could not read the official one); licence Apache-2.0 per model cards, official LICENSE not fetched. |
| Online provider tested? | **No**, only against fake HTTP. Any real provider/proxy is untested; pricing/quota unverified. |
| Phone tests | **None** (`docs/OFFLINE_VERIFICATION.md` has the manual list). |
| Privacy change | The app **now declares INTERNET + ACCESS_NETWORK_STATE** (it had none). Online is opt-in, off by default, blocked by Offline only; the audit now confines raw networking to `ng/pidgingpt/net`. |
| Decisions to check | Palette kept as the project's sampled values (`#0F1115/#2FA36B/#20242C/#373D4A`, per `BRAND.md`), not the prompt's off-by-one hex. App id kept `ng.pidgingpt` (the prompt's `ng.pidgingt` looks like a typo). Reply language default is now Automatic. Your real GitHub workflow was not in the zip: `docs/ci/android-build.proposed.yml` is a proposal. |
| Not done | Text-chat read-aloud; Pidgin model (interface only); running the CI; pinned model checksum. |

---
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
python tools/audit_android_offline.py android-app                        # network audit (M4: INTERNET allowed only in ng/pidgingpt/net)
tools/host-verify/run_host_verify.sh                                     # JNI + engine (35 checks)
tools/host-verify/run_voice_verify.sh                                    # voice layer with real speech models (103 checks)
python tools/fetch_voice_models.py --out dist/voice-models               # verified speech model ZIPs
```
## Next (details in `TODO.md`)
1. Build on Windows (`docs/BUILD_ANDROID.md`), send the first Gradle error log. 2. Get Qwen3-1.7B GGUF + run the baseline (`docs/MODEL_SELECTION.md`). 3. Phone tests (`docs/OFFLINE_VERIFICATION.md`). 4. Decide the GPL/eSpeak question. 5. Collect consented Pidgin speech for a Pidgin-capable recogniser/voice (`docs/VOICE_PROFILES.md`).

Docs: `HYBRID_AI.md` `BRAND.md` `BUILD_ANDROID.md` `HOST_VERIFICATION.md` `LICENSES.md` `MODEL_SELECTION.md` `OFFLINE_VERIFICATION.md` `VOICE.md` `VOICE_PROFILES.md`. Layout: `android-app/ ai-training/ datasets/ models/ tools/ tests/ docs/ voices/`
