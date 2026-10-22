package ng.pidgingpt.voice

import com.k2fsa.sherpa.onnx.*

/** ASR via sherpa-onnx (the same C++ engine benchmarked in docs/voice-host-bench.json). Models are loaded from files, so no AssetManager is passed. */
class SherpaAsr private constructor(private val rec: OfflineRecognizer, val spec: AsrSpec) : Asr {
    override fun transcribe(samples: FloatArray, sampleRate: Int): String {
        val st = rec.createStream()
        try { st.acceptWaveform(samples, sampleRate); rec.decode(st); return rec.getResult(st).text.trim() } finally { st.release() }
    }
    override fun release() = rec.release()

    companion object {
        fun create(spec: AsrSpec, threads: Int): SherpaAsr {
            val f = spec.files
            val model = when (spec.family) {
                AsrFamily.WHISPER -> OfflineModelConfig(whisper = OfflineWhisperModelConfig(encoder = f.getValue("encoder"), decoder = f.getValue("decoder"), language = spec.language, task = "transcribe"),
                    tokens = f.getValue("tokens"), numThreads = threads, provider = "cpu")
                AsrFamily.MOONSHINE -> OfflineModelConfig(moonshine = OfflineMoonshineModelConfig(preprocessor = f.getValue("preprocessor"), encoder = f.getValue("encoder"),
                    uncachedDecoder = f.getValue("uncachedDecoder"), cachedDecoder = f.getValue("cachedDecoder")), tokens = f.getValue("tokens"), numThreads = threads, provider = "cpu")
            }
            return SherpaAsr(OfflineRecognizer(null, OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80), modelConfig = model, decodingMethod = "greedy_search")), spec)
        }
    }
}

/** TTS via sherpa-onnx (Piper/VITS or Kokoro). Streams audio chunks through a callback that can stop synthesis (used for interrupt). */
class SherpaTts private constructor(private val tts: OfflineTts, val spec: TtsSpec) : Tts {
    override val sampleRate: Int get() = tts.sampleRate()
    val numSpeakers: Int get() = tts.numSpeakers()
    override fun speak(text: String, speakerId: Int, speed: Float, onAudio: (FloatArray) -> Boolean) {
        // MUST be an explicit Function1 object, not a Kotlin lambda: sherpa's JNI looks up the typed method invoke([F)Ljava/lang/Integer;,
        // which Kotlin 2.x invokedynamic lambdas do not have (found by host verification: NoSuchMethodError).
        val cb = object : Function1<FloatArray, Int> { override fun invoke(samples: FloatArray): Int = if (onAudio(samples)) 1 else 0 }
        tts.generateWithCallback(text, speakerId.coerceIn(0, (numSpeakers - 1).coerceAtLeast(0)), speed, cb)
    }
    override fun release() = tts.free()

    companion object {
        fun create(spec: TtsSpec, threads: Int): SherpaTts {
            val f = spec.files
            val model = when (spec.family) {
                TtsFamily.KOKORO -> OfflineTtsModelConfig(kokoro = OfflineTtsKokoroModelConfig(model = f.getValue("model"), voices = f.getValue("voices"), tokens = f.getValue("tokens"), dataDir = f.getValue("dataDir")), numThreads = threads, provider = "cpu")
                TtsFamily.PIPER_VITS -> OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(model = f.getValue("model"), tokens = f.getValue("tokens"), dataDir = f.getValue("dataDir")), numThreads = threads, provider = "cpu")
            }
            return SherpaTts(OfflineTts(null, OfflineTtsConfig(model = model, maxNumSentences = 1)), spec)
        }
    }
}
