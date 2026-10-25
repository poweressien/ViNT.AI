package ng.pidgingpt.ai

import ng.pidgingpt.voice.MiniJson
import java.io.File

enum class ModelRole { ENGLISH, PIDGIN }
/** How we know what a model can do. Only EVALUATED or USER_CONFIGURED can make a model eligible for Pidgin routing. */
enum class Evidence { UNTESTED, OBSERVED, EVALUATED, USER_CONFIGURED }
enum class InstallStatus { INSTALLED, PARTIAL, FAILED }
enum class EvalStatus { NOT_EVALUATED, EVALUATED }
enum class RuntimeStatus { UNVERIFIED, LOADED_OK, LOAD_FAILED }

/** Extensible description of a local model. A Pidgin model you train later is just another profile with role PIDGIN. */
data class ModelProfile(
    val id: String, val displayName: String, val fileName: String, val format: String = "gguf",
    val architecture: String? = null, val runtime: RuntimeStatus = RuntimeStatus.UNVERIFIED, val runtimeNote: String? = null,
    val role: ModelRole = ModelRole.ENGLISH, val englishEvidence: Evidence = Evidence.UNTESTED, val pidginEvidence: Evidence = Evidence.UNTESTED,
    /** Explicit flag: the owner says this model may be used for Pidgin requests. Installing a file never sets it. */
    val markedAvailable: Boolean = false,
    val license: String? = null, val licenseSource: String? = null, val provenance: String = "Imported by the user",
    val version: String? = null, val sha256: String? = null, val integrity: String? = null, val sizeBytes: Long = 0L,
    val installStatus: InstallStatus = InstallStatus.INSTALLED, val evalStatus: EvalStatus = EvalStatus.NOT_EVALUATED, val evalNote: String? = null,
) {
    /** Pidgin routing needs ALL of: Pidgin role, explicit availability, and evaluation or explicit user configuration. "Observed" is not enough. */
    val pidginEligible: Boolean get() = role == ModelRole.PIDGIN && markedAvailable && installStatus == InstallStatus.INSTALLED &&
        (pidginEvidence == Evidence.EVALUATED || pidginEvidence == Evidence.USER_CONFIGURED)
}

/** Persistence of [ModelProfile]s as JSON in app-private storage. Thread-safe. A corrupt file reads as empty and is never overwritten until the next write. */
class ModelRegistry(private val file: File) {
    private val lock = Any()

    fun all(): List<ModelProfile> = synchronized(lock) { read() }
    fun get(fileName: String): ModelProfile? = all().firstOrNull { it.fileName == fileName }

    fun upsert(p: ModelProfile) = synchronized(lock) {
        val l = read().filter { it.fileName != p.fileName } + p
        write(l)
    }

    fun remove(fileName: String) = synchronized(lock) { write(read().filter { it.fileName != fileName }) }

    private fun read(): List<ModelProfile> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val root = MiniJson.parse(file.readText()) as Map<*, *>
            (root["models"] as List<*>).mapNotNull { fromMap(it as? Map<*, *> ?: return@mapNotNull null) }
        }.getOrDefault(emptyList())
    }

    private fun write(l: List<ModelProfile>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(toJson(l)); if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        private inline fun <reified E : Enum<E>> enumOf(v: Any?, d: E): E = runCatching { enumValueOf<E>(v as String) }.getOrDefault(d)

        fun fromMap(m: Map<*, *>): ModelProfile? {
            val id = m["id"] as? String ?: return null; val fn = m["fileName"] as? String ?: return null
            return ModelProfile(id, m["displayName"] as? String ?: fn, fn, m["format"] as? String ?: "gguf", m["architecture"] as? String,
                enumOf(m["runtime"], RuntimeStatus.UNVERIFIED), m["runtimeNote"] as? String, enumOf(m["role"], ModelRole.ENGLISH),
                enumOf(m["englishEvidence"], Evidence.UNTESTED), enumOf(m["pidginEvidence"], Evidence.UNTESTED), m["markedAvailable"] as? Boolean ?: false,
                m["license"] as? String, m["licenseSource"] as? String, m["provenance"] as? String ?: "Imported by the user", m["version"] as? String,
                m["sha256"] as? String, m["integrity"] as? String, (m["sizeBytes"] as? Number)?.toLong() ?: 0L,
                enumOf(m["installStatus"], InstallStatus.INSTALLED), enumOf(m["evalStatus"], EvalStatus.NOT_EVALUATED), m["evalNote"] as? String)
        }

        private fun q(s: String?): String = if (s == null) "null" else buildString {
            append('"'); for (c in s) when { c == '"' -> append("\\\""); c == '\\' -> append("\\\\"); c == '\n' -> append("\\n"); c == '\r' -> append("\\r"); c == '\t' -> append("\\t"); c < ' ' -> append("\\u%04x".format(c.code)); else -> append(c) }; append('"')
        }

        fun toJson(l: List<ModelProfile>): String = buildString {
            append("{\"version\":1,\"models\":[")
            l.forEachIndexed { i, p ->
                if (i > 0) append(',')
                append("{\"id\":${q(p.id)},\"displayName\":${q(p.displayName)},\"fileName\":${q(p.fileName)},\"format\":${q(p.format)},\"architecture\":${q(p.architecture)},")
                append("\"runtime\":${q(p.runtime.name)},\"runtimeNote\":${q(p.runtimeNote)},\"role\":${q(p.role.name)},\"englishEvidence\":${q(p.englishEvidence.name)},")
                append("\"pidginEvidence\":${q(p.pidginEvidence.name)},\"markedAvailable\":${p.markedAvailable},\"license\":${q(p.license)},\"licenseSource\":${q(p.licenseSource)},")
                append("\"provenance\":${q(p.provenance)},\"version\":${q(p.version)},\"sha256\":${q(p.sha256)},\"integrity\":${q(p.integrity)},\"sizeBytes\":${p.sizeBytes},")
                append("\"installStatus\":${q(p.installStatus.name)},\"evalStatus\":${q(p.evalStatus.name)},\"evalNote\":${q(p.evalNote)}}")
            }
            append("]}")
        }
    }
}
