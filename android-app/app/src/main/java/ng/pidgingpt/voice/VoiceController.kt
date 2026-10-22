package ng.pidgingpt.voice

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Owns the voice session: microphone, VAD, ASR, TTS, playback and the turn state machine. It uses plain threads (no coroutines) and
 * reports to [Listener] on the main thread. The LLM is injected ([Llm]) so voice and text chat share one model and one conversation.
 *
 * Lifecycle rules enforced here (host-tested pieces: VoiceTurnMachine, EnergyVad, VoiceTurnRunner):
 *  - the microphone is opened only while state == Listening and is released the moment capture ends;
 *  - stopVoiceMode() (also called when the app goes to the background) interrupts any turn, releases mic, player and speech models;
 *  - no audio is saved anywhere.
 */
class VoiceController(private val store: SpeechModelStore, private val llm: Llm, private val listener: Listener) {
    interface Listener {
        fun onState(state: VoiceState); fun onTranscript(text: String); fun onReply(text: String, final: Boolean)
        fun onLevel(level: Float); fun onTimings(t: TurnTimings); fun onNotice(message: String?); fun onSpeechReady(asr: Boolean, tts: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private val machine = VoiceTurnMachine()
    private val capture = AudioCapture()
    private val player = AudioPlayer()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "vint-voice") }
    private val loader = Executors.newSingleThreadExecutor { Thread(it, "vint-voice-load") }
    private val lock = Any()

    private var asr: Asr? = null; private var tts: Tts? = null; private var profile: VoiceProfile? = null
    private var runner: VoiceTurnRunner? = null
    private var pcm = ShortArray(16_000 * 32); private var pcmLen = 0
    private var vad: EnergyVad? = null; private var hold = true
    val state: VoiceState get() = synchronized(lock) { machine.state }

    private fun dispatch(e: VoiceEvent) { val s = synchronized(lock) { machine.on(e) }; main.post { listener.onState(s) } }

    /** Loads the speech models on a background thread, then enters Idle. [ttsProfile]==null means replies are shown but not spoken. */
    fun startVoiceMode(asrSpec: AsrSpec, ttsSpec: TtsSpec?, ttsProfile: VoiceProfile?, threads: Int) {
        if (synchronized(lock) { machine.state !is VoiceState.Off && machine.state !is VoiceState.Error }) return
        main.post { listener.onNotice("Loading speech models…") }
        loader.execute {
            release(detachEngines())                                // restart after an error: never leak the previous engines
            var a: SherpaAsr? = null; var t: SherpaTts? = null
            try {
                a = SherpaAsr.create(asrSpec, threads); t = ttsSpec?.let { SherpaTts.create(it, threads) }
                synchronized(lock) { asr = a; tts = t; profile = ttsProfile; runner = VoiceTurnRunner(a, llm, t, player, turnListener) }
                dispatch(VoiceEvent.StartVoiceMode); main.post { listener.onNotice(if (t == null) "No voice installed: replies will be shown, not spoken." else null); listener.onSpeechReady(true, t != null) }
            } catch (e: Throwable) { release(a to t); detachEngines(); dispatch(VoiceEvent.Failed("Speech models failed to load: ${e.message ?: e.javaClass.simpleName}")); main.post { listener.onSpeechReady(false, false) } }
        }
    }

    /** Hold-to-talk or tap-to-talk. Ignored unless Idle (no overlapping turns): interrupt first. */
    fun pressTalk() {
        synchronized(lock) { if (machine.state !is VoiceState.Idle) return; pcmLen = 0; vad = null; hold = true; machine.on(VoiceEvent.TalkPressed) }
        main.post { listener.onState(VoiceState.Listening) }
        val ok = capture.start(object : AudioCapture.Callback {
            override fun onPcm(buf: ShortArray, n: Int) { onAudio(buf, n) }
            override fun onError(message: String) { main.post { listener.onNotice(message) } }
        })
        if (!ok) { capture.stop(); dispatch(VoiceEvent.NothingHeard) }
    }

    /** Called on release. A press longer than [holdMs] is push-to-talk (sends now); a short tap switches to hands-free until silence is heard. */
    fun releaseTalk(heldMs: Long) {
        if (state !is VoiceState.Listening) return
        if (heldMs >= 350) endCapture()
        else synchronized(lock) { hold = false; vad = EnergyVad().also { v -> v.accept(pcm, pcmLen) } }
    }

    /** Tap again while hands-free listening to finish early. */
    fun finishListening() { if (state is VoiceState.Listening) endCapture() }

    private fun onAudio(buf: ShortArray, n: Int) {
        var endNow = false
        synchronized(lock) {
            if (machine.state !is VoiceState.Listening) return
            if (pcmLen + n > pcm.size) endNow = true else { System.arraycopy(buf, 0, pcm, pcmLen, n); pcmLen += n }
            val v = if (hold) null else (vad ?: EnergyVad().also { vad = it })
            val ev = v?.accept(buf, n) ?: EnergyVad.Event.None
            val level = v?.level ?: rms(buf, n)
            main.post { listener.onLevel(level) }
            if (ev == EnergyVad.Event.SpeechEnded || ev == EnergyVad.Event.MaxLengthReached) endNow = true
        }
        if (endNow) endCapture()
    }

    private fun rms(b: ShortArray, n: Int): Float { var s = 0.0; for (i in 0 until n) { val f = b[i] / 32768.0; s += f * f }; return Math.sqrt(s / maxOf(n, 1)).toFloat() }

    private fun endCapture() {
        val samples: ShortArray
        synchronized(lock) { if (machine.state !is VoiceState.Listening) return; samples = pcm.copyOf(pcmLen); pcmLen = 0; vad = null }
        capture.stop()                                              // microphone released BEFORE any processing starts
        main.post { listener.onLevel(0f) }
        if (samples.size < 16_000 * 3 / 10) { dispatch(VoiceEvent.NothingHeard); return }   // < 0.3 s: accidental tap
        dispatch(VoiceEvent.TalkEnded)
        val floats = FloatArray(samples.size) { samples[it] / 32768f }
        val r = runner ?: return; val p = profile
        worker.execute { r.run(floats, 16_000, p?.speakerId ?: 0, p?.speed ?: 1f) }
    }

    /** Stop button: ends listening, generation and speech immediately and returns to Idle without recording. */
    fun interrupt() {
        capture.stop(); runner?.interrupt(); synchronized(lock) { pcmLen = 0; vad = null }
        dispatch(VoiceEvent.Interrupt); main.post { listener.onLevel(0f) }
    }

    /** Leaves voice mode: nothing records, nothing speaks, speech models are freed. Also called when the app goes to the background. */
    fun stopVoiceMode() {
        capture.stop(); runner?.interrupt(); player.stopNow()
        dispatch(VoiceEvent.StopVoiceMode); main.post { listener.onLevel(0f); listener.onNotice(null); listener.onSpeechReady(false, false) }
        val old = detachEngines()                                   // fields cleared NOW, so a quick restart can never have its new engines freed
        worker.execute { release(old) }                             // runs after any in-flight turn, so models are never freed mid-use
    }

    fun shutdown() { stopVoiceMode(); worker.shutdown(); loader.shutdown(); worker.awaitTermination(3, TimeUnit.SECONDS); player.release() }

    private fun detachEngines(): Pair<Asr?, Tts?> = synchronized(lock) { val old = asr to tts; asr = null; tts = null; runner = null; old }
    private fun release(e: Pair<Asr?, Tts?>) { try { e.first?.release() } catch (_: Throwable) {}; try { e.second?.release() } catch (_: Throwable) {} }

    private val turnListener = object : VoiceListener {
        override fun onEvent(e: VoiceEvent) { dispatch(e) }
        override fun onTranscript(text: String) { main.post { listener.onTranscript(text) } }
        override fun onReplyPartial(text: String) { main.post { listener.onReply(text, false) } }
        override fun onFinished(replyText: String, timings: TurnTimings?, cancelled: Boolean) {
            main.post { listener.onReply(replyText, true); timings?.let { listener.onTimings(it) } }
            val s = synchronized(lock) { machine.state }
            if (s is VoiceState.Thinking || s is VoiceState.Speaking || s is VoiceState.Transcribing) dispatch(VoiceEvent.Interrupt)     // never leave a turn stuck busy
        }
    }
}
