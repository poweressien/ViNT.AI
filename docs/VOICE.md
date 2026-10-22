# ViNT voice chat: design, models, and what was actually measured

**Status:** implemented in source and verified on a Linux host with the real speech engines. **Not run on a phone. No microphone capture, speaker playback,
Compose voice screen, or airplane-mode test has happened.** No real language model has been used for voice either (only a random test model).

## Pipeline (all local)
`microphone (AudioRecord 16 kHz) → ASR (sherpa-onnx) → language model (llama.cpp, same model + conversation as text chat) → TTS (sherpa-onnx) → AudioTrack`
- Push-to-talk first: hold to talk, or tap once for hands-free (energy VAD ends the turn after about 0.9 s of silence). Stop button interrupts listening, thinking and speaking.
- Turn-taking is a pure state machine (`VoiceTurnMachine`): Off → Idle → Listening → Transcribing → Thinking → Speaking → Idle. The microphone may be open **only** in Listening.
- Leaving voice mode, or the app going to the background (`MainActivity.onStop`), stops capture, playback and frees the speech models. Audio is never written to disk or sent anywhere.
- TTS runs sentence by sentence while the LLM is still generating (`SentenceChunker`); emoji, markdown and links are removed before speaking (`SpeechText`).
- Without an installed voice, replies are shown but not spoken, and the screen says so. Without a Ready language model, voice mode cannot start. No reply is ever fabricated.

## Models obtained (real files, hashes in `tools/voice_models.json`)
| Component | File | Size | Licence (source) | Notes |
|---|---|---|---|---|
| ASR | Moonshine tiny EN int8 | 108 MB download | MIT (LICENSE in package) | English only |
| ASR | Whisper tiny (int8 used) | 116 MB download, 57 MB repacked | MIT (openai/whisper); multilingual incl. Yoruba/Hausa, **no Pidgin** | run in English mode |
| TTS | Piper en_GB northern_english_male medium int8 | 21 MB | CC BY-SA 4.0 (MODEL_CARD: OpenSLR 83) | 22.05 kHz |
| TTS | Piper en_GB southern_english_female low int8 | 21 MB | CC BY-SA 4.0 (MODEL_CARD) | 16 kHz, low quality |
| TTS | Kokoro int8 en v0.19 | 103 MB | Apache-2.0 (LICENSE in package) | 11 speakers, 24 kHz, slow on CPU |
| Runtime | sherpa-onnx 1.13.8 Android AAR | 50 MB | Apache-2.0 | arm64-v8a, includes ONNX Runtime; adds **no** permissions |

## Measured on the host (Linux x86_64, **1 CPU core, not a phone**; `docs/voice-host-bench.json`, `docs/host-verify-result-voice.txt`)
- **ASR on 3 real recorded English utterances** (test recordings shipped with the models): corpus WER **0.039** (Moonshine) and **0.078** (Whisper tiny). n = 3, so this is a sanity check, **not** an accuracy estimate.
- **Speed (real-time factor, lower is faster):** Moonshine 0.03–0.09, Whisper tiny 0.10–0.25, Piper 0.2–0.37, **Kokoro 2.4–2.7 (slower than real time)**. Repeated runs on the shared host varied by up to 2.5x; treat as rough. Phone numbers are unknown.
- **Memory:** peak process RSS about 1.0 GB while holding all five models together in the benchmark. Per-model phone memory is unmeasured.
- **Pidgin:** there is no Pidgin speech in the sandbox, so **ASR accuracy on real Pidgin speakers was not measured.** A synthetic round trip (English TTS reading Pidgin text, then ASR) gave WER 0.43–0.47 and shows the expected failure: `wetin dey` → "Wet and day" / "Wedding day", `wahala` → "Noah Hala" / "noahala", `abeg` → "A big" / "A beg", `kampe` → "camp". This is synthetic speech, so it says nothing about real speakers; it does show these English-only recognisers have no Pidgin vocabulary. `Prompts.system(voice=true)` tells the model transcripts may be mis-heard (an unmeasured mitigation).
- **Voices:** all three are English voices, none is Nigerian, none was evaluated by listeners.

## Host verification of the voice layer (103 checks, 0 failed)
Real sherpa-onnx ASR + TTS through the same Kotlin wrappers as the app, the real llama.cpp JNI engine (random test model), and test doubles only where labelled. It covers: the turn
state machine (20,000 random events keep every invariant); VAD on synthetic audio and **real human speech**; speech-text cleaning and chunking; reasoning-block filtering; JSON + voice catalog;
zip-slip / size-bomb protection and import of the real model ZIPs; ASR on real speech; TTS streaming and early stop; full ASR→LLM→TTS turns; interrupt (stopped in 554 ms on the host);
silence input; missing voice; LLM failure; context overflow surfaced as an error. **It found a real bug**: sherpa's JNI needs an explicit `Function1` object for the TTS callback; a Kotlin lambda crashes with
`NoSuchMethodError` (fixed in `SherpaEngines.kt`).
Reproduce: `tools/host-verify/run_voice_verify.sh` (see the header of that script for the environment variables).

## Known limitations and open issues
1. **Distribution licence blocker.** Piper and Kokoro need eSpeak NG. The sherpa library contains eSpeak NG code (its CMake builds an eSpeak NG fork; the native lib carries "Wrong version of espeak-ng-data" and `ESPEAK_DATA_PATH`), and upstream eSpeak NG is **GPL-3.0-or-later**. An APK containing it is a combined work with GPL obligations (offer source, GPL-compatible licence for the combination). Not a lawyer: get advice before any public release. Options: accept a GPL-compatible licence for ViNT; build sherpa-onnx without eSpeak NG and choose TTS models that do not need it; or use another TTS. Personal testing is unaffected.
2. Piper voices are CC BY-SA 4.0: keep attribution; share-alike may apply to derived voice models.
3. English-only recognisers will mis-transcribe Pidgin. A Pidgin-capable recogniser needs consented Pidgin speech data and fine-tuning (see `VOICE_PROFILES.md`).
4. Energy VAD can be fooled by noise or miss quiet speech. Whisper may hallucinate text on silence (Moonshine did not on 2 s of digital silence in the host test).
5. Hands-free (tap) mode and the Compose screen are untested on a device. Sample rate conversion (phone mic 16 kHz) is requested directly, so some phones may need a fallback.
6. The speech models are imported as ZIPs made on your PC (`tools/fetch_voice_models.py`); ViNT never downloads anything.
