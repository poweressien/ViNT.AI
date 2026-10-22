# Offline verification record

**Airplane-mode test on a real phone: NOT DONE. Offline operation of ViNT on a device is UNVERIFIED.**
Nothing below should be read as a pass for that test.

## Actually done (sandbox)
| Check | Result |
|---|---|
| Static audit `tools/audit_android_offline.py`: no permissions in manifest, no networking/analytics libraries, no networking APIs in Kotlin; negative-tested (it fails when INTERNET or okhttp is added) | passed |
| llama.cpp compiled with `LLAMA_CURL=OFF` (no HTTP client compiled in) | configured in CMake; the host build used the same flag |
| Engine + JNI on a JVM with the pinned llama.cpp and a tiny **random** mechanics model: load, generate, cancel, unload, UTF-8 (35 checks) | passed: plumbing only, not a language model (`HOST_VERIFICATION.md`) |
| Static audit now allowlists permissions: only `RECORD_AUDIO` is accepted (INTERNET, contacts, etc. fail; negative-tested) | passed |
| Speech recognition + synthesis + LLM turn on a JVM with real sherpa-onnx models, no network used (host cannot reach those models' hosts at run time; models were local files) | 103 checks passed (`HOST_VERIFICATION.md`): host, not a phone, random LLM |
| Slang search (SQLite FTS5) and history storage use only local files | slang search tested in Python; Android code type-checked, not run |

A missing INTERNET permission is **not** proof of offline behaviour; it is one input to it.

## Voice steps to add on a phone
9. Import speech ZIPs (Voice → Speech models); load the language model; allow the microphone.
10. Airplane mode on. Start voice mode; hold the button and speak; confirm transcript, a model-generated reply and spoken audio. Tap Stop while it speaks; confirm silence within about a second.
11. Press Home during voice mode; confirm the microphone indicator disappears (voice mode ends). Reopen: status says voice mode is off.
12. Record measured latency (shown on the Voice screen: recognition ms, first-audio ms, total ms) and memory (`adb shell dumpsys meminfo ng.pidgingpt`).
13. Say the same 10 Pidgin phrases and record what was recognised; this is the real ASR measurement for Pidgin and has not been done.

## To do on a phone (record date, device, Android version, model, result)
1. Install the APK; import a real GGUF (Model screen); wait for **Ready**.
2. Turn on airplane mode, Wi-Fi and Bluetooth off. Force-stop and reopen ViNT.
3. Load the model; send "Guy, how body?"; confirm a streamed reply and tokens/s.
4. Dictionary → search "wahala".
5. Start a chat, close the app, reopen → History shows it; open it.
6. `adb shell dumpsys package ng.pidgingpt | findstr /i permission` → no `INTERNET`.
7. Optional traffic proof: router/PCAPdroid-style capture while chatting shows no connections from the app.
8. `gradlew connectedAndroidTest` (`OfflineVerificationTest`; needs `files/models/test.gguf`, which may be a mechanics model for plumbing or a real one).

| Date | Device / Android | Model | Steps passed | Notes |
|---|---|---|---|---|
| — | — | — | none yet | — |
