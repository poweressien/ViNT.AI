package ng.pidgingpt.net

enum class OnlineAuth {
    /** Production: the app talks to YOUR backend proxy, which holds the provider's secret. An optional access token may be set; it is NOT the provider key. */
    PROXY,
    /** Personal testing only: the user types their own provider key on this phone. Refused unless the build is debuggable. Not secure for public distribution. */
    DIRECT_DEV_KEY,
}

/** Any OpenAI-compatible /chat/completions endpoint (OpenAI, OpenRouter, Groq, Together, Gemini's compatibility endpoint, or your own proxy). */
data class OnlineConfig(
    val baseUrl: String = "", val model: String = "", val auth: OnlineAuth = OnlineAuth.PROXY,
    val maxHistoryTurns: Int = 20, val maxContextChars: Int = 12_000,
    val connectTimeoutMs: Int = 10_000, val readTimeoutMs: Int = 45_000,
    /** Retries happen ONLY when the connection could not even be opened (nothing was sent), so they cannot duplicate a charge or a message. */
    val connectRetries: Int = 1,
    val debuggableBuild: Boolean = false,
) {
    val endpoint: String get() = baseUrl.trim().trimEnd('/') + "/chat/completions"
}

object OnlineConfigValidator {
    private val SECRETISH = Regex("(?i)(api[_-]?key|apikey|access[_-]?token|secret|token|key)=")
    private val KEY_LIKE = Regex("(sk-[A-Za-z0-9_-]{16,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{16,}|Bearer\\s+\\S{12,})")

    /** Returns every problem found (empty = valid). No network access. [secretPresent] = a key/token is stored. */
    fun validate(c: OnlineConfig, secretPresent: Boolean): List<String> {
        val p = ArrayList<String>(); val url = c.baseUrl.trim()
        if (url.isEmpty()) return listOf("Enter the service address (a https:// URL).")
        if (!url.startsWith("https://", ignoreCase = true)) p += "The address must start with https://. Plain http:// is not allowed."
        val rest = url.removePrefix("https://").removePrefix("HTTPS://")
        val authority = rest.substringBefore('/')
        if (authority.isEmpty() || authority.startsWith(".")) p += "The address has no host name."
        if ('@' in authority) p += "Do not put a user name or password in the address."
        if ('?' in url || '#' in url) p += "Do not add ? or # parts to the address."
        if (SECRETISH.containsMatchIn(url) || KEY_LIKE.containsMatchIn(url)) p += "The address looks like it contains a secret key. Keys never go in the address."
        if (c.model.isBlank()) p += "Enter a model name."
        if (c.maxHistoryTurns < 1) p += "History turns must be at least 1."
        if (c.maxContextChars < 500) p += "Context limit is too small."
        if (c.connectRetries !in 0..3) p += "Connect retries must be between 0 and 3."
        if (c.auth == OnlineAuth.DIRECT_DEV_KEY) {
            if (!c.debuggableBuild) p += "Typing a provider key into the app is only allowed in development (debug) builds. For a public build, use a backend proxy."
            if (!secretPresent) p += "Enter your key, or switch to the backend proxy option."
        }
        return p
    }

    fun looksLikeProviderKey(s: String): Boolean = KEY_LIKE.containsMatchIn(s)
}
