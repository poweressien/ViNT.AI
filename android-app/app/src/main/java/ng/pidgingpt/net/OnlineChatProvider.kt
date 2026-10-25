package ng.pidgingpt.net

import ng.pidgingpt.ai.*
import ng.pidgingpt.data.LanguageMode
import ng.pidgingpt.inference.ChatTurn
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.SocketTimeoutException

/**
 * Online provider for any OpenAI-compatible chat endpoint. Message text leaves the phone ONLY from here, and only after the manager chose it
 * (not Offline Only, consent given, validated network). No secret is compiled in: the key/token comes from [secret] (Keystore-encrypted storage).
 * Bounded retries: only when the connection could not be opened. A request that was sent is never re-sent, so no duplicate reply or charge.
 */
class OnlineChatProvider(
    private val config: () -> OnlineConfig,
    private val secret: () -> String?,
    private val transport: HttpTransport,
    private val languageMode: () -> LanguageMode,
    private val pidginHelp: () -> String,
) : OnlineAIProvider {
    override val kind = ProviderKind.ONLINE
    override val id = "online-openai-compatible"
    override val capabilities = ProviderCapabilities("Cloud AI", streaming = true, sendsDataOffDevice = true, languages = setOf(ResponseLanguage.ENGLISH, ResponseLanguage.PIDGIN))

    @Volatile private var activeId: String? = null
    @Volatile private var activeResponse: HttpResponse? = null
    @Volatile private var cancelFlag = false

    private fun problems(): List<String> = OnlineConfigValidator.validate(config(), !secret().isNullOrBlank())

    override fun availability(): Availability {
        val p = problems()
        return if (p.isEmpty()) Availability.Available else Availability.Unavailable(UnavailableReason.NOT_CONFIGURED, p.first())
    }

    private fun headers(requestId: String?, stream: Boolean): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["Content-Type"] = "application/json"; h["Accept"] = if (stream) "text/event-stream" else "application/json"
        if (requestId != null) { h["X-Request-Id"] = requestId; h["Idempotency-Key"] = requestId }     // lets a proxy drop a duplicate delivery
        secret()?.takeIf { it.isNotBlank() }?.let { h["Authorization"] = "Bearer $it" }
        return h
    }

    private fun open(cfg: OnlineConfig, body: ByteArray, headers: Map<String, String>): HttpResponse {
        var last: TransportException? = null
        for (attempt in 0..cfg.connectRetries.coerceIn(0, 3)) {
            if (cancelFlag) throw AiException(AiFailure.Network("Cancelled."))
            try { return transport.post(cfg.endpoint, headers, body, cfg.connectTimeoutMs, cfg.readTimeoutMs) }
            catch (e: TransportException) { last = e; if (e.phase != Phase.CONNECT) throw e }     // anything past CONNECT may have reached the server: never retry
        }
        throw last ?: TransportException(Phase.CONNECT, "Could not connect.")
    }

    override fun generate(request: AiRequest, onPiece: (String) -> Unit): ProviderStats {
        val cfg = config(); val probs = problems()
        if (probs.isNotEmpty()) throw AiException(AiFailure.NotConfigured(probs.first()))
        cancelFlag = false; activeId = request.id
        val t0 = System.currentTimeMillis(); var chunks = 0
        try {
            val system = PromptBuilder.system(request.language, ProviderKind.ONLINE, languageMode(), request.voice, noThink = false, pidginHelp = pidginHelp())
            val history = ContextTrimmer.trim(request.history, cfg.maxHistoryTurns, cfg.maxContextChars)
            val body = OpenAiWire.requestBody(cfg.model, system, history, request.maxNewTokens, request.temperature, stream = true)
            val resp = try { open(cfg, body, headers(request.id, true)) } catch (e: TransportException) { throw AiException(map(e)) }
            activeResponse = resp
            resp.use { r ->
                if (r.status >= 400) throw AiException(OpenAiWire.classify(r.status, readLimited(r)))
                val ctype = r.headers["content-type"].orEmpty().lowercase()
                if ("text/event-stream" in ctype) {
                    val reader = BufferedReader(InputStreamReader(r.body, Charsets.UTF_8))
                    while (true) {
                        val line = reader.readLine() ?: break
                        when (val ev = OpenAiWire.parseStreamLine(line)) {
                            is OpenAiWire.StreamEvent.Delta -> { chunks++; onPiece(ev.text) }
                            is OpenAiWire.StreamEvent.Error -> throw AiException(AiFailure.ServerUnavailable("The online service reported an error: ${ev.message.take(160)}"))
                            OpenAiWire.StreamEvent.Done -> break
                            OpenAiWire.StreamEvent.Ignore -> {}
                        }
                    }
                } else {
                    val text = OpenAiWire.parseFull(readLimited(r, 1 shl 20)) ?: throw AiException(AiFailure.Unexpected("The online service sent a reply ViNT could not read."))
                    if (text.isNotEmpty()) { chunks++; onPiece(text) }
                }
            }
            return ProviderStats(chunks, System.currentTimeMillis() - t0, "chunks", cancelled = cancelFlag)
        } catch (e: AiException) { throw e
        } catch (e: IOException) {
            if (cancelFlag) return ProviderStats(chunks, System.currentTimeMillis() - t0, "chunks", cancelled = true)   // cancel() closed the connection
            throw AiException(if (e is SocketTimeoutException) AiFailure.Timeout() else AiFailure.Network("The connection was lost while the online service was answering."))
        } finally { activeResponse = null; activeId = null }
    }

    private fun readLimited(r: HttpResponse, max: Int = 4096): String {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(1024)
        while (out.size() < max) { val n = r.body.read(buf, 0, minOf(buf.size, max - out.size())); if (n < 0) break; out.write(buf, 0, n) }
        return out.toString("UTF-8")
    }

    private fun map(e: TransportException): AiFailure = when (e.phase) {
        Phase.CONNECT -> AiFailure.Network("Could not reach the online service (no connection, DNS failure, or the server is down).")
        Phase.TIMEOUT -> AiFailure.Timeout()
        Phase.IO -> AiFailure.Network()
    }

    override fun cancel(requestId: String) {
        if (activeId != requestId) return
        cancelFlag = true
        runCatching { activeResponse?.close() }       // unblocks the read; generate() then returns cancelled stats
    }

    override fun testConnection(): Result<String> = runCatching {
        val cfg = config(); val p = OnlineConfigValidator.validate(cfg, !secret().isNullOrBlank())
        if (p.isNotEmpty()) error(p.first())
        cancelFlag = false
        val body = OpenAiWire.requestBody(cfg.model, "Reply with the single word OK.", listOf(ChatTurn("user", "ping")), 5, 0f, stream = false)
        val resp = try { open(cfg, body, headers(null, false)) } catch (e: TransportException) { error(map(e).message) }
        resp.use { r ->
            if (r.status >= 400) error(OpenAiWire.classify(r.status, readLimited(r)).message)
            if (OpenAiWire.parseFull(readLimited(r, 1 shl 16)) == null) error("The service answered, but not in the expected format. Check the address and model name.")
        }
        "Connected. The service accepted the credentials and model."
    }
}
