package ng.pidgingpt.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Streams synthesised speech through an AudioTrack. stopNow() silences output immediately (interrupt); release() frees the audio device. */
class AudioPlayer : AudioSink {
    private var track: AudioTrack? = null
    private var rate = 0
    @Volatile private var stopped = false
    @Volatile private var framesWritten = 0L

    @Synchronized override fun begin() { stopped = false; framesWritten = 0 }

    @Synchronized private fun ensure(sampleRate: Int): AudioTrack {
        track?.let { if (rate == sampleRate) return it; it.release() }
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, sampleRate * 4)).setTransferMode(AudioTrack.MODE_STREAM).build()
        t.play(); track = t; rate = sampleRate; framesWritten = 0; return t
    }

    override fun write(samples: FloatArray, sampleRate: Int): Boolean {
        if (stopped) return false
        val t = ensure(sampleRate); var off = 0
        while (off < samples.size && !stopped) {
            val n = t.write(samples, off, samples.size - off, AudioTrack.WRITE_BLOCKING)
            if (n < 0) return false; off += n; framesWritten += n
        }
        return !stopped
    }

    @Synchronized override fun stopNow() { stopped = true; track?.let { try { it.pause(); it.flush() } catch (_: IllegalStateException) {} } }

    /** Waits (bounded) until everything written has been played. Returns early if stopNow() is called. */
    override fun drain() {
        val t = track ?: return
        val deadline = System.currentTimeMillis() + 20_000
        while (!stopped && t.playbackHeadPosition < framesWritten && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    @Synchronized fun release() { stopped = true; track?.let { try { it.stop() } catch (_: IllegalStateException) {}; it.release() }; track = null }
}
