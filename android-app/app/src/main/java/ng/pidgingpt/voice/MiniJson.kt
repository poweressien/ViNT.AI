package ng.pidgingpt.voice

/** Minimal strict JSON parser (objects, arrays, strings, numbers, booleans, null). Pure Kotlin so manifests can be parsed and tested on any JVM. */
object MiniJson {
    class JsonException(msg: String) : Exception(msg)

    fun parse(text: String): Any? {
        val p = Parser(text); p.ws(); val v = p.value(); p.ws()
        if (p.i != text.length) throw JsonException("Trailing characters at ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun err(m: String): Nothing = throw JsonException("$m at $i")
        fun value(depth: Int = 0): Any? {
            if (depth > 64) err("Nesting too deep")
            if (i >= s.length) err("Unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(depth); '[' -> arr(depth); '"' -> str()
                't' -> lit("true", true); 'f' -> lit("false", false); 'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else err("Unexpected '$c'")
            }
        }
        fun lit(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) err("Bad literal"); i += w.length; return v }
        fun num(): Any { val st = i; if (s[i] == '-') i++; while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(st, i); return t.toLongOrNull() ?: t.toDoubleOrNull() ?: err("Bad number") }
        fun str(): String {
            i++; val sb = StringBuilder()
            while (true) {
                if (i >= s.length) err("Unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> { if (i >= s.length) err("Bad escape"); when (val e = s[i++]) {
                        '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'u' -> { if (i + 4 > s.length) err("Bad \\u"); sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> err("Bad escape '\\$e'") } }
                    c < ' ' -> err("Control character in string")
                    else -> sb.append(c)
                }
            }
        }
        fun arr(d: Int): List<Any?> { i++; val l = ArrayList<Any?>(); ws(); if (s.getOrNull(i) == ']') { i++; return l }
            while (true) { ws(); l.add(value(d + 1)); ws(); when (s.getOrNull(i++)) { ',' -> continue; ']' -> return l; else -> err("Expected , or ]") } } }
        fun obj(d: Int): Map<String, Any?> { i++; val m = LinkedHashMap<String, Any?>(); ws(); if (s.getOrNull(i) == '}') { i++; return m }
            while (true) { ws(); if (s.getOrNull(i) != '"') err("Expected key"); val k = str(); ws(); if (s.getOrNull(i++) != ':') err("Expected :")
                ws(); m[k] = value(d + 1); ws(); when (s.getOrNull(i++)) { ',' -> continue; '}' -> return m; else -> err("Expected , or }") } } }
    }
}
