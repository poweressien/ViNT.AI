package ng.pidgingpt.voice

enum class VoiceStatus { AVAILABLE, EXPERIMENTAL, UNAVAILABLE }

/**
 * One selectable (or deliberately unavailable) voice. Labels describe the RECORDED VOICE only.
 * ViNT never infers a listener's accent, region, gender or ethnicity, and never picks a voice from them.
 */
data class VoiceProfile(
    val id: String, val displayName: String, val engine: String, val modelId: String?, val speakerId: Int, val speed: Float,
    val voiceLabel: String, val accentDescription: String, val nigerianPidginStyle: String,
    val status: VoiceStatus, val statusReason: String, val license: String, val attribution: String, val source: String, val approxSizeMb: Int?,
) { val selectable: Boolean get() = status != VoiceStatus.UNAVAILABLE }

class VoiceCatalog(val profiles: List<VoiceProfile>) {
    fun byId(id: String?): VoiceProfile? = profiles.firstOrNull { it.id == id }
    fun unavailable(): List<VoiceProfile> = profiles.filter { !it.selectable }
    /** Selectable profiles whose model folder is installed. Never returns an unavailable profile. */
    fun installed(installedTtsModelIds: Set<String>): List<VoiceProfile> = profiles.filter { it.selectable && it.modelId != null && it.modelId in installedTtsModelIds }
    /** Default = the stored choice if still installed, otherwise the first installed profile (catalog order), otherwise null. No guessing from user data. */
    fun pick(storedId: String?, installedTtsModelIds: Set<String>): VoiceProfile? {
        val inst = installed(installedTtsModelIds); return inst.firstOrNull { it.id == storedId } ?: inst.firstOrNull()
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun parse(json: String): VoiceCatalog {
            val root = MiniJson.parse(json) as? Map<String, Any?> ?: throw MiniJson.JsonException("Catalog must be an object")
            val list = root["profiles"] as? List<Map<String, Any?>> ?: throw MiniJson.JsonException("Missing profiles array")
            val seen = HashSet<String>()
            return VoiceCatalog(list.map { m ->
                fun s(k: String) = m[k] as? String ?: ""
                val id = s("id"); require(id.isNotEmpty() && seen.add(id)) { "Missing or duplicate profile id '$id'" }
                val status = when (s("status")) { "available" -> VoiceStatus.AVAILABLE; "experimental" -> VoiceStatus.EXPERIMENTAL; "unavailable" -> VoiceStatus.UNAVAILABLE
                    else -> throw IllegalArgumentException("Profile $id: unknown status '${s("status")}'") }
                val model = m["model_id"] as? String
                require(status == VoiceStatus.UNAVAILABLE || !model.isNullOrEmpty()) { "Profile $id is selectable but has no model_id" }
                require(status != VoiceStatus.UNAVAILABLE || model == null) { "Profile $id is unavailable but references a model" }
                VoiceProfile(id, s("display_name"), s("engine"), model, (m["speaker_id"] as? Number)?.toInt() ?: 0, (m["speed"] as? Number)?.toFloat() ?: 1f,
                    s("voice_label"), s("accent_description"), s("nigerian_pidgin_style"), status, s("status_reason"), s("license"), s("attribution"), s("source"),
                    (m["approx_size_mb"] as? Number)?.toInt())
            })
        }
    }
}
