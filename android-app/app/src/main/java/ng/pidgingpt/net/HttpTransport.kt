package ng.pidgingpt.net

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

enum class Phase { CONNECT, TIMEOUT, IO }

/** [phase] CONNECT means the request body was never sent, so retrying cannot cause a duplicate request or charge. */
class TransportException(val phase: Phase, message: String, cause: Throwable? = null) : IOException(message, cause)

class HttpResponse(val status: Int, val headers: Map<String, String>, val body: InputStream, private val onClose: () -> Unit = {}) : Closeable {
    override fun close() { runCatching { body.close() }; runCatching { onClose() } }
}

/** Abstraction over HTTPS so providers and the downloader are testable with fakes (no real network in unit tests). */
interface HttpTransport {
    fun get(url: String, headers: Map<String, String>, connectTimeoutMs: Int, readTimeoutMs: Int): HttpResponse
    fun post(url: String, headers: Map<String, String>, body: ByteArray, connectTimeoutMs: Int, readTimeoutMs: Int): HttpResponse
}

/**
 * The ONLY class in the app that opens a network connection (tools/audit_android_offline.py enforces that). HTTPS only: plain http:// is refused,
 * including on redirects. Redirects are followed by hand (GET only) so the first hop's x-linked-etag / x-linked-size headers (the file's SHA-256 and
 * size on Hugging Face) are kept. No credentials are forwarded to a different host. Nothing is logged.
 */
class HttpUrlTransport : HttpTransport {
    override fun get(url: String, headers: Map<String, String>, connectTimeoutMs: Int, readTimeoutMs: Int) = request("GET", url, headers, null, connectTimeoutMs, readTimeoutMs)
    override fun post(url: String, headers: Map<String, String>, body: ByteArray, connectTimeoutMs: Int, readTimeoutMs: Int) = request("POST", url, headers, body, connectTimeoutMs, readTimeoutMs)

    private fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?, ct: Int, rt: Int): HttpResponse {
        var current = url; val carried = HashMap<String, String>(); val firstHost = hostOf(url)
        repeat(6) {
            val u = URL(current)
            if (!u.protocol.equals("https", ignoreCase = true)) throw TransportException(Phase.CONNECT, "Only https:// addresses are allowed.")
            val c = u.openConnection() as HttpsURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = ct; c.readTimeout = rt; c.requestMethod = method; c.useCaches = false
            val sameHost = u.host.equals(firstHost, ignoreCase = true)
            headers.forEach { (k, v) -> if (sameHost || !k.equals("Authorization", ignoreCase = true)) c.setRequestProperty(k, v) }
            if (body != null) { c.doOutput = true; c.setFixedLengthStreamingMode(body.size) }
            try { c.connect() } catch (e: SocketTimeoutException) { c.disconnect(); throw TransportException(Phase.CONNECT, "Connection timed out.", e) }
            catch (e: IOException) { c.disconnect(); throw TransportException(Phase.CONNECT, "Could not connect.", e) }
            try {
                if (body != null) c.outputStream.use { it.write(body) }
                val code = c.responseCode
                if (code in intArrayOf(301, 302, 303, 307, 308) && method == "GET") {
                    for (h in listOf("x-linked-etag", "x-linked-size")) c.getHeaderField(h)?.let { carried.putIfAbsent(h, it) }
                    val loc = c.getHeaderField("Location") ?: throw TransportException(Phase.IO, "Redirect without a location.")
                    current = URL(u, loc).toString(); c.disconnect(); return@repeat
                }
                val map = HashMap<String, String>()
                for ((k, v) in c.headerFields) if (k != null && v.isNotEmpty()) map[k.lowercase()] = v.last()
                carried.forEach { (k, v) -> map.putIfAbsent(k, v) }
                val stream = (if (code >= 400) c.errorStream else c.inputStream) ?: ByteArrayInputStream(ByteArray(0))
                return HttpResponse(code, map, stream) { c.disconnect() }
            } catch (e: SocketTimeoutException) { c.disconnect(); throw TransportException(Phase.TIMEOUT, "The server took too long to answer.", e) }
            catch (e: TransportException) { c.disconnect(); throw e }
            catch (e: IOException) { c.disconnect(); throw TransportException(Phase.IO, "The connection was interrupted.", e) }
        }
        throw TransportException(Phase.IO, "Too many redirects.")
    }

    private fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault("")
}
