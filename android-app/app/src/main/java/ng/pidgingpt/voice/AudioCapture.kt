package ng.pidgingpt.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Microphone capture: 16 kHz mono PCM16 on a dedicated thread. The AudioRecord exists ONLY between start() and stop():
 * stop() joins the thread and releases the microphone, so nothing records while voice mode is idle, off, or the app is in the background.
 * Audio is delivered to the callback in memory and is never written to disk.
 */
class AudioCapture(private val sampleRate: Int = 16_000) {
    interface Callback { fun onPcm(buf: ShortArray, n: Int); fun onError(message: String) }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    val isRecording: Boolean get() = running

    /** The caller must already hold RECORD_AUDIO. Returns false (and reports via [cb]) if the microphone cannot be opened. */
    @SuppressLint("MissingPermission")
    @Synchronized fun start(cb: Callback): Boolean {
        if (running) return true
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { cb.onError("This phone cannot record 16 kHz audio."); return false }
        val rec = try { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2) }
                  catch (e: SecurityException) { cb.onError("Microphone permission is missing."); return false }
                  catch (e: IllegalArgumentException) { cb.onError("Microphone could not be configured."); return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); cb.onError("Microphone is busy or unavailable. Close other apps that use it."); return false }
        try { rec.startRecording() } catch (e: IllegalStateException) { rec.release(); cb.onError("Microphone could not start."); return false }
        record = rec; running = true
        thread = Thread({
            val buf = ShortArray(min / 2 + 480)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) cb.onPcm(buf, n) else if (n < 0) { cb.onError("Microphone read failed ($n)."); break }
            }
        }, "vint-mic").also { it.start() }
        return true
    }

    /** Stops recording and releases the microphone before returning. Safe to call repeatedly. */
    @Synchronized fun stop() {
        running = false
        thread?.let { if (it !== Thread.currentThread()) it.join(1500) }; thread = null
        record?.let { try { it.stop() } catch (_: IllegalStateException) {}; it.release() }; record = null
    }
}
