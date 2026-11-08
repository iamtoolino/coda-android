package dev.coda.core.subsonic

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** What answered at a server address: the resolved origin and whether it spoke the Subsonic API. */
data class ServerAnswer(val url: String, val subsonic: Boolean) {
    val secure: Boolean get() = url.startsWith("https://")
}

/**
 * The sign-in server check. An unauthenticated `ping.view` (no `u`, `t`, `s` or `p`), so no credentials are sent.
 *
 * Without a typed `scheme://` the address is tried as `https://` first. `http://` (same host and port) is tried only
 * when the https attempt ended before any TLS session or HTTP answer existed, and only because it was refused, timed
 * out, or the TLS handshake failed because the port is positively identified as answering a ClientHello in plain HTTP
 * (Navidrome's own `host:4533`). Every other TLS failure (alerts, protocol or cipher mismatch, EOF)
 * stands. A certificate error (untrusted, wrong host, expired) never falls back, nor does anything after an
 * established TLS session or any https answer. The http
 * result counts only when a Subsonic server answered there; otherwise the https failure stands. A typed scheme is used
 * as it is.
 *
 * Every probe uses its own connection pool (no pooled connection whose handshake this probe did not see) and follows
 * no redirects, so the reported URL is exactly the one that answered.
 *
 * Every route OkHttp tried counts (a TLS failure on one address is not hidden by a refusal on the next), the
 * whole check (https, plain-HTTP proof, http) ends after [deadlineMillis], and cancelling it closes the proof socket.
 * The deadline lives here only, on this probe's calls: the streaming and API clients keep their idle timeouts.
 */
class ServerProbe(client: OkHttpClient, private val clientName: String, private val deadlineMillis: Long = DEADLINE_MILLIS) {
    private val client = client.newBuilder().cache(null).followRedirects(false).followSslRedirects(false)
        .apply { networkInterceptors().removeAll { it === RedirectGuard } } // the check follows no redirects at all
        .build()

    /**
     * Throws [SubsonicError.InvalidConfiguration] for an unusable address and [SubsonicError.Network] when nothing usable
     * answered, or when the whole check took longer than [deadlineMillis] (a [SocketTimeoutException]).
     * [httpsSeenBefore]: this server worked over https before (recent servers, the saved session), so a
     * failing https is reported as it is and http is never tried.
     */
    suspend fun resolve(input: String, httpsSeenBefore: Boolean = false): ServerAnswer {
        val first = SubsonicConfig.normalizeServerURL(input)
        return withTimeoutOrNull(deadlineMillis) { resolveWithin(input, first, httpsSeenBefore) }
            ?: throw SubsonicError.Network(SocketTimeoutException("The server check took too long."))
    }

    private suspend fun resolveWithin(input: String, first: String, httpsSeenBefore: Boolean): ServerAnswer {
        val attempt = probe(first)
        attempt.answer?.let { return it }
        val failure = requireNotNull(attempt.failure)
        if (hasScheme(input) || httpsSeenBefore) throw SubsonicError.Network(failure)
        val origin = requireNotNull(first.toHttpUrlOrNull())
        val allowed = fallbackAllowed(attempt.failures, attempt.tlsEstablished, attempt.answered) { tlsFailure ->
            answersTlsInPlainHttp(origin.host, attempt.routes[tlsFailure])
        }
        if (!allowed) throw SubsonicError.Network(failure)
        val http = probe(SubsonicConfig.normalizeServerURL("http://" + input.trim()))
        return http.answer?.takeIf { it.subsonic } ?: throw SubsonicError.Network(failure, otherServerAnswered = http.answer != null)
    }

    /**
     * [failures]: the failure of every route OkHttp tried plus the call's terminal failure unless a route record
     * accounts for it (a DNS or proxy-selection failure happens before any `connectFailed`); [routes]: the address and
     * proxy each failure happened on (by identity).
     */
    private class Attempt(
        val answer: ServerAnswer?, val failure: IOException?, val tlsEstablished: Boolean, val answered: Boolean,
        val failures: List<IOException> = emptyList(), val routes: Map<IOException, Pair<InetSocketAddress, Proxy>> = emptyMap(),
    )

    private suspend fun probe(origin: String): Attempt {
        val base = origin.toHttpUrlOrNull() ?: throw SubsonicError.InvalidConfiguration("Enter a valid HTTP or HTTPS server URL.")
        val url = base.newBuilder().addPathSegment("rest").addPathSegment("ping.view")
            .addQueryParameter("v", "1.16.1").addQueryParameter("c", clientName).addQueryParameter("f", "json").build()
        val secured = AtomicBoolean(false)
        val answered = AtomicBoolean(false)
        // Per-route outcomes (review LG-1b item 1): OkHttp reports each failed route here, before it tries the next one.
        val routes = Collections.synchronizedMap(IdentityHashMap<IOException, Pair<InetSocketAddress, Proxy>>())
        val call = client.newBuilder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .eventListener(object : EventListener() {
                override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: okhttp3.Protocol?, ioe: IOException) {
                    routes[ioe] = inetSocketAddress to proxy
                }
                override fun secureConnectEnd(call: Call, handshake: Handshake?) { secured.set(true) }
                // Also covers a connection the pool handed out without a new handshake.
                override fun connectionAcquired(call: Call, connection: Connection) { if (connection.handshake() != null) secured.set(true) }
                override fun responseHeadersStart(call: Call) { answered.set(true) }
            }).build().newCall(Request.Builder().url(url).build())
        return try {
            val body = withContext(Dispatchers.IO) { call.await() }
            Attempt(ServerAnswer(origin, withContext(Dispatchers.Default) { isSubsonicEnvelope(body) }), null, secured.get(), true)
        } catch (error: IOException) {
            val recorded = synchronized(routes) { routes.toMap() }
            // What the call throws is not a reliable record of every route: OkHttp 4.12 reports only the last route's
            // failure when an earlier route failed differently. Every route failure goes through connectFailed, so all
            // of those are classified. The terminal failure counts too (fail closed): a DNS failure
            // while selecting the next proxy, or the first route's DNS, never reaches connectFailed. It is left out
            // only when it is a route failure already recorded: the same object, or the JDK's copy of a recorded TLS
            // failure (same class and message), which then keeps that route for the plain-HTTP proof.
            val accounted = recorded.keys.firstOrNull { it === error || it.sameFailureAs(error) }
            val failures = if (accounted != null) recorded.keys.toList() else recorded.keys.toList() + error
            Attempt(null, error, secured.get(), answered.get(), failures, recorded)
        }
    }

    private fun IOException.sameFailureAs(other: IOException): Boolean =
        javaClass == other.javaClass && message == other.message && this is SSLException

    /**
     * Positive identification of a plain-http port: send a genuine TLS ClientHello (from the platform's own SSLEngine)
     * over a fresh plain connection to the exact address whose handshake failed ([route], from the failed attempt) and
     * check that the reply starts with `HTTP/`. Through a proxy there is no such proof (the tunnel hides the server), so a
     * proxied route never falls back. Any failure or any other reply (a TLS record, an alert, nothing) is "no".
     * Cancelling (a new address, the overall deadline) closes the socket at once.
     */
    private suspend fun answersTlsInPlainHttp(host: String, route: Pair<InetSocketAddress, Proxy>?): Boolean {
        val (address, proxy) = route ?: return false
        if (proxy.type() != Proxy.Type.DIRECT || address.isUnresolved) return false
        val socket = try { client.socketFactory.createSocket() } catch (_: Exception) { return false }
        return try {
            closingOnCancel(socket) { plainHttpReply(socket, host, address) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun plainHttpReply(socket: Socket, host: String, address: InetSocketAddress): Boolean {
        val engine = SSLContext.getDefault().createSSLEngine(host, address.port).apply { useClientMode = true }
        val hello = ByteBuffer.allocate(engine.session.packetBufferSize)
        engine.beginHandshake()
        engine.wrap(ByteBuffer.allocate(0), hello)
        hello.flip()
        val bytes = ByteArray(hello.remaining()).also { hello.get(it) }
        val timeout = client.connectTimeoutMillis.coerceIn(1_000, 10_000)
        socket.connect(address, timeout)
        socket.soTimeout = client.readTimeoutMillis.coerceIn(1_000, 10_000)
        socket.getOutputStream().apply { write(bytes); flush() }
        val head = ByteArray(5)
        var read = 0
        val input = socket.getInputStream()
        while (read < head.size) {
            val n = input.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        return read == head.size && String(head, Charsets.US_ASCII) == "HTTP/"
    }

    companion object {
        fun hasScheme(input: String): Boolean = "://" in input.trim()

        /** The whole check (https, plain-HTTP proof, http) ends after this. */
        const val DEADLINE_MILLIS = 15_000L

        /**
         * May a failed https attempt try http? [failures] are the failures of every route that was tried; each one must
         * allow it. Never after a TLS session or an HTTP answer existed, never for a DNS failure or a certificate
         * problem; yes when refused or timed out; for any other TLS failure only when its route is positively identified
         * as answering the ClientHello in plain HTTP ([plainHttpReply], asked only once nothing else forbids it).
         */
        suspend fun fallbackAllowed(
            failures: List<IOException>, tlsEstablished: Boolean, answered: Boolean, plainHttpReply: suspend (SSLException) -> Boolean,
        ): Boolean {
            if (tlsEstablished || answered || failures.isEmpty()) return false
            val tls = failures.filterIsInstance<SSLException>()
            val noAnswer = failures.filterNot { it is SSLException }
                .all { it is ConnectException || it is SocketTimeoutException || it is NoRouteToHostException }
            if (!noAnswer || tls.any(::isCertificateProblem)) return false
            return tls.all { plainHttpReply(it) }
        }

        /** Untrusted, expired or not-yet-valid certificates and wrong host names. */
        fun isCertificateProblem(error: Throwable): Boolean =
            generateSequence(error) { it.cause }.take(16).any { it is SSLPeerUnverifiedException || it is GeneralSecurityException }

        private val json = Json { ignoreUnknownKeys = true }

        internal fun isSubsonicEnvelope(body: String?): Boolean = body != null && try {
            json.parseToJsonElement(body).jsonObject["subsonic-response"] is JsonObject
        } catch (_: Exception) { false }
    }
}

/**
 * Runs the blocking [block] on the IO pool; if the caller is cancelled first, [socket] is closed so the blocked read
 * or connect ends at once, and this returns only after [block] has finished (no thread left behind). The block's own
 * failure travels as a value, so the closed socket's exception never replaces the cancellation.
 */
private suspend fun <T> closingOnCancel(socket: Socket, block: () -> T): T = coroutineScope {
    val work = async(Dispatchers.IO) { runCatching(block) }
    try { work.await() } finally { if (!work.isCompleted) runCatching { socket.close() } }
}.getOrThrow()

/** Any HTTP status counts as an answer; the body is read only to recognise a Subsonic envelope. */
private suspend fun Call.await(): String? = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            try {
                val body = response.use { it.body?.source()?.let { source -> source.request(MAX_BODY); source.buffer.clone().readUtf8() } }
                continuation.resumeWith(Result.success(body))
            } catch (error: IOException) {
                continuation.resumeWithException(error)
            }
        }
    })
}

private const val MAX_BODY = 64L * 1024
