#!/usr/bin/env bash
# Fetch llama.cpp at the pinned commit into android-app/third_party/llama.cpp (not stored in this repo).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; PIN="$(awk '{print $1}' "$ROOT/docs/LLAMA_CPP_PIN.txt")"; D="$ROOT/android-app/third_party/llama.cpp"
[ -d "$D/.git" ] || { mkdir -p "$D"; git -C "$D" init -q; git -C "$D" remote add origin https://github.com/ggml-org/llama.cpp; }
git -C "$D" fetch --depth 1 origin "$PIN"; git -C "$D" checkout -q FETCH_HEAD
echo "llama.cpp at $(git -C "$D" rev-parse HEAD)"
