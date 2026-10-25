package ng.pidgingpt.net

import ng.pidgingpt.data.GgufReader
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Where models live and how much room there is. Implemented by ModelManager; faked in unit tests. */
interface ModelStorage { val modelDir: File; fun freeStorageBytes(): Long }

/**
 * An entry the user can download. [sha256] is a PINNED checksum; when it is null the download is still checked against the size and the
 * checksum the file host reports (Hugging Face sends the LFS SHA-256 in x-linked-etag), then validated as a GGUF. The pinned value is filled in
 * only from a source we actually read: it is never guessed.
 */
data class CatalogEntry(
    val id: String, val displayName: String, val url: String, val fileName: String, val sizeText: String,
    val license: String, val licenseSource: String, val sha256: String?, val notes: String,
)

object ModelCatalog {
    val QWEN25_1_5B = CatalogEntry(
        id = "qwen2.5-1.5b-instruct-q4_k_m", displayName = "Qwen2.5-1.5B-Instruct (Q4_K_M)",
        url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf", sizeText = "about 1 GB (the exact size is shown when the download starts)",
        license = "Apache-2.0", licenseSource = "Qwen model card on Hugging Face (read it before redistributing)", sha256 = null,
        notes = "General English assistant. Not Pidgin-trained: its Pidgin quality is unverified until tested.")
    val all = listOf(QWEN25_1_5B)
}

enum class IntegrityLevel { PINNED, SERVER_REPORTED, NONE }

data class DownloadResult(val file: File, val sha256: String, val integrity: IntegrityLevel, val sizeBytes: Long)

class DownloadCancelled : Exception("Download cancelled. The partial file is kept so it can resume.")

/**
 * Consent-based, resumable, verified model download. The caller must have shown the user the address, size and licence and received a tap on Download:
 * this class never starts by itself. Work happens on the calling thread: call off the main thread.
 * Layout: <name>.gguf.part grows while downloading (resumable with a Range request), then is verified and atomically renamed to <name>.gguf,
 * so a half-downloaded file can never be listed or loaded as a model.
 */
class ModelDownloader(private val storage: ModelStorage, private val http: HttpTransport) {
    fun partFile(e: CatalogEntry) = File(storage.modelDir, e.fileName + ".part")
    fun partialBytes(e: CatalogEntry): Long = partFile(e).takeIf { it.exists() }?.length() ?: 0L
    fun discardPartial(e: CatalogEntry) { partFile(e).delete() }

    fun download(e: CatalogEntry, pinnedSha256: String? = e.sha256, cancel: AtomicBoolean = AtomicBoolean(false), onProgress: (Long, Long) -> Unit = { _, _ -> }): Result<DownloadResult> = runCatching {
        require(e.url.startsWith("https://")) { "Only https:// downloads are allowed." }
        require(e.fileName.matches(Regex("[A-Za-z0-9._-]+\\.gguf")) && !e.fileName.contains("..")) { "Unsafe file name." }
        storage.modelDir.mkdirs()
        val part = partFile(e); var have = if (part.exists()) part.length() else 0L
        val headers = HashMap<String, String>().apply { put("Accept", "application/octet-stream"); if (have > 0) put("Range", "bytes=$have-") }
        var total = -1L; var serverSha: String? = null
        val resp = try { http.get(e.url, headers, 15_000, 30_000) } catch (t: TransportException) { error("Could not reach the download server (${t.message}). Check your connection and try again.") }
        resp.use { r ->
            when (r.status) {
                200 -> { have = 0L; total = r.headers["content-length"]?.toLongOrNull() ?: -1L; part.delete() }
                206 -> {
                    val cr = r.headers["content-range"].orEmpty()                      // "bytes 100-999/1000"
                    val start = Regex("bytes (\\d+)-").find(cr)?.groupValues?.get(1)?.toLongOrNull()
                    if (start != have) { part.delete(); error("The server resumed from the wrong position. The partial file was discarded: tap Download again.") }
                    total = Regex("/(\\d+)$").find(cr)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                }
                416 -> { part.delete(); error("The partial file no longer matches the server and was discarded. Tap Download again.") }
                404 -> error("The file was not found at the address (HTTP 404). The model may have moved.")
                else -> error("The download server answered HTTP ${r.status}.")
            }
            serverSha = (r.headers["x-linked-etag"] ?: "").trim().trim('"').lowercase().takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            val linkedSize = r.headers["x-linked-size"]?.toLongOrNull()
            if (total <= 0 && linkedSize != null) total = linkedSize
            if (total > 0) {
                val need = total - have + 64L * 1024 * 1024
                check(need <= storage.freeStorageBytes()) { "Not enough free storage: need about ${need shr 20} MB, have ${storage.freeStorageBytes() shr 20} MB." }
            }
            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(have); if (have == 0L) raf.setLength(0)
                val buf = ByteArray(1 shl 18); var done = have
                while (true) {
                    if (cancel.get()) throw DownloadCancelled()
                    val n = try { r.body.read(buf) } catch (t: java.io.IOException) { error("The connection was interrupted after ${done shr 20} MB. The partial file is kept: tap Download to resume.") }
                    if (n < 0) break
                    raf.write(buf, 0, n); done += n; onProgress(done, total)
                }
            }
        }
        if (total > 0 && part.length() != total) error("The download stopped early (${part.length() shr 20} of ${total shr 20} MB). The partial file is kept: tap Download to resume.")
        val actual = sha256Of(part)
        val expected = pinnedSha256?.lowercase() ?: serverSha
        val level = when { pinnedSha256 != null -> IntegrityLevel.PINNED; serverSha != null -> IntegrityLevel.SERVER_REPORTED; else -> IntegrityLevel.NONE }
        if (expected != null && expected != actual) { part.delete(); error("Checksum mismatch: the file is corrupt or not the expected model. It was deleted. Try again.") }
        runCatching { part.inputStream().use { GgufReader.read(it) } }.onFailure { part.delete(); error("The downloaded file is not a valid GGUF model (${it.message}). It was deleted.") }
        val dest = File(storage.modelDir, e.fileName)
        if (dest.exists()) dest.delete()
        check(part.renameTo(dest)) { "Could not finish installing the model file." }
        DownloadResult(dest, actual, level, dest.length())
    }

    companion object {
        fun sha256Of(f: File): String {
            val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(1 shl 20)
            f.inputStream().use { i -> while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
