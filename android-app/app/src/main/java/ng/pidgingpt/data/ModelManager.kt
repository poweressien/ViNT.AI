package ng.pidgingpt.data

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import ng.pidgingpt.net.ModelStorage

data class ModelInfo(val file: File, val sizeBytes: Long, val gguf: GgufInfo?, val headerError: String?, val sha256: String? = null)
data class Compat(val ok: Boolean, val warning: String?)

/** Models arrive by import (system file picker, no permission) or by an explicit, user-confirmed download (ng.pidgingpt.net.ModelDownloader). Files live in app-private storage. */
class ModelManager(private val ctx: Context) : ModelStorage {
    private val dir = File(ctx.filesDir, "models").apply { mkdirs() }
    override val modelDir: File get() = dir

    fun list(): List<ModelInfo> = dir.listFiles { f -> f.isFile && f.extension == "gguf" }.orEmpty().sortedBy { it.name }.map { describe(it) }

    private fun describe(f: File, sha: String? = null): ModelInfo = runCatching { f.inputStream().use { GgufReader.read(it) } }
        .fold({ ModelInfo(f, f.length(), it, null, sha) }, { ModelInfo(f, f.length(), null, it.message ?: "Unreadable file", sha) })

    /** Validates the GGUF header BEFORE copying anything, so a wrong file never fills storage. */
    fun import(uri: Uri, displayName: String, onProgress: (Long) -> Unit = {}): Result<ModelInfo> = runCatching {
        require(displayName.endsWith(".gguf", ignoreCase = true)) { "That is not a .gguf file. Pick a GGUF model." }
        ctx.contentResolver.openInputStream(uri)!!.use { GgufReader.read(it) }          // throws InvalidGgufException with a readable message
        val dest = File(dir, displayName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val md = MessageDigest.getInstance("SHA-256"); var total = 0L
        val size = ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (size > 0 && size > freeStorageBytes() - 64L * 1024 * 1024) error("Not enough free storage: need ${size shr 20} MB, have ${freeStorageBytes() shr 20} MB.")
        try {
            ctx.contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { o ->
                val buf = ByteArray(1 shl 20)
                while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); md.update(buf, 0, n); total += n; onProgress(total) } } }
        } catch (t: Throwable) { dest.delete(); throw t }
        describe(dest, md.digest().joinToString("") { "%02x".format(it) })
    }

    fun delete(f: File) = f.delete()
    fun totalModelBytes(): Long = dir.listFiles().orEmpty().sumOf { it.length() }
    override fun freeStorageBytes(): Long = ctx.filesDir.usableSpace

    /** Heuristic only: GGUF weights are mmapped, so we need roughly file size + KV cache + overhead. Warns, never blocks silently. */
    fun compat(m: ModelInfo, nCtx: Int): Compat {
        if (m.gguf == null) return Compat(false, m.headerError ?: "Not a valid GGUF file.")
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val kv = nCtx * 128L * 1024                                    // rough per-token KV estimate; real value depends on the model
        val need = m.sizeBytes + kv + 300L * 1024 * 1024
        return when {
            mi.lowMemory -> Compat(false, "Device memory is low. Close other apps first.")
            need > mi.availMem -> Compat(false, "Too big for free memory right now (needs about ${need shr 20} MB, free about ${mi.availMem shr 20} MB). Try a smaller model or a smaller context.")
            need > mi.availMem * 0.7 -> Compat(true, "Memory is tight. It may be slow or close the app.")
            else -> Compat(true, null)
        }
    }
}
