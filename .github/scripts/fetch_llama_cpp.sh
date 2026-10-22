#!/usr/bin/env bash
# Fetch llama.cpp with the project's own setup script, then PROVE the checkout is the pinned commit.
set -euo pipefail
cd "$PROJECT_ROOT"
bash tools/setup_llama_cpp.sh
pin="$(awk '{print $1}' docs/LLAMA_CPP_PIN.txt)"
got="$(git -C android-app/third_party/llama.cpp rev-parse HEAD)"
[ "$got" = "$pin" ] || { echo "::error::llama.cpp is at $got but docs/LLAMA_CPP_PIN.txt pins $pin"; exit 1; }
echo "llama.cpp verified at pinned commit $got"
