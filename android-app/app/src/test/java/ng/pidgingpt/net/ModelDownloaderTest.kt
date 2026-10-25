package ng.pidgingpt.net

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ModelDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A minimal valid GGUF header (version 3, 0 tensors, 1 key: general.architecture = "llama") padded to look like a model body. */
    private fun gguf(extra: Int = 4000): ByteArray {
        val o = ByteArrayOutputStream()
        fun u32(v: Int) { for (i in 0..3) o.write((v shr (8 * i)) and 255) }
        fun u64(v: Long) { for (i in 0..7) o.write(((v shr (8 * i)) and 255).toInt()) }
        o.write("GGUF".toByteArray()); u32(3); u64(0); u64(1)
        val k = "general.architecture".toByteArray(); u64(k.size.toLong()); o.write(k); u32(8); val v = "llama".toByteArray(); u64(v.size.toLong()); o.write(v)
        o.write(ByteArray(extra) { (it % 251).toByte() }); return o.toByteArray()
    }
    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private val entry = CatalogEntry("t", "Test", "https://files.example.com/m.gguf", "m.gguf", "tiny", "Apache-2.0", "test", null, "")
    private fun storage(free: Long = Long.MAX_VALUE / 2) = object : ModelStorage { override val modelDir = tmp.root; override fun freeStorageBytes() = free }
    private fun ok(data: ByteArray, vararg h: Pair<String, String>) = HttpResponse(200, mapOf("content-length" to data.size.toString()) + h.toMap(), ByteArrayInputStream(data))

    @Test fun successfulDownloadIsVerifiedAndInstalledAtomically() {
        val d = gguf(); val t = FakeTransport { _, _, _, _ -> ok(d, "x-linked-etag" to "\"${sha(d)}\"") }
        val r = ModelDownloader(storage(), t).download(entry).getOrThrow()
        assertEquals(IntegrityLevel.SERVER_REPORTED, r.integrity); assertEquals(sha(d), r.sha256); assertTrue(File(tmp.root, "m.gguf").exists()); assertFalse(File(tmp.root, "m.gguf.part").exists()) }
    @Test fun pinnedChecksumIsPreferredAndMismatchDeletesTheFile() {
        val d = gguf(); val t = FakeTransport { _, _, _, _ -> ok(d) }
        assertEquals(IntegrityLevel.PINNED, ModelDownloader(storage(), t).download(entry, pinnedSha256 = sha(d)).getOrThrow().integrity)
        File(tmp.root, "m.gguf").delete()
        val bad = ModelDownloader(storage(), FakeTransport { _, _, _, _ -> ok(d) }).download(entry, pinnedSha256 = "0".repeat(64))
        assertTrue(bad.isFailure); assertTrue(bad.exceptionOrNull()!!.message!!.contains("Checksum")); assertFalse(File(tmp.root, "m.gguf").exists()); assertFalse(File(tmp.root, "m.gguf.part").exists()) }
    @Test fun corruptionDetectedAgainstServerReportedChecksum() {
        val d = gguf(); val corrupt = d.copyOf().also { it[100] = (it[100] + 1).toByte() }
        val r = ModelDownloader(storage(), FakeTransport { _, _, _, _ -> ok(corrupt, "x-linked-etag" to sha(d)) }).download(entry); assertTrue(r.isFailure); assertFalse(File(tmp.root, "m.gguf").exists()) }
    @Test fun notAGgufIsRejectedAndDeleted() {
        val junk = ByteArray(5000) { 7 }; val r = ModelDownloader(storage(), FakeTransport { _, _, _, _ -> ok(junk) }).download(entry)
        assertTrue(r.isFailure); assertTrue(r.exceptionOrNull()!!.message!!.contains("GGUF")); assertEquals(0, tmp.root.listFiles()!!.size) }
    @Test fun insufficientStorageStopsBeforeWritingAnything() {
        val d = gguf(); val r = ModelDownloader(storage(free = 1000), FakeTransport { _, _, _, _ -> ok(d) }).download(entry)
        assertTrue(r.isFailure); assertTrue(r.exceptionOrNull()!!.message!!.contains("storage")); assertFalse(File(tmp.root, "m.gguf.part").exists() && File(tmp.root, "m.gguf.part").length() > 0) }
    @Test fun interruptedDownloadKeepsPartialAndResumesWithRange() {
        val d = gguf(); val half = d.size / 2
        val cut = object : java.io.InputStream() { var i = 0; override fun read(): Int { if (i < half) return d[i++].toInt() and 255; throw java.io.IOException("reset") } }
        val first = ModelDownloader(storage(), FakeTransport { _, _, _, _ -> HttpResponse(200, mapOf("content-length" to d.size.toString()), cut) }).download(entry)
        assertTrue(first.isFailure); val part = File(tmp.root, "m.gguf.part"); assertTrue(part.length() in 1 until d.size.toLong()); assertFalse(File(tmp.root, "m.gguf").exists())
        val have = part.length().toInt(); var rangeSeen: String? = null
        val t2 = FakeTransport { _, _, h, _ -> rangeSeen = h["Range"]; HttpResponse(206, mapOf("content-range" to "bytes $have-${d.size - 1}/${d.size}"), ByteArrayInputStream(d.copyOfRange(have, d.size))) }
        val r = ModelDownloader(storage(), t2).download(entry, pinnedSha256 = sha(d)); assertTrue(r.isSuccess); assertEquals("bytes=$have-", rangeSeen); assertEquals(sha(d), r.getOrThrow().sha256) }
    @Test fun serverIgnoringRangeRestartsCleanly() {
        val d = gguf(); File(tmp.root, "m.gguf.part").writeBytes(ByteArray(300) { 9 })
        assertTrue(ModelDownloader(storage(), FakeTransport { _, _, _, _ -> ok(d) }).download(entry, pinnedSha256 = sha(d)).isSuccess) }
    @Test fun wrongResumeOffsetDiscardsThePartial() {
        val d = gguf(); File(tmp.root, "m.gguf.part").writeBytes(ByteArray(300) { 9 })
        val r = ModelDownloader(storage(), FakeTransport { _, _, _, _ -> HttpResponse(206, mapOf("content-range" to "bytes 0-${d.size - 1}/${d.size}"), ByteArrayInputStream(d)) }).download(entry)
        assertTrue(r.isFailure); assertFalse(File(tmp.root, "m.gguf.part").exists()) }
    @Test fun cancelKeepsThePartialSoItCanResume() {
        val d = gguf(60_000); val cancel = AtomicBoolean(false)
        val r = ModelDownloader(storage(), FakeTransport { _, _, _, _ -> ok(d) }).download(entry, cancel = cancel) { done, _ -> if (done > 1000) cancel.set(true) }
        assertTrue(r.exceptionOrNull() is DownloadCancelled); assertTrue(File(tmp.root, "m.gguf.part").exists()); assertFalse(File(tmp.root, "m.gguf").exists()) }
    @Test fun httpAddressesAndUnsafeNamesAreRefused() {
        val t = FakeTransport { _, _, _, _ -> error("no network") }
        assertTrue(ModelDownloader(storage(), t).download(entry.copy(url = "http" + "://x.example.com/m.gguf")).isFailure)
        assertTrue(ModelDownloader(storage(), t).download(entry.copy(fileName = "../evil.gguf")).isFailure); assertTrue(t.calls.isEmpty()) }
    @Test fun noDownloadStartsByItself() { val t = FakeTransport { _, _, _, _ -> error("no network") }; ModelDownloader(storage(), t); assertTrue(t.calls.isEmpty()) }
    @Test fun missingFileGivesAClearError() { val r = ModelDownloader(storage(), FakeTransport { _, _, _, _ -> resp(404, "") }).download(entry); assertTrue(r.exceptionOrNull()!!.message!!.contains("404")) }
}
