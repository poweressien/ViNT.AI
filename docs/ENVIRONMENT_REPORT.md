# Environment report (inspected 2026-10-20, build sandbox)

| Item | Finding |
|---|---|
| OS | Ubuntu 24.04, x86_64 |
| CPU / RAM | 1 core / 3.9 GiB, no swap |
| GPU | none (`nvidia-smi` not found) |
| Disk | ~10 GB free on `/` |
| Python | 3.12.3 (numpy, pandas present; torch/transformers/peft NOT installed) |
| Java | OpenJDK 21; Gradle NOT installed |
| Android SDK / NDK | NOT installed |
| Network | reachable: pypi.org, github.com. **Unreachable:** huggingface.co, dl.google.com, maven.google.com, services.gradle.org |

## What this means (blockers, not excuses)
- **No APK can be built here**: no Android SDK/NDK, and Google/Gradle hosts are blocked. Build on your PC (see BUILD_ANDROID.md).
- **No model can be downloaded, evaluated, trained, or quantized here**: no Hugging Face access, no GPU, 3.9 GB RAM.
- **Everything else was built and tested here**: dataset pipeline, slang DB, evaluation harness, offline audit tool.
