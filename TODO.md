# TODO  ([x] done and tested here · [~] written, only partly verified · [ ] not done)

## Milestone 4: hybrid AI
- [~] Provider architecture, routing rules, connectivity monitor, online provider, downloader, profiles, settings UI: **written, not compiled**
- [~] JUnit tests (89) written; **never run**. Python static tests + audits: run and passing
- [ ] First Gradle build + fix compile errors (send the log); run `:app:testDebugUnitTest`
- [ ] Real load of Qwen2.5-1.5B Q4_K_M on a phone; pin its SHA-256 in `ModelCatalog` after reading it from a source you trust; read the official LICENSE
- [ ] Choose a provider and deploy a backend proxy (`docs/HYBRID_AI.md`); test streaming, quota and error paths for real
- [ ] Manual device tests 1-11 in `docs/OFFLINE_VERIFICATION.md`
- [ ] Replace/merge `docs/ci/android-build.proposed.yml` into your real workflow; run it
- [ ] Text-chat read-aloud; measure Pidgin behaviour of the English model; build and evaluate your Pidgin model, then mark it on the Model screen
- [ ] Move model header parsing off the UI thread (`ModelScreen`, `ProfileBlock`, `DownloadCard` still call `models.list()` while composing)

## Milestone 3 (this delivery)
- [ ] **Android build / APK**: blocked in the sandbox (no Maven/Gradle access). Needs a Windows run of `docs/BUILD_ANDROID.md`
- [x] Real speech models + sherpa-onnx AAR obtained, hashed, licences read (`tools/voice_models.json`)
- [x] Offline ASR + TTS + pipeline verified on a Linux host with real engines (103 checks); real-speech VAD checked
- [~] Voice UI, controller, mic capture, playback: written; capture/playback/Compose never run on a device
- [~] Language model: provisional selection (Qwen3-1.7B) with a gate; weights not obtained; **no real LLM tested**
- [x] Baseline tool tested end to end (random model only)
- [x] Voice profile architecture + consent/eval pipeline + tests; **no Nigerian voices exist**
- [ ] Phone verification: text inference, ASR, TTS, interrupt, history, dictionary, airplane mode, latency, memory

## Blockers (need you / other resources)
- [ ] Windows build machine with Android SDK/NDK
- [ ] Internet to download the language model (and run conversion + baseline)
- [ ] **GPL decision** for eSpeak NG inside the sherpa library before any public release
- [ ] Consented Nigerian Pidgin speech data (and licence reading for any public corpus) for Pidgin ASR/TTS
- [ ] Native Pidgin speakers for human evaluation (text and speech)

## Next engineering
- [ ] Fix first-build errors from a real Gradle log; view icon/theme/voice screen on a device
- [ ] Measure mic sample-rate handling, AudioTrack latency, tokens/s, memory on 2+ phones; tune thread counts
- [ ] Tokenizer-based context windowing and KV-cache reuse (each reply re-decodes the whole prompt today)
- [ ] Pidgin ASR: evaluate Whisper multilingual/fine-tuning on consented data; try hotwords/lexicon biasing from the slang dictionary
- [ ] Listener (MOS) evaluation for any voice before marking it `available` as a Nigerian style
- [ ] ModelScreen/VoiceScreen: move GGUF header parsing off the UI thread

## Data and training (unchanged; none started beyond milestone 1 tooling)
- [ ] Verify CENCOS / AfriDataHub / DiscoNaija licences; grow beyond 6 synthetic seed entries; larger eval set (11 prompts today)
- [ ] GPU fine-tune (`train_lora.py`), merge, quantize, compare to baseline with real numbers

## Known limitations
- No language model bundled; chat needs a downloaded/imported local model or a configured online service.
- Speech recognisers/voices are English; Pidgin words are mis-heard/mispronounced; not measured on real Pidgin speakers.
- Dictionary = 6 AI-drafted, synthetic, unverified entries. App id stays `ng.pidgingpt`.
