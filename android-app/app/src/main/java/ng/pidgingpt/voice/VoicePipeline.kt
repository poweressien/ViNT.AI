package ng.pidgingpt.voice

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** The three local components + the audio output, as interfaces so the orchestration is testable with the REAL engines on a host JVM. */
interface Asr { fun transcribe(samples: FloatArray, sampleRate: Int): String; fun release() }
interface Tts {
    val sampleRate: Int
    /** Synthesises [text]; calls [onAudio] with each chunk; synthesis stops as soon as [onAudio] returns false. */
    fun speak(text: String, speakerId: Int, speed: Float, onAudio: (FloatArray) -> Boolean)
    fun release()
}
interface Llm {
    /** Streams the reply to [onPiece]; must return promptly after [cancel]. Returns the full reply text. */
    fun reply(userText: String, onPiece: (String) -> Unit): Result<String>
    fun cancel()
}
interface AudioSink {
    fun begin() {}                     // called at the start of every turn: re-arms a sink that was stopped by an earlier interrupt
    /** Queues audio for playback; returns false if playback was stopped. */
    fun write(samples: FloatArray, sampleRate: Int): Boolean
    fun stopNow()                      // drop queued audio immediately (interrupt)
    fun drain()                        // block until queued audio has been played (or stopNow was called)
}

data class TurnTimings(val asrMs: Long, val llmFirstPieceMs: Long, val firstAudioMs: Long, val llmTotalMs: Long, val totalMs: Long)

interface VoiceListener {
    fun onEvent(e: VoiceEvent)
    fun onTranscript(text: String)
    fun onReplyPartial(text: String)
    fun onFinished(replyText: String, timings: TurnTimings?, cancelled: Boolean)
}

/**
 * One voice turn: captured audio -> ASR -> LLM (streaming) -> TTS (sentence by sentence, overlapping with generation) -> audio sink.
 * [run] blocks on the calling worker thread. [interrupt] may be called from any thread and stops ASR output use, LLM, TTS and playback.
 * If [tts] is null the reply is returned as text only (no audio), which the UI states explicitly.
 */
class VoiceTurnRunner(private val asr: Asr, private val llm: Llm, private val tts: Tts?, private val sink: AudioSink, private val listener: VoiceListener) {
    private val cancelled = AtomicBoolean(false)

    fun interrupt() { cancelled.set(true); llm.cancel(); sink.stopNow() }

    fun run(audio: FloatArray, sampleRate: Int, speakerId: Int, speed: Float) {
        cancelled.set(false); sink.begin()
        val t0 = System.nanoTime(); fun ms(from: Long = t0) = (System.nanoTime() - from) / 1_000_000
        try {
            val text = asr.transcribe(audio, sampleRate).trim(); val asrMs = ms()
            if (cancelled.get()) { listener.onFinished("", null, true); return }
            if (text.isBlank()) { listener.onEvent(VoiceEvent.NothingHeard); listener.onFinished("", null, false); return }
            listener.onTranscript(text); listener.onEvent(VoiceEvent.TranscriptReady(text))

            val queue = LinkedBlockingQueue<String>(); val STOP = "\u0000STOP"
            var firstAudioMs = -1L; val speakingStarted = AtomicBoolean(false)
            val worker = tts?.let { t ->
                Thread({
                    while (!cancelled.get()) {
                        val s = queue.take(); if (s === STOP) break
                        t.speak(s, speakerId, speed) { chunk ->
                            if (cancelled.get()) false else {
                                if (speakingStarted.compareAndSet(false, true)) { firstAudioMs = ms(); listener.onEvent(VoiceEvent.FirstAudioQueued) }
                                sink.write(chunk, t.sampleRate) } }
                    }
                }, "vint-tts").also { it.start() }
            }
            val chunker = SpeechText.SentenceChunker()
            val llmStart = System.nanoTime(); var firstPiece = -1L; val acc = StringBuilder()
            val result = llm.reply(text) { piece ->
                if (firstPiece < 0) firstPiece = ms(llmStart)
                acc.append(piece); listener.onReplyPartial(acc.toString())
                if (worker != null && !cancelled.get()) chunker.push(piece).forEach { queue.put(it) }
            }
            val llmTotal = ms(llmStart)
            if (worker != null) { if (!cancelled.get()) chunker.flush().forEach { queue.put(it) }; queue.put(STOP); worker.join() }
            if (!cancelled.get()) sink.drain()
            result.onFailure { listener.onEvent(VoiceEvent.Failed(it.message ?: "Reply failed")); listener.onFinished(acc.toString(), null, cancelled.get()); return }
            if (cancelled.get()) { listener.onFinished(acc.toString(), null, true); return }
            listener.onEvent(VoiceEvent.ReplyFinished); if (speakingStarted.get()) listener.onEvent(VoiceEvent.SpeechFinished)
            listener.onFinished(acc.toString(), TurnTimings(asrMs, firstPiece, firstAudioMs, llmTotal, ms()), false)
        } catch (t: Throwable) {
            listener.onEvent(VoiceEvent.Failed(t.message ?: t.javaClass.simpleName)); listener.onFinished("", null, cancelled.get())
        }
    }
}
