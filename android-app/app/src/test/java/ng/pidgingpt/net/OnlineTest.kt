package ng.pidgingpt.net

import ng.pidgingpt.ai.*
import ng.pidgingpt.data.LanguageMode
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.net.SocketTimeoutException

class OnlineTest {
    private val cfg = OnlineConfig("https://proxy.example.com/v1", "some-model", OnlineAuth.PROXY, debuggableBuild = false)
    private fun provider(t: FakeTransport, c: OnlineConfig = cfg, secret: String? = null) = OnlineChatProvider({ c }, { secret }, t, { LanguageMode.NATURAL_PIDGIN }, { "" })

    // ---- configuration validation / secret protection
    @Test fun validConfigHasNoProblems() { assertTrue(OnlineConfigValidator.validate(cfg, false).isEmpty()) }
    @Test fun plainHttpIsRefused() { assertTrue(OnlineConfigValidator.validate(cfg.copy(baseUrl = "http" + "://x.example.com/v1"), false).any { it.contains("https://") }) }
    @Test fun keysInTheAddressAreRefused() {
        assertTrue(OnlineConfigValidator.validate(cfg.copy(baseUrl = "https://x.example.com/v1?api_key=abc"), false).isNotEmpty())
        assertTrue(OnlineConfigValidator.validate(cfg.copy(baseUrl = "https://user:pw@x.example.com/v1"), false).isNotEmpty())
        assertTrue(OnlineConfigValidator.validate(cfg.copy(baseUrl = "https://x.example.com/" + "sk" + "-abcdefghijklmnopqrstuvwx"), false).any { it.contains("secret") }) }
    @Test fun directKeyOnlyInDebugBuildsAndOnlyWithAKey() {
        val d = cfg.copy(auth = OnlineAuth.DIRECT_DEV_KEY)
        assertTrue(OnlineConfigValidator.validate(d, true).any { it.contains("debug") })
        assertTrue(OnlineConfigValidator.validate(d.copy(debuggableBuild = true), false).any { it.contains("key") })
        assertTrue(OnlineConfigValidator.validate(d.copy(debuggableBuild = true), true).isEmpty()) }
    @Test fun missingModelAndEmptyAddressAreReported() { assertTrue(OnlineConfigValidator.validate(OnlineConfig(), false).isNotEmpty()); assertTrue(OnlineConfigValidator.validate(cfg.copy(model = " "), false).isNotEmpty()) }
    @Test fun providerKeyShapesAreDetected() { assertTrue(OnlineConfigValidator.looksLikeProviderKey("sk" + "-abcdefghijklmnopqrstuvwxyz")); assertFalse(OnlineConfigValidator.looksLikeProviderKey("hello")) }
    @Test fun unconfiguredProviderIsUnavailableAndNeverCallsTheNetwork() {
        val t = FakeTransport { _, _, _, _ -> error("must not be called") }; val p = provider(t, OnlineConfig())
        assertTrue(p.availability() is Availability.Unavailable)
        try { p.generate(req(), {}); fail() } catch (e: AiException) { assertTrue(e.failure is AiFailure.NotConfigured) }; assertTrue(t.calls.isEmpty()) }

    // ---- wire format
    @Test fun streamingDeltasAreAssembledInOrder() {
        val t = FakeTransport { _, _, _, _ -> resp(200, sse("Hel", "lo ", "wor", "ld"), "content-type" to "text/event-stream") }
        val sb = StringBuilder(); val st = provider(t).generate(req(), { sb.append(it) }); assertEquals("Hello world", sb.toString()); assertEquals(4, st.units); assertFalse(st.cancelled) }
    @Test fun nonStreamingJsonReplyIsAccepted() {
        val t = FakeTransport { _, _, _, _ -> resp(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Plain reply\"}}]}", "content-type" to "application/json") }
        val sb = StringBuilder(); provider(t).generate(req(), { sb.append(it) }); assertEquals("Plain reply", sb.toString()) }
    @Test fun requestIsHttpsJsonWithRequestIdAndSystemPromptAndNoSecretWhenNoneSaved() {
        val t = FakeTransport { _, _, _, _ -> resp(200, sse("ok"), "content-type" to "text/event-stream") }; provider(t).generate(req("rid-1", "hi"), {})
        val (m, url, h) = t.calls.single(); assertEquals("POST", m); assertEquals("https://proxy.example.com/v1/chat/completions", url)
        assertEquals("rid-1", h["X-Request-Id"]); assertEquals("rid-1", h["Idempotency-Key"]); assertFalse(h.containsKey("Authorization"))
        assertTrue(t.bodies.single().contains("\"stream\":true")); assertTrue(t.bodies.single().contains("\"role\":\"system\"")); assertTrue(t.bodies.single().contains("some-model")) }
    @Test fun savedSecretIsSentOnlyAsBearerHeaderNeverInTheBody() {
        val t = FakeTransport { _, _, _, _ -> resp(200, sse("ok"), "content-type" to "text/event-stream") }; provider(t, secret = "tok-12345").generate(req(), {})
        assertEquals("Bearer tok-12345", t.calls.single().third["Authorization"]); assertFalse(t.bodies.single().contains("tok-12345")) }
    @Test fun historyIsTrimmedToTheConfiguredLimit() {
        val t = FakeTransport { _, _, _, _ -> resp(200, sse("ok"), "content-type" to "text/event-stream") }
        val h = (1..10).map { ng.pidgingpt.inference.ChatTurn(if (it % 2 == 1) "user" else "assistant", "msg$it") }
        provider(t, cfg.copy(maxHistoryTurns = 3)).generate(req(history = h), {}); val b = t.bodies.single(); assertTrue(b.contains("msg10")); assertFalse(b.contains("msg6")) }
    @Test fun streamParserHandlesCommentsDoneAndErrors() {
        assertEquals(OpenAiWire.StreamEvent.Ignore, OpenAiWire.parseStreamLine(": keep-alive")); assertEquals(OpenAiWire.StreamEvent.Done, OpenAiWire.parseStreamLine("data: [DONE]"))
        assertTrue(OpenAiWire.parseStreamLine("data: {\"error\":{\"message\":\"x\"}}") is OpenAiWire.StreamEvent.Error); assertEquals(OpenAiWire.StreamEvent.Ignore, OpenAiWire.parseStreamLine("data: {broken")) }
    @Test fun jsonEscapingInRequestIsSafe() { val b = String(OpenAiWire.requestBody("m", "sys \"q\"", listOf(ng.pidgingpt.inference.ChatTurn("user", "line1\nline2 \\ \"x\"")), 10, 0.5f, true)); assertTrue(b.contains("line1\\nline2 \\\\ \\\"x\\\"")) }

    // ---- error classification
    @Test fun httpErrorsMapToClearFailures() {
        assertTrue(OpenAiWire.classify(401, "") is AiFailure.InvalidCredentials); assertTrue(OpenAiWire.classify(403, "") is AiFailure.InvalidCredentials)
        assertTrue(OpenAiWire.classify(429, "{\"error\":{\"message\":\"You exceeded your current quota\",\"code\":\"insufficient_quota\"}}") is AiFailure.QuotaExhausted)
        assertTrue(OpenAiWire.classify(429, "{}") is AiFailure.RateLimited); assertTrue(OpenAiWire.classify(503, "") is AiFailure.ServerUnavailable)
        assertTrue(OpenAiWire.classify(504, "") is AiFailure.Timeout); assertTrue(OpenAiWire.classify(404, "") is AiFailure.BadRequest) }
    @Test fun serverErrorSurfacesAsFailureWithNoFabricatedText() {
        val t = FakeTransport { _, _, _, _ -> resp(500, "oops") }; val sb = StringBuilder()
        try { provider(t).generate(req(), { sb.append(it) }); fail() } catch (e: AiException) { assertTrue(e.failure is AiFailure.ServerUnavailable) }; assertEquals("", sb.toString()) }

    // ---- retries are bounded and never duplicate a sent request
    @Test fun connectFailureIsRetriedOnceThenReported() {
        var n = 0; val t = FakeTransport { _, _, _, _ -> n++; throw TransportException(Phase.CONNECT, "no route") }
        try { provider(t).generate(req(), {}); fail() } catch (e: AiException) { assertTrue(e.failure is AiFailure.Network) }; assertEquals(2, n) }
    @Test fun readTimeoutIsNeverRetriedBecauseTheRequestMayHaveBeenProcessed() {
        var n = 0; val t = FakeTransport { _, _, _, _ -> n++; throw TransportException(Phase.TIMEOUT, "slow") }
        try { provider(t).generate(req(), {}); fail() } catch (e: AiException) { assertTrue(e.failure is AiFailure.Timeout) }; assertEquals(1, n) }
    @Test fun connectionDroppingMidStreamKeepsPartialTextAndReportsNetworkError() {
        val broken = object : InputStream() { var i = 0; val data = "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n".toByteArray()
            override fun read(): Int { if (i < data.size) return data[i++].toInt(); throw java.io.IOException("reset") } }
        val t = FakeTransport { _, _, _, _ -> HttpResponse(200, mapOf("content-type" to "text/event-stream"), broken) }
        val r = Rig(online = FakeProvider(ProviderKind.ONLINE, script = { rq, emit -> provider(t).generate(rq, emit) })); val (o, text) = r.run()
        assertTrue(o is AiOutcome.Failed && (o as AiOutcome.Failed).partial); assertEquals("Hel", text); assertEquals(0, r.english.calls) }
    @Test fun socketTimeoutWhileStreamingIsATimeoutFailure() {
        val slow = object : InputStream() { override fun read(): Int = throw SocketTimeoutException("read timed out") }
        val t = FakeTransport { _, _, _, _ -> HttpResponse(200, mapOf("content-type" to "text/event-stream"), slow) }
        try { provider(t).generate(req(), {}); fail() } catch (e: AiException) { assertTrue(e.failure is AiFailure.Timeout) } }

    // ---- connection test
    @Test fun connectionTestSendsNoChatContentAndReportsSuccess() {
        val t = FakeTransport { _, _, _, _ -> resp(200, "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}", "content-type" to "application/json") }
        assertTrue(provider(t).testConnection().isSuccess); assertTrue(t.bodies.single().contains("ping")); assertFalse(t.bodies.single().contains("hello")) }
    @Test fun connectionTestReportsBadCredentials() {
        val t = FakeTransport { _, _, _, _ -> resp(401, "{}") }; val r = provider(t).testConnection(); assertTrue(r.isFailure); assertTrue(r.exceptionOrNull()!!.message!!.contains("credentials")) }
}
