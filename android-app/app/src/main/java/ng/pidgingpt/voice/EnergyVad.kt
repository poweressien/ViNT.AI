package ng.pidgingpt.voice

import kotlin.math.sqrt

/**
 * Small energy-based voice-activity detector for end-of-speech detection in tap-to-talk mode (push-to-talk does not need it).
 * It is deliberately simple and does NOT understand speech: loud background noise can keep it open, quiet speakers can fall under the threshold.
 * The first [calibrationMs] of audio set the noise floor. Pure Kotlin, 16 kHz mono PCM16 frames of any size.
 */
class EnergyVad(
    private val sampleRate: Int = 16_000,
    private val frameMs: Int = 30,
    private val calibrationMs: Int = 300,
    private val endSilenceMs: Int = 900,
    private val minSpeechMs: Int = 250,
    private val maxUtteranceMs: Int = 30_000,
    private val startFactor: Float = 3.0f,
    private val minThreshold: Float = 0.012f,
) {
    enum class Event { None, SpeechStarted, SpeechEnded, MaxLengthReached }

    private val frameLen = sampleRate * frameMs / 1000
    private var buf = ShortArray(frameLen); private var fill = 0
    private var noiseFloor = 0f; private var calibFrames = 0; private val calibNeeded = calibrationMs / frameMs
    private var speaking = false; private var speechMs = 0; private var silenceMs = 0; private var totalMs = 0
    var level: Float = 0f; private set          // latest frame RMS (0..1), for a UI level meter
    val threshold: Float get() = maxOf(minThreshold, noiseFloor * startFactor)

    fun reset() { fill = 0; noiseFloor = 0f; calibFrames = 0; speaking = false; speechMs = 0; silenceMs = 0; totalMs = 0; level = 0f }

    /** Feed PCM16 samples. Returns the most significant event seen in this chunk (SpeechEnded / MaxLengthReached win). */
    fun accept(pcm: ShortArray, n: Int = pcm.size): Event {
        var result = Event.None; var i = 0
        while (i < n) {
            val take = minOf(frameLen - fill, n - i); System.arraycopy(pcm, i, buf, fill, take); fill += take; i += take
            if (fill == frameLen) { fill = 0; val e = frame(); if (e != Event.None && (result == Event.None || e != Event.SpeechStarted)) result = e }
        }
        return result
    }

    private fun frame(): Event {
        var sum = 0.0; for (s in buf) { val f = s / 32768.0; sum += f * f }
        val rms = sqrt(sum / frameLen).toFloat(); level = rms; totalMs += frameMs
        if (calibFrames < calibNeeded) { noiseFloor = (noiseFloor * calibFrames + rms) / (calibFrames + 1); calibFrames++; return Event.None }
        if (totalMs >= maxUtteranceMs) return Event.MaxLengthReached
        val loud = rms >= threshold
        if (loud) { speechMs += frameMs; silenceMs = 0 } else if (speaking) silenceMs += frameMs
        if (!speaking && loud && speechMs >= minSpeechMs) { speaking = true; return Event.SpeechStarted }
        if (!loud && !speaking) speechMs = 0                         // isolated clicks do not count as speech
        if (speaking && silenceMs >= endSilenceMs) { speaking = false; return Event.SpeechEnded }
        return Event.None
    }
}
