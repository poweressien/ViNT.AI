package ng.pidgingpt

import android.app.Application
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ng.pidgingpt.ai.*
import ng.pidgingpt.data.*
import ng.pidgingpt.inference.*
import ng.pidgingpt.net.*
import ng.pidgingpt.voice.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class UiMessage(val id: Long, val role: String, val content: String, val provider: String? = null)

/** What answered the last message and why it may differ from the plan (for the status line under the chat). */
data class LastRun(val providerLabel: String, val detail: String, val switchNote: String?)

data class DownloadUi(val entryId: String, val done: Long, val total: Long, val running: Boolean, val message: String? = null, val finished: Boolean = false)

/** Everything the voice screen shows. Replies are never invented: [reply] only ever holds text produced by the loaded model. */
data class VoiceUi(val state: VoiceState = VoiceState.Off, val transcript: String = "", val reply: String = "", val level: Float = 0f,
                   val notice: String? = null, val timings: TurnTimings? = null, val asrReady: Boolean = false, val ttsReady: Boolean = false, val route: String? = null)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    val store = ConversationStore(app); val slang = SlangRepository(app)
    val settings = Settings(app); val models = ModelManager(app)
    val speech = SpeechModelStore(File(app.filesDir, "voice"))
    val voiceCatalog: VoiceCatalog = runCatching { VoiceCatalog.parse(app.assets.open("voices/voice_profiles.json").bufferedReader().use { it.readText() }) }.getOrDefault(VoiceCatalog(emptyList()))

    // ---- hybrid AI wiring: UI -> manager -> providers. Nothing below the manager is called by the UI directly.
    val registry = ModelRegistry(File(app.filesDir, "model_registry.json"))
    val secrets = SecretStore(app)
    val connectivity: ConnectivityMonitor = AndroidConnectivityMonitor(app)
    private val transport: HttpTransport = HttpUrlTransport()
    private val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val engine: InferenceEngine = LlamaEngine()
    val host = LocalModelHost(engine, { models.list() }, { m, n -> models.compat(m, n) }, registry, { settings.contextTokens },
        { (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6) }, { settings.modelFileName }, { settings.modelFileName = it })
    private val localEnglish = LocalProvider(ProviderKind.LOCAL_ENGLISH, host, { settings.contextTokens }, { settings.languageMode }, { settings.pidginHelp })
    private val localPidgin = LocalProvider(ProviderKind.LOCAL_PIDGIN, host, { settings.contextTokens }, { settings.languageMode }, { settings.pidginHelp })
    fun onlineConfig() = OnlineConfig(settings.onlineBaseUrl, settings.onlineModel, settings.onlineAuth, settings.onlineMaxTurns, settings.onlineMaxChars, debuggableBuild = debuggable)
    val onlineProvider = OnlineChatProvider({ onlineConfig() }, { secrets.get() }, transport, { settings.languageMode }, { settings.pidginHelp })
    val manager = AIProviderManager(listOf(localEnglish, localPidgin, onlineProvider), { connectivity.state.value },
        { AiPolicy(settings.aiMode, settings.preferredProvider, settings.onlineConsent, settings.responseLanguage) })
    val isDebuggableBuild: Boolean get() = debuggable
    private val downloader = ModelDownloader(models, transport)
    private val downloadCancel = AtomicBoolean(false)

    val engineState: StateFlow<EngineState> = host.state
    private val _gen = MutableStateFlow<GenState>(GenState.Idle); val genState = _gen.asStateFlow()
    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList()); val messages = _messages.asStateFlow()
    private val _lastRun = MutableStateFlow<LastRun?>(null); val lastRun = _lastRun.asStateFlow()
    private val _status = MutableStateFlow(ProviderStatus("Connecting\u2026", null, false)); val providerStatus = _status.asStateFlow()
    private val _download = MutableStateFlow<DownloadUi?>(null); val download = _download.asStateFlow()
    /** Why a reply bubble is empty (message id -> reason), so a failed reply is never a silent "...". */
    private val _failures = MutableStateFlow<Map<Long, String>>(emptyMap()); val failures = _failures.asStateFlow()
    private val _selfTest = MutableStateFlow<String?>(null); val selfTest = _selfTest.asStateFlow()
    private val _restoredDraft = MutableStateFlow<String?>(null); val restoredDraft = _restoredDraft.asStateFlow()
    private val _voice = MutableStateFlow(VoiceUi()); val voiceUi = _voice.asStateFlow()
    var conversationId: Long = -1; private set
    private var job: Job? = null

    init {
        store.purgeOlderThan(settings.retentionDays); newChat(); refreshModelState()
        viewModelScope.launch { connectivity.state.collect { manager.onNetworkChanged(); refreshStatus() } }
        viewModelScope.launch { host.state.collect { refreshStatus() } }
    }

    val generating: Boolean get() = _gen.value is GenState.Generating

    /** Recomputes "which provider would answer the next message". Off the main thread: it reads model file headers. */
    fun refreshStatus() { viewModelScope.launch(Dispatchers.Default) { _status.value = manager.status() } }
    fun consumeDraft() { _restoredDraft.value = null }

    /** Call after the user changes AI mode, consent, address, model or key, so an old failure never keeps online AI blocked. */
    fun onAiSettingsChanged() { manager.resetOnline(); refreshStatus() }

    // ------------------------------------------------------------------ model + conversation state

    fun refreshModelState() { host.refresh() }

    fun newChat() { stopAndDetach(); conversationId = store.newConversation(); _messages.value = emptyList(); _gen.value = GenState.Idle; _lastRun.value = null }
    fun openChat(id: Long) { stopAndDetach(); conversationId = id; _messages.value = store.messages(id).map { UiMessage(it.id, it.role, it.content, it.provider) }; _gen.value = GenState.Idle; _lastRun.value = null }
    fun deleteChat(id: Long) { if (id == conversationId) newChat(); store.delete(id) }
    fun clearAllChats() { stopAndDetach(); store.clearAll(); newChat() }
    fun dismissGenError() { if (_gen.value is GenState.Failed || _gen.value is GenState.Cancelled) _gen.value = GenState.Idle }

    fun loadModel(fileName: String? = settings.modelFileName) {
        viewModelScope.launch(Dispatchers.Default) { host.loadByName(fileName); refreshStatus() }
    }

    fun unloadModel() = viewModelScope.launch(Dispatchers.Default) {
        stopVoice(); manager.cancel(); host.unload(); job?.join(); refreshStatus()
    }

    fun deleteModel(info: ModelInfo) = viewModelScope.launch(Dispatchers.Default) {
        if (host.loadedFile == info.file.name) { stopVoice(); manager.cancel(); host.unload(); job?.join() }
        models.delete(info.file); registry.remove(info.file.name)
        if (settings.modelFileName == info.file.name) settings.modelFileName = null
        host.refresh(); refreshStatus()
    }

    /** A model that arrived by file picker gets a profile with honest provenance. It is English-role and Pidgin-UNTESTED by default. */
    fun registerImported(info: ModelInfo) {
        if (registry.get(info.file.name) == null) registry.upsert(host.defaultProfile(info).copy(provenance = "Imported by the user from device storage"))
        refreshStatus()
    }

    // ------------------------------------------------------------------ model profiles: Pidgin routing is opt-in and evidence-based

    fun profileOf(info: ModelInfo): ModelProfile = registry.get(info.file.name) ?: host.defaultProfile(info)

    /** Marks a model as Pidgin-capable. Requires the owner to state the basis: they evaluated it, or they configured it on purpose. Merely installing never does. */
    fun markPidginCapable(info: ModelInfo, evidence: Evidence, note: String) {
        require(evidence == Evidence.EVALUATED || evidence == Evidence.USER_CONFIGURED) { "Pidgin routing needs an evaluation or an explicit configuration." }
        registry.upsert(profileOf(info).copy(role = ModelRole.PIDGIN, markedAvailable = true, pidginEvidence = evidence,
            evalStatus = if (evidence == Evidence.EVALUATED) EvalStatus.EVALUATED else EvalStatus.NOT_EVALUATED, evalNote = note.trim().ifEmpty { null }))
        refreshStatus()
    }
    fun unmarkPidgin(info: ModelInfo) { registry.upsert(profileOf(info).copy(role = ModelRole.ENGLISH, markedAvailable = false, pidginEvidence = Evidence.UNTESTED)); refreshStatus() }

    /** One tap diagnostic on the Model screen: loads the model, generates a few words, and reports the real result or the real error. */
    fun runSelfTest() {
        if (generating) return
        viewModelScope.launch(Dispatchers.Default) { _selfTest.value = host.selfTest(settings.modelFileName) { _selfTest.value = it }; refreshStatus() }
    }

    // ------------------------------------------------------------------ download (explicit user action only)

    fun partialBytes(e: CatalogEntry): Long = downloader.partialBytes(e)
    fun discardPartial(e: CatalogEntry) { downloader.discardPartial(e); _download.value = null }
    fun cancelDownload() { downloadCancel.set(true) }

    fun downloadModel(e: CatalogEntry) {
        if (_download.value?.running == true) return
        downloadCancel.set(false); _download.value = DownloadUi(e.id, downloader.partialBytes(e), -1, true)
        viewModelScope.launch(Dispatchers.IO) {
            downloader.download(e, e.sha256, downloadCancel) { done, total -> _download.value = DownloadUi(e.id, done, total, true) }
                .onSuccess { r ->
                    host.refresh()
                    models.list().firstOrNull { it.file.name == r.file.name }?.let { info ->
                        registry.upsert(host.defaultProfile(info).copy(id = e.id, displayName = e.displayName, license = e.license, licenseSource = e.licenseSource,
                            provenance = "Downloaded from ${e.url} after the user confirmed", sha256 = r.sha256, sizeBytes = r.sizeBytes,
                            integrity = when (r.integrity) { IntegrityLevel.PINNED -> "SHA-256 matches the pinned value"; IntegrityLevel.SERVER_REPORTED -> "SHA-256 matches the value reported by the file host"; IntegrityLevel.NONE -> "No checksum available: verified by size and GGUF header only" }))
                        if (settings.modelFileName == null) settings.modelFileName = info.file.name
                    }
                    _download.value = DownloadUi(e.id, r.sizeBytes, r.sizeBytes, false, "Installed ${r.file.name}. It is not loaded yet: it loads on first use, or tap Load.", finished = true)
                    refreshStatus()
                }
                .onFailure { _download.value = DownloadUi(e.id, downloader.partialBytes(e), -1, false, it.message ?: "Download failed.") }
        }
    }

    // ------------------------------------------------------------------ one generation path shared by text and voice

    private fun label(k: ProviderKind) = when (k) { ProviderKind.ONLINE -> "Cloud AI"; ProviderKind.LOCAL_ENGLISH -> "Local English"; ProviderKind.LOCAL_PIDGIN -> "Local Pidgin" }
    private fun statusLabel(k: ProviderKind) = when (k) { ProviderKind.ONLINE -> "Online \u2014 Cloud AI"; ProviderKind.LOCAL_ENGLISH -> "Offline \u2014 Local English model"; ProviderKind.LOCAL_PIDGIN -> "Offline \u2014 Local Pidgin model" }

    /**
     * Blocking; call off the main thread. [text] = null means "answer the last user message again" (retry).
     * Checks that SOME provider can answer BEFORE saving anything, so an unusable setup never swallows a message: the draft is handed back.
     * Never fabricates a reply: [Result.failure] carries the real reason.
     */
    private fun runTurn(text: String?, voice: Boolean, onPiece: ((String) -> Unit)? = null, force: ProviderKind? = null): Result<String> {
        val retry = text == null; val t = text?.trim().orEmpty()
        if (!retry && t.isEmpty()) return Result.failure(IllegalArgumentException("Empty message."))
        val allowOnline = !voice || settings.onlineInVoice
        val prior = _messages.value
        val userText = if (retry) prior.lastOrNull { it.role == "user" }?.content.orEmpty() else t
        if (retry && (prior.lastOrNull()?.role != "assistant" || userText.isEmpty())) return Result.failure(IllegalStateException("Nothing to retry."))
        val decision = LanguageRouter.decide(userText, settings.responseLanguage, prior.map { ChatTurn(it.role, it.content) })
        val plan = manager.plan(decision, allowOnline, force)
        if (plan.order.isEmpty()) {
            val advice = FixAdvice.build(plan.skipped); _gen.value = GenState.Failed(advice); if (!retry && !voice) _restoredDraft.value = t
            return Result.failure(IllegalStateException(advice))
        }
        synchronized(this) { if (generating) return Result.failure(IllegalStateException("ViNT is already answering.")); _gen.value = GenState.Generating }
        val conv = conversationId; val aId: Long
        if (retry) {
            aId = prior.last().id; store.updateMessage(aId, "")
            _messages.value = prior.map { if (it.id == aId) it.copy(content = "", provider = null) else it }
        } else {
            val userId = store.addMessage(conv, "user", t)
            if (prior.isEmpty()) store.rename(conv, t.take(40))
            aId = store.addMessage(conv, "assistant", "")
            _messages.value = prior + UiMessage(userId, "user", t) + UiMessage(aId, "assistant", "")
        }
        val history = _messages.value.dropLast(1).map { ChatTurn(it.role, it.content) }          // shared by every provider: coherent across switches
        val req = AiRequest(UUID.randomUUID().toString(), history, settings.maxNewTokens, settings.temperature, decision, voice, allowOnline, force)
        val sb = StringBuilder(); var providerNow: ProviderKind? = null
        _failures.update { it - aId }
        fun noteFail(msg: String) { if (sb.isEmpty()) _failures.update { it + (aId to msg) } }
        fun emit(piece: String) { if (piece.isEmpty()) return; sb.append(piece)
            if (conversationId == conv) _messages.update { l -> l.map { if (it.id == aId) it.copy(content = sb.toString(), provider = providerNow?.name) else it } }
            onPiece?.invoke(piece) }
        try {
            val outcome = manager.submit(req, { emit(it) }, onProvider = { providerNow = it }, onSwitch = { })
            val result: Result<String> = when (outcome) {
                is AiOutcome.Completed -> {
                    val st = outcome.stats
                    val switchNote = outcome.switches.lastOrNull()?.let { "Answered offline because: ${it.because}" }
                    _lastRun.value = LastRun(statusLabel(outcome.provider), if (st.unit == "tokens") "%.1f tokens/s \u00b7 %d tokens".format(st.perSecond, st.units) else "%.1f s".format(st.millis / 1000.0), switchNote)
                    if (conversationId == conv) _gen.value = if (st.cancelled) GenState.Cancelled(st.units) else GenState.Idle
                    Result.success(sb.toString())
                }
                is AiOutcome.Failed -> {
                    noteFail(outcome.failure.message)
                    if (conversationId == conv) _gen.value = GenState.Failed(outcome.failure.message + if (outcome.partial) " The partial reply is kept. Tap Retry to answer again (it will not be sent twice by itself)." else "")
                    Result.failure(IllegalStateException(outcome.failure.message))
                }
                is AiOutcome.Cancelled -> { if (conversationId == conv) _gen.value = GenState.Cancelled(0); Result.success(sb.toString()) }
                is AiOutcome.Rejected -> { noteFail(outcome.advice); _gen.value = GenState.Failed(outcome.advice); Result.failure(IllegalStateException(outcome.advice)) }
                AiOutcome.Duplicate, AiOutcome.Busy -> { _gen.value = GenState.Failed("ViNT is already answering."); Result.failure(IllegalStateException("busy")) }
            }
            return result
        } catch (oom: OutOfMemoryError) {
            noteFail("Memory ran out"); host.unload(); _gen.value = GenState.Failed("Memory ran out. Reload with a smaller context.")
            return Result.failure(oom)
        } finally { store.updateMessage(aId, sb.toString(), providerNow?.name); refreshStatus() }
    }

    fun send(text: String) {
        if (text.isBlank() || generating) return
        job = viewModelScope.launch(Dispatchers.Default) { runTurn(text, voice = false) }
    }

    /** Answers the last user message again. [force] = LOCAL_ENGLISH retries offline after an online failure. Never automatic. */
    fun retry(force: ProviderKind? = null) {
        if (generating) return
        job = viewModelScope.launch(Dispatchers.Default) { runTurn(null, voice = false, force = force) }
    }

    /** Stop button: the native loop (or the online connection) ends, the partial reply is kept, and the state becomes Cancelled. */
    fun stop() { if (generating) manager.cancel() }
    private fun stopAndDetach() { if (generating) manager.cancel() }

    // ------------------------------------------------------------------ voice

    /** The LLM seen by the voice pipeline: the same loaded model and the same conversation as text chat. */
    private val voiceLlm = object : Llm {
        override fun reply(userText: String, onPiece: (String) -> Unit): Result<String> = runTurn(userText, voice = true, onPiece = onPiece)
        override fun cancel() { manager.cancel() }
    }

    private val voiceListener = object : VoiceController.Listener {
        override fun onState(state: VoiceState) = _voice.update { it.copy(state = state) }
        override fun onTranscript(text: String) = _voice.update { it.copy(transcript = text, reply = "") }
        override fun onReply(text: String, final: Boolean) = _voice.update { it.copy(reply = text) }
        override fun onLevel(level: Float) = _voice.update { it.copy(level = level) }
        override fun onTimings(t: TurnTimings) = _voice.update { it.copy(timings = t) }
        override fun onNotice(message: String?) = _voice.update { it.copy(notice = message) }
        override fun onSpeechReady(asr: Boolean, tts: Boolean) = _voice.update { it.copy(asrReady = asr, ttsReady = tts) }
    }
    private var controller: VoiceController? = null
    private fun controller(): VoiceController = controller ?: VoiceController(speech, voiceLlm, voiceListener).also { controller = it }   // main thread only

    fun selectedAsr(): AsrSpec? = speech.asrModels().let { l -> l.firstOrNull { it.id == settings.asrModelId } ?: l.firstOrNull() }
    fun selectedVoice(): VoiceProfile? = voiceCatalog.pick(settings.voiceProfileId, speech.installedTtsIds())

    /**
     * Voice needs some provider that may answer voice turns (the local model, or online AI if you allowed it for voice) and an installed ASR model.
     * Speech recognition and speech output always run on this phone. Only the transcribed TEXT could go online, and only if allowed; audio never does.
     */
    fun startVoice() {
        viewModelScope.launch(Dispatchers.Default) {
            val allowOnline = settings.onlineInVoice
            val can = manager.plan(LanguageDecision(ResponseLanguage.ENGLISH, true), allowOnline).order.isNotEmpty()
            val st = manager.status(allowOnline = allowOnline)
            withContext(Dispatchers.Main) {
                if (!can) { _voice.update { it.copy(notice = "No language model can answer yet. Load or download one on the Model screen" + if (!allowOnline) " (voice replies stay offline unless you allow online AI for voice in AI settings)." else ".") }; return@withContext }
                val asr = selectedAsr() ?: run { _voice.update { it.copy(notice = "No speech recognition model is installed. Import one in Voice \u2192 Speech models.") }; return@withContext }
                val profile = selectedVoice(); val tts = if (settings.speakReplies) profile?.let { p -> speech.ttsModels().firstOrNull { it.id == p.modelId } } else null
                val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
                _voice.update { it.copy(route = st.label + if (st.willSendOnline) ". Your transcribed words (not your audio) are sent online." else ". Recognition, replies and speech stay on this phone.") }
                controller().startVoiceMode(asr, tts, profile, threads)
            }
        }
    }
    fun stopVoice() { controller?.stopVoiceMode() }
    fun voicePress() { controller?.let { c -> if (c.state is VoiceState.Listening) c.finishListening() else c.pressTalk() } }
    fun voiceRelease(heldMs: Long) { controller?.releaseTalk(heldMs) }
    fun voiceInterrupt() { controller?.interrupt() }
    /** Called from MainActivity.onStop: leaving the app ends voice mode, which releases the microphone, the player and the speech models. */
    fun onAppBackgrounded() { controller?.stopVoiceMode() }

    fun importSpeechZip(uri: Uri, name: String): Result<String> = runCatching {
        getApplication<Application>().contentResolver.openInputStream(uri)!!.use { speech.importZip(it, name).getOrThrow() }
    }
    fun deleteSpeechModel(kind: String, id: String) { speech.delete(kind, id) }

    override fun onCleared() { controller?.shutdown(); connectivity.stop(); manager.cancel(); engine.unload() }
}
