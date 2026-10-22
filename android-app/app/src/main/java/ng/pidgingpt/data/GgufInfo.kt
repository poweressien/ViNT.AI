package ng.pidgingpt.data

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream

/** Metadata read from a GGUF header. Pure JVM (no Android classes) so it is unit-testable on a host. */
data class GgufInfo(
    val version: Int, val tensorCount: Long, val kvCount: Long,
    val architecture: String?, val name: String?, val license: String?, val quantization: String?,
    val contextLength: Long?, val embeddingLength: Long?, val blockCount: Long?, val hasChatTemplate: Boolean,
)

class InvalidGgufException(message: String) : Exception(message)

object GgufReader {
    private val FILE_TYPES = mapOf(0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 7 to "Q8_0", 8 to "Q5_0", 9 to "Q5_1",
        10 to "Q2_K", 11 to "Q3_K_S", 12 to "Q3_K_M", 13 to "Q3_K_L", 14 to "Q4_K_S", 15 to "Q4_K_M", 16 to "Q5_K_S",
        17 to "Q5_K_M", 18 to "Q6_K", 24 to "IQ4_NL", 30 to "BF16")
    private const val MAX_KV = 200_000L
    private const val MAX_KEEP_STRING = 16_384L

    private class LE(private val inp: InputStream) {
        fun u8(): Int { val b = inp.read(); if (b < 0) throw EOFException(); return b }
        fun u16() = u8() or (u8() shl 8)
        fun u32(): Long = (u16().toLong()) or (u16().toLong() shl 16)
        fun u64(): Long = u32() or (u32() shl 32)
        fun skip(n: Long) { var left = n; while (left > 0) { val s = inp.skip(left); if (s <= 0) { if (inp.read() < 0) throw EOFException(); left-- } else left -= s } }
        fun bytes(n: Int): ByteArray { val b = ByteArray(n); var o = 0; while (o < n) { val r = inp.read(b, o, n - o); if (r < 0) throw EOFException(); o += r }; return b }
    }

    private fun fixedSize(type: Int): Int? = when (type) { 0, 1, 7 -> 1; 2, 3 -> 2; 4, 5, 6 -> 4; 10, 11, 12 -> 8; else -> null }

    private fun readScalar(r: LE, type: Int): Any? = when (type) {
        0, 1, 7 -> r.u8().toLong(); 2, 3 -> r.u16().toLong(); 4, 5 -> r.u32(); 6 -> { r.u32(); null }
        10, 11 -> r.u64(); 12 -> { r.u64(); null }
        8 -> { val len = r.u64(); if (len in 0..MAX_KEEP_STRING) String(r.bytes(len.toInt()), Charsets.UTF_8) else { r.skip(len); null } }
        else -> throw InvalidGgufException("Unknown GGUF value type $type")
    }

    private fun skipArray(r: LE) {
        val elemType = r.u32().toInt(); val count = r.u64()
        if (count < 0 || count > 1L shl 31) throw InvalidGgufException("Implausible array length")
        val fs = fixedSize(elemType)
        if (fs != null) { r.skip(count * fs); return }
        if (elemType == 8) { repeat(count.toInt()) { r.skip(r.u64()) }; return }
        throw InvalidGgufException("Unsupported array element type $elemType")
    }

    /** Reads only the header + key/value metadata. Throws [InvalidGgufException] for anything that is not a sane GGUF file. */
    fun read(input: InputStream): GgufInfo {
        val r = LE(BufferedInputStream(input, 1 shl 16))
        try {
            val magic = r.bytes(4)
            if (String(magic, Charsets.ISO_8859_1) != "GGUF") throw InvalidGgufException("Not a GGUF file (missing GGUF header).")
            val version = r.u32().toInt()
            if (version < 2 || version > 3) throw InvalidGgufException("Unsupported GGUF version $version.")
            val tensors = r.u64(); val kvs = r.u64()
            if (kvs < 0 || kvs > MAX_KV || tensors < 0 || tensors > 1_000_000) throw InvalidGgufException("Corrupt GGUF header.")
            val meta = HashMap<String, Any>()
            repeat(kvs.toInt()) {
                val klen = r.u64(); if (klen < 0 || klen > 1024) throw InvalidGgufException("Corrupt key length.")
                val key = String(r.bytes(klen.toInt()), Charsets.UTF_8)
                val type = r.u32().toInt()
                if (type == 9) skipArray(r) else readScalar(r, type)?.let { meta[key] = it }
            }
            val arch = meta["general.architecture"] as? String
            fun num(k: String) = (meta[k] as? Long)
            return GgufInfo(version, tensors, kvs, arch, meta["general.name"] as? String, meta["general.license"] as? String,
                (meta["general.file_type"] as? Long)?.let { FILE_TYPES[it.toInt()] ?: "type $it" },
                arch?.let { num("$it.context_length") }, arch?.let { num("$it.embedding_length") }, arch?.let { num("$it.block_count") },
                meta.containsKey("tokenizer.chat_template"))
        } catch (e: EOFException) { throw InvalidGgufException("File ended unexpectedly (truncated or corrupt GGUF).") }
    }
}
