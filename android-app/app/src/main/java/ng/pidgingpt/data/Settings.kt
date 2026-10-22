package ng.pidgingpt.data

import android.content.Context

class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var languageMode: LanguageMode
        get() = runCatching { LanguageMode.valueOf(sp.getString("lang", "")!!) }.getOrDefault(LanguageMode.NATURAL_PIDGIN)  // Pidgin is always the default
        set(v) = sp.edit().putString("lang", v.name).apply()
    var maxNewTokens: Int get() = sp.getInt("maxNew", 256); set(v) = sp.edit().putInt("maxNew", v).apply()
    var contextTokens: Int get() = sp.getInt("ctx", 2048); set(v) = sp.edit().putInt("ctx", v).apply()
    var temperature: Float get() = sp.getFloat("temp", 0.7f); set(v) = sp.edit().putFloat("temp", v).apply()
    var retentionDays: Int get() = sp.getInt("retention", 0); set(v) = sp.edit().putInt("retention", v).apply()
    var voiceProfileId: String? get() = sp.getString("voiceProfile", null); set(v) = sp.edit().putString("voiceProfile", v).apply()
    var asrModelId: String? get() = sp.getString("asrModel", null); set(v) = sp.edit().putString("asrModel", v).apply()
    var onboarded: Boolean get() = sp.getBoolean("onboarded", false); set(v) = sp.edit().putBoolean("onboarded", v).apply()
    var modelFileName: String? get() = sp.getString("model", null); set(v) = sp.edit().putString("model", v).apply()
}
