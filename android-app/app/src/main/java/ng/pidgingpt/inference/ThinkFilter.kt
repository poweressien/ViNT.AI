package ng.pidgingpt.inference

/**
 * Streaming filter that removes <think>...</think> reasoning blocks (emitted by Qwen3-family and other reasoning models) so they are never shown,
 * saved to history or spoken. Tags may arrive split across tokens. If a block is never closed (generation stopped early) its text stays hidden.
 * Pure Kotlin.
 */
class ThinkFilter {
    private val open = "<think>"; private val close = "</think>"
    private val hold = StringBuilder(); private var inThink = false; private var skipWs = true

    private fun partial(s: CharSequence, tag: String): Int {
        for (k in minOf(tag.length - 1, s.length) downTo 1) if (s.endsWith(tag.substring(0, k))) return k
        return 0
    }
    private fun CharSequence.endsWith(x: String): Boolean = length >= x.length && substring(length - x.length) == x

    fun push(piece: String): String {
        hold.append(piece); val out = StringBuilder()
        while (true) {
            if (!inThink) {
                val i = hold.indexOf(open)
                if (i >= 0) { out.append(hold, 0, i); hold.delete(0, i + open.length); inThink = true; continue }
                val keep = partial(hold, open); out.append(hold, 0, hold.length - keep); hold.delete(0, hold.length - keep); break
            } else {
                val j = hold.indexOf(close)
                if (j >= 0) { hold.delete(0, j + close.length); inThink = false; skipWs = true; continue }
                val keep = partial(hold, close); hold.delete(0, hold.length - keep); break
            }
        }
        return trim(out)
    }

    /** End of generation: releases a held partial tag that turned out to be ordinary text; discards unfinished reasoning. */
    fun flush(): String { val r = if (inThink) "" else trim(StringBuilder(hold)); hold.setLength(0); inThink = false; return r }

    private fun trim(out: StringBuilder): String {
        if (skipWs) { var i = 0; while (i < out.length && out[i].isWhitespace()) i++; out.delete(0, i); if (out.isNotEmpty()) skipWs = false }
        return out.toString()
    }
}
