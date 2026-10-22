#!/usr/bin/env bash
# Merge LoRA adapter -> HF model -> GGUF -> Q4_K_M.  STATUS: untested (needs trained adapter + llama.cpp checkout).
# Usage: merge_and_quantize.sh <base_model_id> <revision> <adapter_dir> <out_dir> <llama.cpp_dir>
set -euo pipefail
BASE="$1"; REV="$2"; ADAPTER="$3"; OUT="$4"; LLAMA="$5"
mkdir -p "$OUT"
python3 - <<PY
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import PeftModel
m = AutoModelForCausalLM.from_pretrained("$BASE", revision="$REV", torch_dtype="auto")
m = PeftModel.from_pretrained(m, "$ADAPTER").merge_and_unload()
m.save_pretrained("$OUT/merged"); AutoTokenizer.from_pretrained("$BASE", revision="$REV").save_pretrained("$OUT/merged")
PY
python3 "$LLAMA/convert_hf_to_gguf.py" "$OUT/merged" --outfile "$OUT/model-f16.gguf" --outtype f16   # fails if arch unsupported: check, don't assume
"$LLAMA/build/bin/llama-quantize" "$OUT/model-f16.gguf" "$OUT/pidgingpt-Q4_K_M.gguf" Q4_K_M
ls -l "$OUT"/*.gguf   # record REAL sizes in docs/MODEL_REPORT.md
sha256sum "$OUT/pidgingpt-Q4_K_M.gguf" | tee "$OUT/pidgingpt-Q4_K_M.gguf.sha256"
