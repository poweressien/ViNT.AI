# Language model selection

**Status: a model is SELECTED PROVISIONALLY. It has NOT been downloaded, loaded, or evaluated.** The sandbox could not reach Hugging Face, so
no real language-model weights were obtained, and **no real language model has been run through ViNT**. The baseline gate below must pass first.

## Decision (provisional)
| Role | Model | Why |
|---|---|---|
| **Primary** | **Qwen3-1.7B** (instruct/hybrid) converted to GGUF Q4_K_M | Licence verified at source; architecture verified in the pinned llama.cpp; small enough to try on mid-range phones |
| Smaller fallback | Qwen3-0.6B | for phones that cannot hold the primary |
| Step-up to test | Qwen3-4B | better quality if the phone has the RAM |
| Newer family to evaluate | Qwen3.5-0.8B / 2B / 4B | README claims 201 languages/dialects and llama.cpp support, but its licence is only "see the file with the weights" (unverified here) |

## Evidence (what was actually checked, 2026-10-21)
- **Licence:** the official QwenLM/Qwen3 README states "All our open-weight models are licensed under Apache 2.0" (sizes 0.6B, 1.7B, 4B, 8B, 14B, 32B and MoE). *This is the README's claim; read the LICENSE on the exact model page you download.*
- **Architecture/runtime:** `qwen3` (and `qwen35`, `gemma3`, `granite`, `smollm3`, `llama`) are present in `src/llama-arch.cpp` of the pinned llama.cpp checkout (`52ffa02…`). Qwen3 chat text is ChatML (`<|im_start|>`), which llama.cpp's template applier recognises. Conversion and loading of this exact model were **not** run.
- **Qwen3 specifics handled in code:** Qwen3 writes `<think>…</think>` reasoning by default. ViNT adds `/no_think` for `qwen3*` architectures and filters think blocks from the stream (`ThinkFilter.kt`, 7 host checks) so reasoning is never shown, saved, or spoken.
- **Nigerian Pidgin:** the Qwen3 and Qwen3.5 READMEs mention "100+" / "201" languages and dialects. **Neither names Nigerian Pidgin, Yoruba, Igbo or Hausa.** There is **no evidence** of Pidgin ability. **Do not describe this model as Pidgin-capable or Pidgin-trained.** It is a general-purpose multilingual model until measured.
- **Rejected for now:** Llama 3.2 1B/3B (community licence with attribution, naming and use-policy obligations: heavier than Apache-2.0; text fetched from `meta-llama/llama-models`); Gemma/SmolLM2 (no licence evidence reachable).
- **Sizes:** not stated here. Record the real GGUF size and sha256 after conversion; do not trust estimates.

## Gate: what must happen before this becomes "the model"
1. Get the weights (needs internet + about 10 GB disk; Windows steps below).
2. Run the baseline on your PC and **read the replies yourself**: `ai-training/evaluation/baseline_llamaserver.py` (tested end to end with a real `llama-server`, but only with a random test model).
3. Score the same replies with `HUMAN_EVAL_RUBRIC.md` (2+ native Pidgin speakers). Automatic metrics are screening only.
4. Decide: ship as a general assistant that *tries* Pidgin with honest labelling, or fine-tune first (`ai-training/training`, never run so far). Only claim Pidgin training after a documented, verified fine-tune.

## Windows: make the baseline GGUF (UNTESTED here)
```powershell
pip install huggingface_hub "transformers" torch numpy sentencepiece gguf
huggingface-cli download Qwen/Qwen3-1.7B --local-dir qwen3-1.7b          # read the LICENSE file in the folder
powershell -File tools\setup_llama_cpp.ps1                               # pinned llama.cpp source (for convert_hf_to_gguf.py)
pip install -r android-app\third_party\llama.cpp\requirements.txt
python android-app\third_party\llama.cpp\convert_hf_to_gguf.py qwen3-1.7b --outfile qwen3-1.7b-f16.gguf --outtype f16
# prebuilt tools: https://github.com/ggml-org/llama.cpp/releases -> llama-b*-bin-win-cpu-x64.zip (contains llama-quantize.exe and llama-server.exe)
.\llama-quantize.exe qwen3-1.7b-f16.gguf qwen3-1.7b-q4_k_m.gguf Q4_K_M
Get-FileHash qwen3-1.7b-q4_k_m.gguf -Algorithm SHA256 ; (Get-Item qwen3-1.7b-q4_k_m.gguf).Length   # record both here
python ai-training\evaluation\baseline_llamaserver.py --server .\llama-server.exe --model qwen3-1.7b-q4_k_m.gguf --qwen3 --label baseline-qwen3-1.7b --out ai-training\evaluation\results
```
Then `adb push qwen3-1.7b-q4_k_m.gguf /sdcard/Download/` and import it in ViNT → Model.

## Other stages (unchanged, none beyond tooling done)
Data collection and QC (`datasets/`, 6 synthetic unverified seed entries) → fine-tune (`train_lora.py`, never run) → merge/quantize (`merge_and_quantize.sh`, never run) → Android deployment → offline evaluation on a phone (not done).
