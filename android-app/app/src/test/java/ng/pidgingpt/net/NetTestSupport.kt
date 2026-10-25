package ng.pidgingpt.net

import java.io.ByteArrayInputStream

/** Scripted transport. No sockets: unit tests never touch the network. */
class FakeTransport(private val handler: (method: String, url: String, headers: Map<String, String>, body: ByteArray?) -> HttpResponse) : HttpTransport {
    val calls = ArrayList<Triple<String, String, Map<String, String>>>(); val bodies = ArrayList<String>()
    override fun get(url: String, headers: Map<String, String>, connectTimeoutMs: Int, readTimeoutMs: Int): HttpResponse { calls += Triple("GET", url, headers); return handler("GET", url, headers, null) }
    override fun post(url: String, headers: Map<String, String>, body: ByteArray, connectTimeoutMs: Int, readTimeoutMs: Int): HttpResponse {
        calls += Triple("POST", url, headers); bodies += String(body, Charsets.UTF_8); return handler("POST", url, headers, body) }
}

fun resp(status: Int, body: String, vararg h: Pair<String, String>) = HttpResponse(status, h.toMap(), ByteArrayInputStream(body.toByteArray()))
fun sse(vararg chunks: String): String = chunks.joinToString("") { "data: {\"choices\":[{\"delta\":{\"content\":\"$it\"}}]}\n\n" } + "data: [DONE]\n\n"
