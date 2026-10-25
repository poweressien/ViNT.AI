# Hybrid AI (milestone 4): offline English now, online AI when you allow it, Pidgin model later

**Status: written, NOT compiled, NOT run.** This environment has no Kotlin compiler, Gradle, Android SDK or network. What *was* run: the static audits and
Python tests (see `README.md`). The Kotlin unit tests in `android-app/app/src/test` were written but never executed. Expect first-build fixes; send the Gradle log.

## Architecture
```
UI (Compose)  ->  ChatViewModel  ->  AIProviderManager  ->  AIProvider
                                         |                    |- LocalProvider(LOCAL_ENGLISH)  \
 ConnectivityMonitor (NetworkCallback) --+                    |- LocalProvider(LOCAL_PIDGIN)    > LocalModelHost -> LlamaEngine (JNI, ONE native model)
 Settings (mode, consent, preferences) --+                    '- OnlineChatProvider -> HttpUrlTransport (the only network code)
```
| Spec name | Where |
|---|---|
| `AIProvider`, `LocalAIProvider`, `OnlineAIProvider` | `ai/AIProvider.kt` |
| `AIProviderManager` | `ai/AIProviderManager.kt` (pure Kotlin, no Android) |
| `ConnectivityMonitor` | `ai/ConnectivityMonitor.kt` |
| `ModelManager` (extended, not replaced) | `data/ModelManager.kt` + `ai/ModelProfile.kt` (`ModelProfile`, `ModelRegistry`) + `net/ModelDownloader.kt` |
| `ConversationRepository` (existing `ConversationStore`, schema v2 adds `messages.provider`) | `data/ConversationStore.kt` |
| Language routing | `ai/LanguageRouter.kt` (`LanguageRouter`, `PromptBuilder`, `ContextTrimmer`) |

The UI sends a request through the manager only; `tests/test_hybrid.py` fails if a screen references the engine or the network classes.

## Routing rules (all in `AIProviderManager`, all unit-tested with fake providers)
1. **Offline only**: the online provider is not in the plan and `availability()` is not even called. A forced or preferred "online" is ignored.
2. **Online usable** = mode allows it + you gave consent + the default network is *validated* (Android's own internet check: false for captive portals, dead Wi-Fi, DNS failure; true for Wi-Fi with mobile data off) + provider configured + not in a 30 s failure cooldown.
3. **Automatic**: online first if usable, else local; except a *verified* local Pidgin model answers Pidgin requests first. **Online preferred**: online first whenever usable. Both fall back to local.
4. The plan is fixed when you send. Connectivity changes never restart a reply or send it twice. Every request id is accepted once; a second send while one runs is refused (`Busy`).
5. Fallback to the next provider happens **only if the failing provider has streamed nothing yet**. After text streamed, the partial reply is kept and you choose **Retry** or **Retry offline** (never automatic: that is what prevents duplicate replies and duplicate charges).
6. No usable provider -> `Rejected` with fix instructions; the user's message is not saved/cleared (the draft returns to the box).
7. Status line shows the *actual next provider*: "Online — Cloud AI", "Offline — Local English model", "Offline — Local Pidgin model", "Local model not installed", "Connecting…", "Online service unavailable — switching to offline". A cloud icon line "Your next message will be sent online" appears when that is true.

## Offline English model
* Candidate: **Qwen2.5-1.5B-Instruct Q4_K_M** (catalog entry `ModelCatalog.QWEN25_1_5B`). Qwen3-1.7B import still works (`/no_think` + think filter kept).
* **Not yet verified here:** that this exact file loads in the pinned llama.cpp (`52ffa02…`). `qwen2` is a long-standing llama.cpp architecture, but only a real load on a phone settles it. The app records the real outcome per model (`Runtime on this phone`).
* **Licence:** Apache-2.0 per the Qwen model cards and secondary listings I could reach. I could not fetch the official repo's LICENSE file from here: read it before redistributing.
* **Checksum:** I could not read the official file's SHA-256, so none is pinned (`sha256 = null`; I did not guess one). The downloader checks (a) size, (b) the SHA-256 the host sends in `x-linked-etag` (Hugging Face LFS), (c) GGUF header validity, and shows which level applied. To pin it: compute `Get-FileHash` on a copy you trust and set `CatalogEntry.sha256`.
* Download: only after you tap **Download** and confirm (address, size, licence, free space shown). `.part` file, resumable (HTTP Range), storage pre-check, atomic rename, delete on mismatch. Cancel keeps the partial. Stored in app-private storage; delete from the Model screen.
* One native model in memory at a time. Loading a different role's model unloads the current one. Loading is lazy (first use) and never on the main thread.
* **Pidgin quality of this model is unverified.** If it answers Pidgin, that is "observed", not verified fluency.

## Online provider
Any **OpenAI-compatible** `/chat/completions` endpoint (streaming SSE or plain JSON), HTTPS only, system CAs only, timeouts, cancel (closes the connection), `X-Request-Id`/`Idempotency-Key` header. Retries: **only when the connection could not be opened** (max 1 by default); a request that reached the server is never re-sent. HTTP 401/403, 429 (rate limit vs quota), 5xx, timeouts map to clear messages; failure never produces text.
* **No secret is in the source, repo or APK** (audited, see below). Two auth modes:
  * **Backend proxy (production)**: the app calls *your* server; your server holds the provider key. Optional proxy access token (not a provider key; the field rejects key-shaped input). A token in an app is extractable, so rate-limit per device/user on the server.
  * **Own provider key (testing only)**: allowed **only in debuggable builds**; stored encrypted with an Android Keystore AES-GCM key; warns it is not safe to distribute. Keystore encryption protects it at rest on the phone, not against someone who controls the phone.
* Proxy requirements: HTTPS with a valid certificate; hold the provider key as a server secret; accept `POST /v1/chat/completions`; stream SSE through; enforce per-client rate limits and a spend cap; do not log message bodies; pass `Idempotency-Key` through or de-duplicate on it; return OpenAI-style error bodies (`insufficient_quota` -> quota message). *Not tested here: I did not build or run a proxy.*
* **Cost:** I did not verify any provider's pricing or free quota. ViNT does not claim online AI is free.
* Test connection sends one `ping` message with none of your chat; disabled in Offline only.

## Pidgin model later (designed, not built)
`ModelProfile` = id, name, file, format, architecture, runtime status (recorded from real loads), role (English/Pidgin), language evidence per language (`UNTESTED / OBSERVED / EVALUATED / USER_CONFIGURED`), explicit `markedAvailable`, licence + source, provenance, version, sha256/integrity, install and evaluation status.
A model is routed Pidgin requests only if **role = Pidgin AND marked available AND evidence is EVALUATED or USER_CONFIGURED AND installed** (`pidginEligible`). Installing a file never does this. To add yours: Model screen -> import your `.gguf` -> **This is my Pidgin model…** -> choose the basis. If it is missing or fails to load, the English model answers; user override ("Local Pidgin model first") exists.

## Language handling
Your message is stored and sent unchanged. `LanguageRouter` honours an explicit English/Pidgin choice, otherwise estimates with a small conservative marker list (it **estimates**; mixed text is detected; very short messages inherit the previous turn). The prompt changes, not your text: Pidgin -> the existing project Pidgin prompt; mixed -> "same mix, no formal translation, no stereotypes"; English -> English plus a *configurable* instruction (AI settings) for understanding Pidgin. Default reply language is now **Automatic** (was "always Pidgin" in milestone 3); choose Nigerian Pidgin in AI settings for the old behaviour.

## Voice
Unchanged and still offline: Whisper/Moonshine ASR, Piper/Kokoro TTS via sherpa-onnx; mic only while voice mode is on. Voice replies use the same manager but **stay offline unless you enable "Allow online AI in voice mode"** (off by default); then only recognised *text* goes online, never audio. "Speak replies" toggle controls TTS in voice mode. **Not done:** reading text-chat replies aloud; no installed voice is a verified Nigerian accent.

## Privacy
Offline only guarantees no message text is sent (enforced and tested). Network permissions: `INTERNET` (online AI + user-started downloads), `ACCESS_NETWORK_STATE`. `network_security_config.xml`: no cleartext, system CAs only. All raw networking is confined to `ng/pidgingpt/net` (`tools/audit_android_offline.py` fails otherwise), no third-party network libs, no analytics, no logging of message content or keys. Conversations: app-private SQLite; AI settings has "Clear all history".

## Install the English model (Windows)
1. Build and install the app (`BUILD_ANDROID.md`). 2. Open **⋮ -> Model -> Download**, read the dialog, tap Download (about 1 GB; Wi-Fi recommended). Or download `qwen2.5-1.5b-instruct-q4_k_m.gguf` yourself, `adb push` it, and use **Import**. 3. Send a message: the model loads on first use (or tap **Load**). 4. Check **Model -> Runtime on this phone** says "loaded successfully".

## Configure online AI
**⋮ -> AI mode & online**: enter the address (`https://your-proxy.example.com/v1`) and model name, choose proxy auth, optional token -> **Save**, switch **Allow sending my messages online** on (read the dialog), **Test connection**. Pick Automatic / Online preferred / Offline only.
