package ng.pidgingpt.data

import android.content.Context
import ng.pidgingpt.ai.AiMode
import ng.pidgingpt.ai.PromptBuilder
import ng.pidgingpt.ai.ProviderKind
import ng.pidgingpt.ai.ResponseLanguage
import ng.pidgingpt.net.OnlineAuth

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
    // ---- hybrid AI (milestone 4). Safe defaults: automatic routing, but NOTHING is sent online until the user gives consent.
    var aiMode: AiMode get() = runCatching { AiMode.valueOf(sp.getString("aiMode", "")!!) }.getOrDefault(AiMode.AUTOMATIC); set(v) = sp.edit().putString("aiMode", v.name).apply()
    var responseLanguage: ResponseLanguage get() = runCatching { ResponseLanguage.valueOf(sp.getString("respLang", "")!!) }.getOrDefault(ResponseLanguage.AUTOMATIC); set(v) = sp.edit().putString("respLang", v.name).apply()
    /** null = automatic model selection. */
    var preferredProvider: ProviderKind? get() = runCatching { ProviderKind.valueOf(sp.getString("preferredProvider", "")!!) }.getOrNull(); set(v) = sp.edit().putString("preferredProvider", v?.name ?: "").apply()
    var onlineConsent: Boolean get() = sp.getBoolean("onlineConsent", false); set(v) = sp.edit().putBoolean("onlineConsent", v).apply()
    var onlineBaseUrl: String get() = sp.getString("onlineUrl", "") ?: ""; set(v) = sp.edit().putString("onlineUrl", v.trim()).apply()
    var onlineModel: String get() = sp.getString("onlineModel", "") ?: ""; set(v) = sp.edit().putString("onlineModel", v.trim()).apply()
    var onlineAuth: OnlineAuth get() = runCatching { OnlineAuth.valueOf(sp.getString("onlineAuth", "")!!) }.getOrDefault(OnlineAuth.PROXY); set(v) = sp.edit().putString("onlineAuth", v.name).apply()
    var onlineMaxTurns: Int get() = sp.getInt("onlineTurns", 20); set(v) = sp.edit().putInt("onlineTurns", v).apply()
    var onlineMaxChars: Int get() = sp.getInt("onlineChars", 12_000); set(v) = sp.edit().putInt("onlineChars", v).apply()
    /** Voice replies use online AI only if this is on (default off). Microphone audio is never sent anywhere; only the transcribed text would be. */
    var onlineInVoice: Boolean get() = sp.getBoolean("onlineInVoice", false); set(v) = sp.edit().putBoolean("onlineInVoice", v).apply()
    var speakReplies: Boolean get() = sp.getBoolean("speakReplies", true); set(v) = sp.edit().putBoolean("speakReplies", v).apply()
    var pidginHelp: String get() = sp.getString("pidginHelp", null) ?: PromptBuilder.DEFAULT_PIDGIN_HELP; set(v) = sp.edit().putString("pidginHelp", v).apply()
}
