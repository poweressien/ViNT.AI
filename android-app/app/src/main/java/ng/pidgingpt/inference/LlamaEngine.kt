package ng.pidgingpt.inference

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * JNI bridge to llama.cpp (src/main/cpp/pidgin_jni.cpp). Host-verified with kotlinc + JVM (tools/host-verify); not yet run on Android.
 * Thread-safety: generate() holds the READ lock, load()/unload() hold the WRITE lock, so the native model can never be freed
 * while a generation is running. cancel() never blocks: if a writer holds the lock, nothing is generating.
 */
class LlamaEngine : InferenceEngine {
    companion object { init { System.loadLibrary("pidgin_jni") } }

    fun interface TokenCallback { fun onBytes(piece: ByteArray) }

    @Volatile private var handle: Long = 0L
    private val lock = ReentrantReadWriteLock()
    private val cancelRequested = AtomicBoolean(false)

    private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeApplyTemplate(h: Long, roles: Array<String>, contents: Array<ByteArray>): ByteArray?
    private external fun nativeGenerate(h: Long, prompt: ByteArray, maxNew: Int, temp: Float, cb: TokenCallback): Int
    private external fun nativeCancel(h: Long)
    private external fun nativeFree(h: Long)

    override fun load(modelPath: String, nCtx: Int, nThreads: Int): Result<Unit> {
        signalCancel()
        return lock.write {
            runCatching {
                freeLocked()
                handle = nativeLoad(modelPath, nCtx, nThreads)
                check(handle != 0L) { "ViNT no fit load this model (corrupt file, unsupported architecture, or not enough memory)." }
            }
        }
    }

    override fun generate(turns: List<ChatTurn>, maxNewTokens: Int, temperature: Float, onToken: (String) -> Unit): Result<GenStats> = lock.read {
        runCatching {
            val h = handle; check(h != 0L) { "No model is loaded." }
            cancelRequested.set(false)
            val prompt = nativeApplyTemplate(h, turns.map { it.role }.toTypedArray(), turns.map { it.content.toByteArray(Charsets.UTF_8) }.toTypedArray())
                ?: error("The model's chat template could not be applied.")
            val t0 = System.currentTimeMillis()
            val n = nativeGenerate(h, prompt, maxNewTokens, temperature) { onToken(String(it, Charsets.UTF_8)) }
            when (n) {
                -1 -> error("Decoding failed inside the native runtime.")
                -2 -> error("Conversation is too long for the context window. Start a new chat or raise the context size.")
                -3 -> error("Prompt could not be tokenized.")
                -4 -> error("Token callback failed.")
                -5 -> error("No model is loaded.")
            }
            GenStats(n, System.currentTimeMillis() - t0, cancelled = cancelRequested.get())
        }
    }

    override fun cancel() = signalCancel()

    private fun signalCancel() {
        cancelRequested.set(true)
        if (lock.readLock().tryLock()) {            // barging tryLock: fails only while a writer (load/unload) holds the lock
            try { val h = handle; if (h != 0L) nativeCancel(h) } finally { lock.readLock().unlock() }
        }
    }

    override fun unload() { signalCancel(); lock.write { freeLocked() } }

    private fun freeLocked() { if (handle != 0L) { nativeFree(handle); handle = 0L } }
}
