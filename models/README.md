# models/
Model weights are NOT stored in Git (see .gitignore). This folder holds only this README and optional `*.sha256` files.

Provision a model:
1. Obtain a `.gguf` (baseline candidate or your fine-tuned `pidgingpt-Q4_K_M.gguf`; see `docs/MODEL_SELECTION.md`).
2. Copy it to the phone (USB / adb push / cloud download done on the phone beforehand).
3. In the app: ⋮ → Model → Import .gguf. The app copies it to private storage and prints its SHA-256.
4. Compare with the `.sha256` you produced at export time.
Until step 3 succeeds the app cannot chat, and it says so.
