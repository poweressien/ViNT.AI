# Fetch llama.cpp at the pinned commit into android-app\third_party\llama.cpp (Windows PowerShell).
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$pin  = (Get-Content "$root\docs\LLAMA_CPP_PIN.txt").Split(" ")[0]
$dir  = "$root\android-app\third_party\llama.cpp"
if (-not (Test-Path "$dir\.git")) { New-Item -ItemType Directory -Force $dir | Out-Null; git -C $dir init -q; git -C $dir remote add origin https://github.com/ggml-org/llama.cpp }
git -C $dir fetch --depth 1 origin $pin
git -C $dir checkout -q FETCH_HEAD
"llama.cpp at " + (git -C $dir rev-parse HEAD)
