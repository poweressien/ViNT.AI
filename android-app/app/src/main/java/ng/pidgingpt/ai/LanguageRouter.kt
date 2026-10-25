package ng.pidgingpt.ai

import ng.pidgingpt.data.LanguageMode
import ng.pidgingpt.inference.ChatTurn
import ng.pidgingpt.inference.Prompts

/**
 * Lightweight, explainable language routing. It ESTIMATES; it is not a language identifier and its Pidgin word list is small.
 * The user's message is never changed or translated: only the instruction given to the model depends on the decision.
 * An explicit user choice (English / Nigerian Pidgin) always wins over the estimate.
 */
object LanguageRouter {
    /** Words that are strongly Pidgin and rare in standard English. Deliberately conservative ("go", "for", "chop" are left out: too ambiguous). */
    private val PIDGIN = setOf("wetin", "dey", "abeg", "wahala", "sabi", "oga", "una", "wey", "dem", "pikin", "comot", "sef", "abi", "shey", "jare", "naija",
        "oya", "kuku", "biko", "wan", "dis", "dat", "na", "no be", "e don", "make we", "una dey")
    private val SINGLE = PIDGIN.filter { ' ' !in it }.toSet()
    private val PHRASES = PIDGIN.filter { ' ' in it }
    private val ENGLISH_FUNCTION = setOf("the", "is", "are", "was", "were", "and", "of", "to", "in", "that", "it", "this", "with", "for", "have", "has", "what", "how", "can", "you", "please", "my", "your", "i")

    fun decide(userText: String, preference: ResponseLanguage, recent: List<ChatTurn> = emptyList()): LanguageDecision {
        if (preference == ResponseLanguage.ENGLISH) return LanguageDecision(ResponseLanguage.ENGLISH, explicit = true)
        if (preference == ResponseLanguage.PIDGIN) return LanguageDecision(ResponseLanguage.PIDGIN, explicit = true)
        val e = estimate(userText)
        // Very short messages carry little evidence: lean on the previous user message instead of flip-flopping.
        if (e.tokens < 3 && e.markers == 0) {
            val prev = recent.lastOrNull { it.role == "user" }?.let { estimate(it.content) }
            if (prev != null && prev.markers > 0) return LanguageDecision(ResponseLanguage.PIDGIN, explicit = false, mixed = prev.english >= 3, confidence = 0.4f)
        }
        return when {
            e.markers == 0 -> LanguageDecision(ResponseLanguage.ENGLISH, explicit = false, confidence = if (e.tokens >= 4) 0.8f else 0.5f)
            e.markers >= 2 || e.ratio >= 0.2f -> LanguageDecision(ResponseLanguage.PIDGIN, explicit = false, mixed = e.english >= 4 && e.ratio < 0.35f, confidence = 0.8f)
            else -> LanguageDecision(ResponseLanguage.PIDGIN, explicit = false, mixed = true, confidence = 0.5f)    // one marker among English words: treat as a mix
        }
    }

    private data class Est(val tokens: Int, val markers: Int, val english: Int) { val ratio: Float get() = if (tokens == 0) 0f else markers.toFloat() / tokens }

    private fun estimate(text: String): Est {
        val low = text.lowercase()
        val tokens = low.split(Regex("[^a-z']+")).filter { it.isNotEmpty() }
        var markers = tokens.count { it in SINGLE }
        val padded = " " + tokens.joinToString(" ") + " "
        markers += PHRASES.count { padded.contains(" $it ") }
        return Est(tokens.size, markers, tokens.count { it in ENGLISH_FUNCTION })
    }
}

/** Writes the system prompt for a given provider. The conversation history is shared; only this instruction differs per provider. */
object PromptBuilder {
    const val DEFAULT_PIDGIN_HELP = "The user may write in Nigerian Pidgin or mix it with English. Understand it as well as you can and answer helpfully. " +
        "If you are unsure what a Pidgin expression means, say so instead of guessing."
    private const val MIXED = "Reply in the same mix of English and Nigerian Pidgin that the user is using. Do not translate everything into formal English, " +
        "and do not exaggerate Pidgin or lean on stereotypes."
    private const val ENGLISH = "You are ViNT, a helpful assistant. Reply in clear, natural English."

    fun system(d: LanguageDecision, kind: ProviderKind, mode: LanguageMode, voice: Boolean, noThink: Boolean, pidginHelp: String = DEFAULT_PIDGIN_HELP): String {
        val core = when {
            d.response == ResponseLanguage.PIDGIN && !d.mixed -> return Prompts.system(mode, voice, noThink)    // existing, unchanged Pidgin prompt
            d.response == ResponseLanguage.PIDGIN -> "You are ViNT, a Nigerian Pidgin and English assistant. $MIXED"
            else -> ENGLISH
        }
        val help = if (kind == ProviderKind.LOCAL_PIDGIN || pidginHelp.isBlank()) "" else " $pidginHelp"
        return core + help + (if (voice) Prompts.voiceNote() else "") + (if (noThink) " /no_think" else "")
    }
}

/** Keeps the online request within configured limits. Pure. */
object ContextTrimmer {
    fun trim(history: List<ChatTurn>, maxTurns: Int, maxChars: Int): List<ChatTurn> {
        val kept = ArrayDeque<ChatTurn>(); var used = 0
        for (t in history.asReversed()) {
            if (kept.size >= maxTurns.coerceAtLeast(1)) break
            if (used + t.content.length > maxChars && kept.isNotEmpty()) break
            kept.addFirst(t); used += t.content.length
        }
        return kept.toList()
    }
}
