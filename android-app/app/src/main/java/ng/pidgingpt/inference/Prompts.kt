package ng.pidgingpt.inference

import ng.pidgingpt.data.LanguageMode

object Prompts {
    /** [voice]=true adds spoken-conversation rules. UNTESTED with a real Pidgin model: it is a design choice, not a measured improvement. */
    fun system(mode: LanguageMode, voice: Boolean = false, noThink: Boolean = false): String = base(mode) + (if (voice) VOICE_NOTE else "") + (if (noThink) " /no_think" else "")      // /no_think: Qwen3-family soft switch that disables reasoning output

    private const val VOICE_NOTE = " The user is talking to you by voice, and their words come from automatic speech recognition, which often mis-hears Pidgin words (for example 'wetin dey' can arrive as 'wet and day'). Work out the meaning from context, and if a message makes no sense, ask them to say it again. Reply in 1 to 3 short spoken sentences. No lists, no markdown, no emoji."

    private fun base(mode: LanguageMode): String = when (mode) {
        LanguageMode.STRICT_PIDGIN ->
            "You be ViNT, Nigerian Pidgin AI. Always reply for Nigerian Pidgin, even if person write standard English. " +
            "Explain technical things for Pidgin too, but keep am correct. No force slang. " +
            "If you no sure wetin one expression mean, talk say you no sure. No invent meaning."
        LanguageMode.NATURAL_PIDGIN ->
            "You be ViNT, Nigerian Pidgin AI. Reply for natural Nigerian Pidgin by default, even if person write standard English. " +
            "You fit keep English technical terms where e make sense. No force slang. " +
            "If you no sure wetin one expression mean, talk say you no sure. No invent meaning."
    }

    /** Keeps the system prompt and the most recent turns that fit a rough token budget (≈4 chars/token; replaced by real tokenizer counts later). */
    fun window(system: String, history: List<ChatTurn>, budgetTokens: Int, reserveForReply: Int): List<ChatTurn> {
        val budgetChars = (budgetTokens - reserveForReply).coerceAtLeast(256) * 4
        var used = system.length; val kept = ArrayDeque<ChatTurn>()
        for (t in history.asReversed()) { if (used + t.content.length > budgetChars && kept.isNotEmpty()) break; kept.addFirst(t); used += t.content.length }
        return listOf(ChatTurn("system", system)) + kept
    }
}
