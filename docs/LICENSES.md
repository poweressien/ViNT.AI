# Licenses and attribution (complete as components are chosen)
- **ViNT source:** no licence chosen by the owner yet (add a LICENSE file).
- **llama.cpp:** MIT, "Copyright (c) 2023-2026 The ggml authors". Pinned at commit in `LLAMA_CPP_PIN.txt`. Include the MIT notice in distributed binaries.
- **Base / fine-tuned model:** not selected. Record model ID, revision, licence text, and any required attribution (e.g. Llama licences require attribution and the agreement). See `MODEL_SELECTION.md`.
- **Datasets:** see `datasets/manifests/sources.yaml`. Seed records are project-authored, AI-drafted, synthetic and unverified (CC0).
- **Brand reference image:** supplied by the product owner; kept unmodified in `docs/brand-assets/`.
- **AndroidX / Compose / Kotlin:** Apache 2.0 (fetched by Gradle at build time).

## Speech (milestone 3)
- **sherpa-onnx 1.13.8** (runtime AAR, Apache-2.0, `tools/voice_models.json`) bundles **ONNX Runtime** (MIT upstream; not re-verified here).
- **Models** (not bundled; imported by the user): Moonshine tiny EN (MIT), Whisper tiny (MIT), Piper voices en_GB northern_english_male / southern_english_female (CC BY-SA 4.0 per their MODEL_CARD, dataset OpenSLR 83), Kokoro v0.19 int8 (Apache-2.0). Hashes pinned in `tools/voice_models.json`.
- **eSpeak NG** (GPL-3.0-or-later upstream): used by Piper/Kokoro text-to-speech; the sherpa native library contains eSpeak NG code. **Unresolved before public distribution of an APK** (GPL obligations for the combined work). See `VOICE.md`, "Known limitations" 1.
- **Language model:** provisional Qwen3-1.7B (Apache-2.0 per the official README, to be confirmed on the model page). See `MODEL_SELECTION.md`.

## Milestone 4 additions
* **Qwen2.5-1.5B-Instruct GGUF** (optional download): Apache-2.0 according to the Qwen model cards and secondary listings reachable from the build environment; the official LICENSE file was not fetched. Verify at https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF before redistributing. ViNT does not bundle it.
* **Online AI provider**: whatever service you configure has its own terms, pricing and privacy policy. None is bundled or endorsed.
