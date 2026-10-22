package ng.pidgingpt.voice

/** Turns LLM text into something a TTS engine can speak, and splits streaming text into speakable chunks. Pure Kotlin. */
object SpeechText {
    private val codeBlock = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL)
    private val url = Regex("https?://\\S+")
    private val mdChars = Regex("[*_`#>~|]+")
    private val bullet = Regex("(?m)^\\s*(?:[-•]|\\d+[.)])\\s+")
    private val spaces = Regex("[ \\t]+")

    /** Removes emoji/symbols, markdown, links and code blocks. Keeps letters (incl. Yoruba/Igbo diacritics), digits and basic punctuation. */
    fun clean(text: String): String {
        var t = codeBlock.replace(text, " ")
        t = url.replace(t, " ")
        t = bullet.replace(t, "")
        t = mdChars.replace(t, " ")
        val sb = StringBuilder(t.length)
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i); i += Character.charCount(cp)
            val keep = Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.NON_SPACING_MARK.toInt() ||
                cp == ' '.code || cp == '\n'.code || cp in ".,!?;:'\"()-—–…".map { it.code }
            if (keep) sb.appendCodePoint(cp) else sb.append(' ')
        }
        return spaces.replace(sb.toString(), " ").replace(Regex(" *\n *"), "\n").trim()
    }

    /** Accumulates streamed text and releases complete sentences. Short fragments are held until [minChars] so TTS gets natural phrases. */
    class SentenceChunker(private val minChars: Int = 24, private val maxChars: Int = 220) {
        private val pending = StringBuilder()
        private fun isEnd(c: Char) = c == '.' || c == '!' || c == '?' || c == '…' || c == '\n'

        fun push(piece: String): List<String> {
            pending.append(piece); val out = ArrayList<String>()
            while (true) {
                var cut = -1
                for (i in pending.indices) {
                    val c = pending[i]
                    val boundary = isEnd(c) && (i + 1 == pending.length || pending[i + 1].isWhitespace())
                    if (boundary && i + 1 >= minChars) { cut = i + 1; break }
                    if (i + 1 >= maxChars && (c == ',' || c == ';' || c == ' ')) { cut = i + 1; break }
                }
                if (cut < 0) break
                val s = clean(pending.substring(0, cut)); pending.delete(0, cut)
                if (s.any { it.isLetterOrDigit() }) out.add(s)
            }
            return out
        }

        /** Call when the LLM has finished: releases whatever is left. */
        fun flush(): List<String> { val s = clean(pending.toString()); pending.setLength(0); return if (s.any { it.isLetterOrDigit() }) listOf(s) else emptyList() }
    }
}
