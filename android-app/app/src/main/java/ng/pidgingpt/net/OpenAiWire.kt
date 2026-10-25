package ng.pidgingpt.net

import ng.pidgingpt.ai.AiFailure
import ng.pidgingpt.inference.ChatTurn
import ng.pidgingpt.voice.MiniJson

/** Pure parsing/encoding for OpenAI-compatible chat completions. No Android, no network: unit-tested on a JVM with canned responses. */
object OpenAiWire {
    sealed class StreamEvent {
        data class Delta(val text: String) : StreamEvent()
        object Done : StreamEvent()
        object Ignore : StreamEvent()
        data class Error(val message: String) : StreamEvent()
    }

    private fun q(s: String): String = buildString {
        append('"'); for (c in s) when { c == '"' -> append("\\\""); c == '\\' -> append("\\\\"); c == '\n' -> append("\\n"); c == '\r' -> append("\\r"); c == '\t' -> append("\\t"); c < ' ' -> append("\\u%04x".format(c.code)); else -> append(c) }; append('"')
    }

    fun requestBody(model: String, system: String, history: List<ChatTurn>, maxTokens: Int, temperature: Float, stream: Boolean): ByteArray {
        val msgs = (listOf(ChatTurn("system", system)) + history.filter { it.role == "user" || it.role == "assistant" }).joinToString(",") { "{\"role\":${q(it.role)},\"content\":${q(it.content)}}" }
        val t = "%.2f".format(java.util.Locale.US, temperature.coerceIn(0f, 2f))
        return "{\"model\":${q(model)},\"messages\":[$msgs],\"max_tokens\":$maxTokens,\"temperature\":$t,\"stream\":$stream}".toByteArray(Charsets.UTF_8)
    }

    /** One line of a server-sent-events stream. */
    fun parseStreamLine(line: String): StreamEvent {
        val l = line.trim()
        if (l.isEmpty() || l.startsWith(":") || !l.startsWith("data:")) return StreamEvent.Ignore
        val data = l.removePrefix("data:").trim()
        if (data == "[DONE]") return StreamEvent.Done
        return runCatching {
            val m = MiniJson.parse(data) as? Map<*, *> ?: return StreamEvent.Ignore
            (m["error"] as? Map<*, *>)?.let { return StreamEvent.Error(it["message"] as? String ?: "The service reported an error.") }
            val choice = (m["choices"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return StreamEvent.Ignore
            val text = (choice["delta"] as? Map<*, *>)?.get("content") as? String
            if (!text.isNullOrEmpty()) StreamEvent.Delta(text) else if (choice["finish_reason"] != null) StreamEvent.Ignore else StreamEvent.Ignore
        }.getOrDefault(StreamEvent.Ignore)
    }

    /** Non-streaming response body -> reply text, or null if the shape is not recognised. */
    fun parseFull(body: String): String? = runCatching {
        val m = MiniJson.parse(body) as Map<*, *>
        val choice = (m["choices"] as List<*>).first() as Map<*, *>
        (choice["message"] as Map<*, *>)["content"] as? String
    }.getOrNull()

    private fun errorMessage(body: String): String? = runCatching {
        val m = MiniJson.parse(body) as Map<*, *>
        ((m["error"] as? Map<*, *>)?.get("message") ?: m["message"]) as? String
    }.getOrNull()?.take(200)

    /** Turns an HTTP error into a clear failure. The body is only inspected for the quota hint and a short provider message; it is never logged. */
    fun classify(status: Int, body: String): AiFailure {
        val low = body.lowercase(); val msg = errorMessage(body)
        val suffix = if (msg != null) " ($msg)" else ""
        return when {
            status == 401 || status == 403 -> AiFailure.InvalidCredentials("The online service rejected the credentials (HTTP $status). Check the key or token in AI settings.$suffix")
            status == 429 && ("insufficient_quota" in low || "quota" in low || "billing" in low || "exceeded your current" in low) ->
                AiFailure.QuotaExhausted("The online account has run out of quota or credit.$suffix")
            status == 429 -> AiFailure.RateLimited("The online service is rate limiting requests. Try again in a moment.$suffix")
            status == 408 || status == 504 -> AiFailure.Timeout("The online service timed out (HTTP $status).")
            status in 500..599 -> AiFailure.ServerUnavailable("The online service is having problems (HTTP $status).")
            status == 402 -> AiFailure.QuotaExhausted("The online service says payment is required.$suffix")
            status in 400..499 -> AiFailure.BadRequest("The online service rejected the request (HTTP $status).$suffix")
            else -> AiFailure.Unexpected("Unexpected response from the online service (HTTP $status).")
        }
    }
}
