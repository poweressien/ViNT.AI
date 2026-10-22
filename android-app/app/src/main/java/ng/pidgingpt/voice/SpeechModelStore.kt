package ng.pidgingpt.voice

import java.io.File

enum class AsrFamily { WHISPER, MOONSHINE }
data class AsrSpec(val id: String, val family: AsrFamily, val dir: File, val files: Map<String, String>, val language: String)
enum class TtsFamily { KOKORO, PIPER_VITS }
data class TtsSpec(val id: String, val family: TtsFamily, val dir: File, val files: Map<String, String>)

/**
 * Finds speech models under <root>/asr/<id>/ and <root>/tts/<id>/ by their file layout (the layouts shipped by the sherpa-onnx model releases).
 * A folder that does not match a known layout is reported as not installed: ViNT never says a speech model is ready because a folder exists.
 */
class SpeechModelStore(val root: File) {
    val asrDir get() = File(root, "asr"); val ttsDir get() = File(root, "tts")

    fun asrModels(): List<AsrSpec> = asrDir.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.mapNotNull { detectAsr(it) }
    fun ttsModels(): List<TtsSpec> = ttsDir.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.mapNotNull { detectTts(it) }
    /**
     * Imports a model ZIP (made by tools/fetch_voice_models.py). Unpacks safely, recognises the layout, and moves it to asr/<id> or tts/<id>.
     * Anything that is not a recognised speech model is deleted again and reported: a folder is never treated as a model just because it exists.
     */
    fun importZip(input: java.io.InputStream, id: String): Result<String> = runCatching {
        val safeId = id.removeSuffix(".zip").replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "model" }
        val staging = File(root, "_incoming/$safeId"); staging.deleteRecursively()
        try {
            SafeUnzip.extract(input, staging)
            val kind = when { detectAsr(staging) != null -> "asr"; detectTts(staging) != null -> "tts"
                else -> throw IllegalArgumentException("This ZIP is not a speech model ViNT recognises (expected a Whisper/Moonshine ASR or Piper/Kokoro TTS package).") }
            val dest = File(root, "$kind/$safeId"); dest.deleteRecursively(); dest.parentFile.mkdirs()
            if (!staging.renameTo(dest)) { staging.copyRecursively(dest, overwrite = true); staging.deleteRecursively() }
            kind
        } finally { staging.deleteRecursively() }
    }

    fun delete(kind: String, id: String): Boolean = File(root, "$kind/$id").deleteRecursively()
    fun installedTtsIds(): Set<String> = ttsModels().map { it.id }.toSet()

    companion object {
        private fun File.pick(vararg names: String): File? = names.map { File(this, it) }.firstOrNull { it.isFile }
        private fun File.glob(suffix: String): List<File> = listFiles { f -> f.isFile && f.name.endsWith(suffix) }.orEmpty().sortedBy { it.name }

        fun detectAsr(d: File): AsrSpec? {
            val tokens = d.glob("tokens.txt").firstOrNull() ?: d.glob("-tokens.txt").firstOrNull() ?: return null
            val moonPre = d.pick("preprocess.onnx"); val enc = d.pick("encode.int8.onnx", "encode.onnx"); val un = d.pick("uncached_decode.int8.onnx", "uncached_decode.onnx"); val ca = d.pick("cached_decode.int8.onnx", "cached_decode.onnx")
            if (moonPre != null && enc != null && un != null && ca != null)
                return AsrSpec(d.name, AsrFamily.MOONSHINE, d, mapOf("preprocessor" to moonPre.path, "encoder" to enc.path, "uncachedDecoder" to un.path, "cachedDecoder" to ca.path, "tokens" to tokens.path), "en")
            val wEnc = d.glob("-encoder.int8.onnx").firstOrNull() ?: d.glob("-encoder.onnx").firstOrNull()
            val wDec = d.glob("-decoder.int8.onnx").firstOrNull() ?: d.glob("-decoder.onnx").firstOrNull()
            if (wEnc != null && wDec != null) return AsrSpec(d.name, AsrFamily.WHISPER, d, mapOf("encoder" to wEnc.path, "decoder" to wDec.path, "tokens" to tokens.path), "en")
            return null
        }

        fun detectTts(d: File): TtsSpec? {
            val tokens = d.pick("tokens.txt") ?: return null; val espeak = File(d, "espeak-ng-data").takeIf { it.isDirectory } ?: return null
            val voices = d.pick("voices.bin"); val kModel = d.pick("model.int8.onnx", "model.onnx")
            if (voices != null && kModel != null) return TtsSpec(d.name, TtsFamily.KOKORO, d, mapOf("model" to kModel.path, "voices" to voices.path, "tokens" to tokens.path, "dataDir" to espeak.path))
            val vits = d.glob(".onnx").firstOrNull { !it.name.contains("tokens") }
            if (vits != null && voices == null) return TtsSpec(d.name, TtsFamily.PIPER_VITS, d, mapOf("model" to vits.path, "tokens" to tokens.path, "dataDir" to espeak.path))
            return null
        }
    }
}
