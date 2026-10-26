package ng.pidgingpt.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ng.pidgingpt.data.Compat
import ng.pidgingpt.data.LanguageMode
import ng.pidgingpt.data.ModelInfo
import ng.pidgingpt.inference.ChatTurn
import ng.pidgingpt.inference.EngineState
import ng.pidgingpt.inference.InferenceEngine
import ng.pidgingpt.inference.Prompts
import ng.pidgingpt.inference.ThinkFilter

/**
 * Owns the ONE native engine, so English and Pidgin local providers can never hold two copies of a model in memory:
 * asking for a different file unloads the current one first (LlamaEngine.load frees the old handle under its write lock).
 * Everything here is blocking: call off the main thread.
 */
class LocalModelHost(
    val engine: InferenceEngine,
    private val listModels: () -> List<ModelInfo>,
    private val compat: (ModelInfo, Int) -> Compat,
    private val registry: ModelRegistry,
    private val contextTokens: () -> Int,
    private val threads: () -> Int,
    private val preferredFile: () -> String?,
    private val onSelected: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<EngineState>(EngineState.NotInstalled)
    val state: StateFlow<EngineState> = _state.asStateFlow()
    @Volatile var loadedFile: String? = null; private set
    @Volatile var loadedArch: String? = null; private set
    private val loadLock = Any()

    fun refresh() {
        val s = _state.value
        if (s is EngineState.Ready || s is EngineState.Loading) return
        _state.value = if (listModels().isEmpty()) EngineState.NotInstalled else if (s is EngineState.Failed) s else EngineState.Unloaded
    }

    fun roleOf(info: ModelInfo): ModelRole = registry.get(info.file.name)?.role ?: ModelRole.ENGLISH

    /** Which installed file would answer for [kind]. A Pidgin model is chosen ONLY if its profile is eligible (role + marked available + evaluated/configured). */
    fun select(kind: ProviderKind): ModelInfo? {
        val valid = listModels().filter { it.gguf != null }
        return when (kind) {
            ProviderKind.LOCAL_ENGLISH -> valid.filter { roleOf(it) == ModelRole.ENGLISH }.let { l -> l.firstOrNull { it.file.name == preferredFile() } ?: l.firstOrNull() }
            ProviderKind.LOCAL_PIDGIN -> valid.firstOrNull { registry.get(it.file.name)?.pidginEligible == true }
            ProviderKind.ONLINE -> null
        }
    }

    fun ensureLoaded(info: ModelInfo): Result<Unit> = synchronized(loadLock) {
        if (loadedFile == info.file.name && _state.value is EngineState.Ready) return Result.success(Unit)
        val n = contextTokens()
        val c = compat(info, n)
        if (!c.ok) return fail(info, c.warning ?: "This model cannot be loaded.")
        _state.value = EngineState.Loading
        engine.load(info.file.path, n, threads()).fold({
            loadedFile = info.file.name; loadedArch = info.gguf?.architecture; onSelected(info.file.name)
            _state.value = EngineState.Ready(info.file.name, n)
            note(info, RuntimeStatus.LOADED_OK, null); Result.success(Unit)
        }, { fail(info, it.message ?: "Model failed to load.") })
    }

    private fun fail(info: ModelInfo, msg: String): Result<Unit> {
        if (loadedFile == info.file.name) { loadedFile = null; loadedArch = null }
        _state.value = EngineState.Failed(msg); note(info, RuntimeStatus.LOAD_FAILED, msg)
        return Result.failure(IllegalStateException(msg))
    }

    /** Records what really happened on this device. LOADED_OK is only ever written after the native runtime loaded the file. */
    private fun note(info: ModelInfo, r: RuntimeStatus, why: String?) {
        val p = registry.get(info.file.name) ?: defaultProfile(info)
        registry.upsert(p.copy(runtime = r, runtimeNote = why, architecture = info.gguf?.architecture ?: p.architecture))
    }

    fun loadByName(name: String?): Result<Unit> {
        val info = listModels().let { l -> l.firstOrNull { it.file.name == name } ?: l.firstOrNull { it.gguf != null } }
            ?: run { _state.value = EngineState.NotInstalled; return Result.failure(IllegalStateException("No model installed.")) }
        return ensureLoaded(info)
    }

    /** Diagnostic for the Model screen: loads the model and generates a few words, returning the real result or the real error. Blocking. */
    fun selfTest(fileName: String?, progress: (String) -> Unit): String {
        progress("Testing: loading the model\u2026")
        val t0 = System.currentTimeMillis(); val r = loadByName(fileName); val loadS = (System.currentTimeMillis() - t0) / 1000.0
        if (r.isFailure) return "LOAD FAILED after %.1f s: %s".format(loadS, r.exceptionOrNull()?.message ?: "unknown error")
        progress("Loaded in %.1f s. Generating a few words\u2026".format(loadS))
        val sb = StringBuilder()
        val g = runCatching { engine.generate(listOf(ChatTurn("user", "Say hello in one short sentence.")), 24, 0.2f) { sb.append(it) } }
        val res = g.getOrNull()
        return when {
            res == null -> "GENERATION CRASHED: ${g.exceptionOrNull()?.javaClass?.simpleName}: ${g.exceptionOrNull()?.message}"
            res.isFailure -> "GENERATION FAILED: ${res.exceptionOrNull()?.message}"
            else -> { val st = res.getOrThrow(); "OK. Loaded in %.1f s, then %d tokens in %.1f s. The model said: \"%s\"".format(loadS, st.tokens, st.millis / 1000.0, sb.toString().trim().ifEmpty { "(nothing)" }) }
        }
    }

    fun unload() { engine.unload(); loadedFile = null; loadedArch = null; _state.value = EngineState.Unloaded; refresh() }
    fun cancel() = engine.cancel()

    fun defaultProfile(info: ModelInfo): ModelProfile = ModelProfile(
        id = info.file.name.removeSuffix(".gguf"), displayName = info.gguf?.name ?: info.file.name, fileName = info.file.name,
        architecture = info.gguf?.architecture, license = info.gguf?.license, licenseSource = if (info.gguf?.license != null) "GGUF file metadata" else null,
        sha256 = info.sha256, sizeBytes = info.sizeBytes)
}

class LocalProvider(
    override val kind: ProviderKind,
    private val host: LocalModelHost,
    private val contextTokens: () -> Int,
    private val languageMode: () -> LanguageMode,
    private val pidginHelp: () -> String,
) : LocalAIProvider {
    init { require(kind.isLocal) }
    override val id: String = if (kind == ProviderKind.LOCAL_PIDGIN) "local-pidgin" else "local-english"
    override val capabilities = ProviderCapabilities(
        if (kind == ProviderKind.LOCAL_PIDGIN) "Local Pidgin model" else "Local English model", streaming = true, sendsDataOffDevice = false,
        languages = if (kind == ProviderKind.LOCAL_PIDGIN) setOf(ResponseLanguage.PIDGIN, ResponseLanguage.ENGLISH) else setOf(ResponseLanguage.ENGLISH))
    override val modelFileName: String? get() = host.select(kind)?.file?.name

    override fun availability(): Availability =
        if (host.select(kind) == null) Availability.Unavailable(UnavailableReason.NOT_INSTALLED, "No ${capabilities.displayName.lowercase()} is installed.") else Availability.Available

    override fun generate(request: AiRequest, onPiece: (String) -> Unit): ProviderStats {
        val info = host.select(kind) ?: throw AiException(AiFailure.NotInstalled("No ${capabilities.displayName.lowercase()} is installed."))
        host.ensureLoaded(info).onFailure { throw AiException(AiFailure.LoadFailed(it.message ?: "The local model could not be loaded.")) }
        val noThink = host.loadedArch?.startsWith("qwen3") == true
        val system = PromptBuilder.system(request.language, kind, languageMode(), request.voice, noThink, pidginHelp())
        val turns = Prompts.window(system, request.history, contextTokens(), request.maxNewTokens)
        val think = ThinkFilter()
        val r = host.engine.generate(turns, request.maxNewTokens, request.temperature) { raw -> val p = think.push(raw); if (p.isNotEmpty()) onPiece(p) }
        val tail = think.flush(); if (tail.isNotEmpty()) onPiece(tail)
        return r.fold({ ProviderStats(it.tokens, it.millis, "tokens", it.cancelled) }, {
            val m = it.message ?: "Generation failed."
            throw AiException(if (m.startsWith("Conversation is too long")) AiFailure.ContextTooLong(m) else AiFailure.NativeError(m))
        })
    }

    override fun cancel(requestId: String) = host.cancel()
}
