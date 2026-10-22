package ng.pidgingpt

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ng.pidgingpt.data.*
import ng.pidgingpt.inference.*
import ng.pidgingpt.voice.*
import java.io.File

data class UiMessage(val id: Long, val role: String, val content: String)

/** Everything the voice screen shows. Replies are never invented: [reply] only ever holds text produced by the loaded model. */
data class VoiceUi(val state: VoiceState = VoiceState.Off, val transcript: String = "", val reply: String = "", val level: Float = 0f,
                   val notice: String? = null, val timings: TurnTimings? = null, val asrReady: Boolean = false, val ttsReady: Boolean = false)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    val store = ConversationStore(app); val slang = SlangRepository(app)
    val settings = Settings(app); val models = ModelManager(app)
    val speech = SpeechModelStore(File(app.filesDir, "voice"))
    val voiceCatalog: VoiceCatalog = runCatching { VoiceCatalog.parse(app.assets.open("voices/voice_profiles.json").bufferedReader().use { it.readText() }) }.getOrDefault(VoiceCatalog(emptyList()))
    private val engine: InferenceEngine = LlamaEngine()

    private val _engine = MutableStateFlow<EngineState>(EngineState.NotInstalled); val engineState = _engine.asStateFlow()
    private val _gen = MutableStateFlow<GenState>(GenState.Idle); val genState = _gen.asStateFlow()
    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList()); val messages = _messages.asStateFlow()
    private val _stats = MutableStateFlow<GenStats?>(null); val lastStats = _stats.asStateFlow()
    private val _voice = MutableStateFlow(VoiceUi()); val voiceUi = _voice.asStateFlow()
    var conversationId: Long = -1; private set
    private var job: Job? = null
    @Volatile private var loadedArch: String? = null            // architecture read from the GGUF header of the loaded model

    init { store.purgeOlderThan(settings.retentionDays); newChat(); refreshModelState() }

    val generating: Boolean get() = _gen.value is GenState.Generating

    // ------------------------------------------------------------------ model + conversation state (unchanged behaviour from milestone 2)

    fun refreshModelState() {
        val s = _engine.value
        if (s is EngineState.Ready || s is EngineState.Loading) return
        _engine.value = if (models.list().isEmpty()) EngineState.NotInstalled else if (s is EngineState.Failed) s else EngineState.Unloaded
    }

    fun newChat() { stopAndDetach(); conversationId = store.newConversation(); _messages.value = emptyList(); _gen.value = GenState.Idle }
    fun openChat(id: Long) { stopAndDetach(); conversationId = id; _messages.value = store.messages(id).map { UiMessage(it.id, it.role, it.content) }; _gen.value = GenState.Idle }
    fun deleteChat(id: Long) { if (id == conversationId) newChat(); store.delete(id) }
    fun clearAllChats() { stopAndDetach(); store.clearAll(); newChat() }
    fun dismissGenError() { if (_gen.value is GenState.Failed || _gen.value is GenState.Cancelled) _gen.value = GenState.Idle }

    fun loadModel(fileName: String? = settings.modelFileName) {
        val info = models.list().let { l -> l.firstOrNull { it.file.name == fileName } ?: l.firstOrNull { it.gguf != null } }
        if (info == null) { _engine.value = EngineState.NotInstalled; return }
        val c = models.compat(info, settings.contextTokens)
        if (!c.ok) { _engine.value = EngineState.Failed(c.warning ?: "This model cannot be loaded."); return }
        settings.modelFileName = info.file.name
        _engine.value = EngineState.Loading
        viewModelScope.launch(Dispatchers.Default) {
            val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6)
            engine.load(info.file.path, settings.contextTokens, threads)
                .onSuccess { loadedArch = info.gguf?.architecture; _engine.value = EngineState.Ready(info.file.name, settings.contextTokens) }
                .onFailure { _engine.value = EngineState.Failed(it.message ?: "Model failed to load.") }
        }
    }

    fun unloadModel() = viewModelScope.launch(Dispatchers.Default) {
        stopVoice(); engine.unload(); job?.join(); _engine.value = EngineState.Unloaded; refreshModelState()
    }

    fun deleteModel(info: ModelInfo) = viewModelScope.launch(Dispatchers.Default) {
        if ((_engine.value as? EngineState.Ready)?.modelName == info.file.name) { stopVoice(); engine.unload(); job?.join() }
        models.delete(info.file); if (settings.modelFileName == info.file.name) settings.modelFileName = null
        _engine.value = EngineState.Unloaded; refreshModelState()
    }

    // ------------------------------------------------------------------ one generation path shared by text and voice

    /**
     * Blocking: persist the user turn, generate with the real model, persist the reply. Call off the main thread.
     * Returns the model's text, or a failure (no model loaded, busy, context overflow, native error). It never fabricates a reply.
     */
    private fun runTurn(text: String, voice: Boolean, onPiece: ((String) -> Unit)? = null): Result<String> {
        val t = text.trim()
        if (_engine.value !is EngineState.Ready) { val m = "No model is loaded yet. Open Model and load one first."; _gen.value = GenState.Failed(m); return Result.failure(IllegalStateException(m)) }
        if (t.isEmpty()) return Result.failure(IllegalArgumentException("Empty message."))
        synchronized(this) { if (generating) return Result.failure(IllegalStateException("ViNT is already answering.")); _gen.value = GenState.Generating }
        val conv = conversationId
        val userId = store.addMessage(conv, "user", t)
        if (_messages.value.isEmpty()) store.rename(conv, t.take(40))
        val aId = store.addMessage(conv, "assistant", "")
        _messages.value = _messages.value + UiMessage(userId, "user", t) + UiMessage(aId, "assistant", "")
        val history = _messages.value.dropLast(1).map { ChatTurn(it.role, it.content) }
        val turns = Prompts.window(Prompts.system(settings.languageMode, voice, noThink = loadedArch?.startsWith("qwen3") == true), history, settings.contextTokens, settings.maxNewTokens)
        val sb = StringBuilder(); val think = ThinkFilter()
        fun emit(piece: String) { if (piece.isEmpty()) return; sb.append(piece)
            if (conversationId == conv) _messages.update { l -> l.map { if (it.id == aId) it.copy(content = sb.toString()) else it } }
            onPiece?.invoke(piece) }
        try {
            val r = engine.generate(turns, settings.maxNewTokens, settings.temperature) { raw -> emit(think.push(raw)) }
            emit(think.flush())
            r.onSuccess { st -> _stats.value = st; if (conversationId == conv) _gen.value = if (st.cancelled) GenState.Cancelled(st.tokens) else GenState.Idle }
             .onFailure { if (conversationId == conv) _gen.value = GenState.Failed(it.message ?: "Generation failed.") }
            return r.map { sb.toString() }
        } catch (oom: OutOfMemoryError) {
            engine.unload(); _engine.value = EngineState.Failed("Memory ran out. Reload with a smaller context."); _gen.value = GenState.Failed("Out of memory.")
            return Result.failure(oom)
        } finally { store.updateMessage(aId, sb.toString()) }
    }

    fun send(text: String) {
        if (text.isBlank() || generating) return
        job = viewModelScope.launch(Dispatchers.Default) { runTurn(text, voice = false) }
    }

    /** Stop button: the native loop ends, the partial reply is kept, and the state becomes Cancelled. */
    fun stop() { if (generating) engine.cancel() }
    private fun stopAndDetach() { if (generating) engine.cancel() }

    // ------------------------------------------------------------------ voice

    /** The LLM seen by the voice pipeline: the same loaded model and the same conversation as text chat. */
    private val voiceLlm = object : Llm {
        override fun reply(userText: String, onPiece: (String) -> Unit): Result<String> = runTurn(userText, voice = true, onPiece = onPiece)
        override fun cancel() { engine.cancel() }
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

    /** Voice needs a READY language model and an installed ASR model. A voice (TTS) is optional: without one replies are shown, not spoken. */
    fun startVoice() {
        if (_engine.value !is EngineState.Ready) { _voice.update { it.copy(notice = "Load a language model first (Model screen). Voice mode needs it to reply.") }; return }
        val asr = selectedAsr() ?: run { _voice.update { it.copy(notice = "No speech recognition model is installed. Import one in Voice → Speech models.") }; return }
        val profile = selectedVoice(); val tts = profile?.let { p -> speech.ttsModels().firstOrNull { it.id == p.modelId } }
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        controller().startVoiceMode(asr, tts, profile, threads)
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

    override fun onCleared() { controller?.shutdown(); engine.unload() }
}
