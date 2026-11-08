package dev.coda.core.subsonic

import dev.coda.core.testing.FakeSubsonicServer
import dev.coda.core.testing.SchemeFrontDoor
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger
import javax.net.SocketFactory
import javax.net.ssl.SSLException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.TlsVersion
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Scheme resolution against real sockets. `music.example.org` resolves to loopback, and the default ports are
 * remapped (443 to [httpsPort], 80 to the plain server), so "https refused, http answers" is a real refused connection.
 */
class ServerProbeTest {
    init { Logger.getLogger(MockWebServer::class.java.name).level = Level.OFF }
    private val certificate = HeldCertificate.Builder().commonName("music.example.org").addSubjectAlternativeName("music.example.org").build()
    private val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    private val envelope = """{"subsonic-response":{"status":"failed","version":"1.16.1","type":"navidrome","serverVersion":"0.0.0","openSubsonic":true,"error":{"code":10,"message":"Missing parameter"}}}"""
    private val http = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    private val https = MockWebServer().apply {
        useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        start(InetAddress.getByName("127.0.0.1"), 0)
    }
    private val closed = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).run { localPort.also { close() } }
    private val silent = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val held = mutableListOf<Socket>()
    init {
        Thread {
            while (!silent.isClosed) try { synchronized(held) { held += silent.accept() } } catch (_: Exception) { break }
        }.apply { isDaemon = true }.start()
    }

    @After fun tearDown() {
        http.shutdown(); https.shutdown(); silent.close()
        synchronized(held) { held.forEach { runCatching { it.close() } } }
    }

    private fun probe(httpsPort: Int, trustCertificate: Boolean = true): ServerProbe =
        ServerProbe(probeClient(httpsPort, if (trustCertificate) trusted else HandshakeCertificates.Builder().build()), "Coda test")

    private fun loopbackClient() = OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS).build()

    private fun probeClient(httpsPort: Int, certificates: HandshakeCertificates): OkHttpClient {
        val ports = mapOf(443 to httpsPort, 80 to http.port, 4533 to http.port)
        return OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String) = if (hostname.endsWith(".example.org")) listOf(InetAddress.getByName("127.0.0.1"))
                    else throw java.net.UnknownHostException("synthetic")
            })
            .socketFactory(Remap(ports))
            .sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)
            .connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS).build()
    }

    private fun resolve(probe: ServerProbe, input: String) = runBlocking { probe.resolve(input) }
    private fun networkError(block: () -> Unit): SubsonicError.Network {
        try { block() } catch (error: SubsonicError.Network) { return error }
        fail("Expected a network error")
        throw AssertionError()
    }

    @Test fun noSchemeTriesHttpsFirstAndSendsNoCredentials() {
        https.enqueue(MockResponse().setBody(envelope))
        val answer = resolve(probe(https.port), "music.example.org")
        assertEquals(ServerAnswer("https://music.example.org", subsonic = true), answer)
        assertTrue(answer.secure)
        val request = https.takeRequest()
        assertEquals("/rest/ping.view", request.requestUrl?.encodedPath)
        listOf("u", "t", "s", "p").forEach { assertNull(it, request.requestUrl?.queryParameter(it)) }
        assertEquals(0, http.requestCount)
    }

    @Test fun httpsRefusedFallsBackToHttp() {
        http.enqueue(MockResponse().setBody(envelope))
        assertEquals(ServerAnswer("http://music.example.org", subsonic = true), resolve(probe(closed), "music.example.org"))
        val request = http.takeRequest()
        listOf("u", "t", "s", "p").forEach { assertNull(it, request.requestUrl?.queryParameter(it)) }
    }

    @Test fun httpFallbackCountsOnlyWhenSubsonicAnswers() {
        http.enqueue(MockResponse().setResponseCode(404).setBody("<html>Not found</html>"))
        val error = networkError { resolve(probe(closed), "music.example.org") }
        assertTrue("the https failure stands", error.exception is java.net.ConnectException)
        assertEquals(1, http.requestCount)
        assertTrue("LG-4: the screen may say another web server answered (information only)", error.otherServerAnswered)
    }

    @Test fun otherServerAnsweredIsOnlySetWhenSomethingAnsweredOnHttp() {
        // Nothing listens on http either: no answer, so no "other server".
        http.shutdown()
        val error = networkError { resolve(probe(closed), "music.example.org") }
        assertTrue(error.exception is java.net.ConnectException)
        assertFalse(error.otherServerAnswered)
    }

    @Test fun httpsTimeoutFallsBackToHttp() {
        http.enqueue(MockResponse().setBody(envelope))
        assertEquals(ServerAnswer("http://music.example.org", subsonic = true), resolve(probe(silent.localPort), "music.example.org"))
    }

    @Test fun untrustedCertificateIsAnErrorAndNeverTriesHttp() {
        https.enqueue(MockResponse().setBody(envelope))
        val error = networkError { resolve(probe(https.port, trustCertificate = false), "music.example.org") }
        assertTrue(error.exception is SSLException)
        assertTrue(ServerProbe.isCertificateProblem(error.exception))
        assertEquals(0, http.requestCount)
    }

    /** A plain-http port (Navidrome's own host:4533) answers the ClientHello in plain text: try http there. */
    @Test fun plainHttpPortFallsBackToHttpOnTheSamePort() {
        FakeSubsonicServer().use { server ->
            SchemeFrontDoor(server, SchemeFrontDoor.Tls.PLAIN_HTTP).use { door ->
                val answer = resolve(ServerProbe(loopbackClient(), "Coda test"), door.address)
                assertEquals(ServerAnswer("http://${door.address}", subsonic = true), answer)
                assertTrue(door.tlsConnections.get() >= 1)
                assertEquals(1, server.anonymousPings.get())
            }
        }
    }

    /** The same non-TLS answer, but nothing Subsonic answers over http: the TLS error stands, no http result. */
    @Test fun nonTlsAnswerWithoutSubsonicOnHttpKeepsTheTlsError() {
        val plain = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!plain.isClosed) try {
                plain.accept().use { socket ->
                    socket.getInputStream().read(ByteArray(8192))
                    socket.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    socket.shutdownOutput()
                    Thread.sleep(100)
                }
            } catch (_: Exception) { break }
        }.apply { isDaemon = true }.start()
        try {
            val error = networkError { resolve(ServerProbe(loopbackClient(), "Coda test"), "127.0.0.1:${plain.localPort}") }
            assertTrue(error.exception.toString(), error.exception is SSLException)
        } finally { plain.close() }
    }

    @Test fun wrongHostNameIsACertificateErrorWithoutFallback() {
        val other = HeldCertificate.Builder().commonName("other.example.net").addSubjectAlternativeName("other.example.net").build()
        val server = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(other).build().sslSocketFactory(), false)
            enqueue(MockResponse().setBody(envelope))
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        try {
            val client = probeClient(server.port, HandshakeCertificates.Builder().addTrustedCertificate(other.certificate).build())
            val error = networkError { resolve(ServerProbe(client, "Coda test"), "music.example.org") }
            assertTrue(ServerProbe.isCertificateProblem(error.exception))
            assertEquals(0, http.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun expiredCertificateIsACertificateErrorWithoutFallback() {
        val day = 24 * 3600 * 1000L
        val now = System.currentTimeMillis()
        // A trust anchor's own dates are not checked, so the expired certificate is a leaf issued by a trusted root.
        val root = HeldCertificate.Builder().certificateAuthority(0).commonName("Synthetic root").build()
        val expired = HeldCertificate.Builder().commonName("music.example.org").addSubjectAlternativeName("music.example.org")
            .signedBy(root).validityInterval(now - 20 * day, now - 10 * day).build()
        val server = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(expired, root.certificate).build().sslSocketFactory(), false)
            enqueue(MockResponse().setBody(envelope))
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        try {
            val client = probeClient(server.port, HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build())
            val error = networkError { resolve(ServerProbe(client, "Coda test"), "music.example.org") }
            val chain = generateSequence<Throwable>(error.exception) { it.cause }.joinToString(" <- ") { it.javaClass.name }
            assertTrue(chain, error.exception is SSLException)
            assertTrue(chain, ServerProbe.isCertificateProblem(error.exception))
            assertEquals(0, http.requestCount)
        } finally { server.shutdown() }
    }

    /** A probe never reuses a pooled TLS connection whose handshake it did not see. */
    @Test fun timeoutAfterAnEarlierHttpsAnswerOnTheSameClientNeverFallsBack() {
        val probe = probe(https.port)
        https.enqueue(MockResponse().setBody(envelope))
        assertEquals(ServerAnswer("https://music.example.org", subsonic = true), resolve(probe, "music.example.org"))
        https.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        http.enqueue(MockResponse().setBody(envelope))
        networkError { resolve(probe, "music.example.org") }
        assertEquals(0, http.requestCount)
    }

    @Test fun headersThenAStalledBodyIsAnAnswerAndNeverFallsBack() {
        https.enqueue(MockResponse().setBody(envelope).setBodyDelay(5, TimeUnit.SECONDS))
        http.enqueue(MockResponse().setBody(envelope))
        networkError { resolve(probe(https.port), "music.example.org") }
        assertEquals(0, http.requestCount)
    }

    /** An https redirect to http is not followed; the answer is the https one that was seen. */
    @Test fun httpsRedirectToHttpIsNotFollowed() {
        https.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://music.example.org/rest/ping.view"))
        http.enqueue(MockResponse().setBody(envelope))
        assertEquals(ServerAnswer("https://music.example.org", subsonic = false), resolve(probe(https.port), "music.example.org"))
        assertEquals(0, http.requestCount)
    }

    /** The authenticated client refuses the same downgrade, so the credential query never reaches http. */
    @Test fun authenticatedClientDoesNotFollowAnHttpsRedirectToHttp() {
        https.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://music.example.org/rest/ping.view"))
        http.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""))
        val client = SubsonicClient(SubsonicConfig("https://music.example.org", "synthetic", "synthetic-password"), probeClient(https.port, trusted))
        val error = networkError { runBlocking { client.ping() } }
        assertTrue(error.exception is RedirectRefused)
        assertEquals(1, https.requestCount)
        assertEquals(0, http.requestCount)
    }

    @Test fun fallbackClassification() = runBlocking {
        suspend fun allowed(error: java.io.IOException, tls: Boolean = false, answered: Boolean = false, plain: Boolean = false) =
            ServerProbe.fallbackAllowed(listOf(error), tls, answered) { plain }
        suspend fun allowedAll(vararg errors: java.io.IOException, plain: Boolean = false) =
            ServerProbe.fallbackAllowed(errors.toList(), false, false) { plain }
        // Every route counts; one TLS failure that is not a proven plain-http port forbids the fallback.
        assertFalse(allowedAll(javax.net.ssl.SSLProtocolException("alert"), java.net.ConnectException()))
        assertFalse(allowedAll(java.net.ConnectException(), javax.net.ssl.SSLProtocolException("alert")))
        assertTrue(allowedAll(java.net.ConnectException(), javax.net.ssl.SSLProtocolException("plain"), plain = true))
        assertTrue(allowedAll(java.net.ConnectException(), java.net.SocketTimeoutException()))
        assertFalse(allowedAll(java.net.ConnectException(), java.net.UnknownHostException()))
        assertFalse("nothing failed, nothing to fall back from", ServerProbe.fallbackAllowed(emptyList(), false, false) { true })
        assertFalse(allowedAll(java.net.ConnectException(), javax.net.ssl.SSLPeerUnverifiedException("host"), plain = true))
        assertTrue(allowed(java.net.ConnectException()))
        assertTrue(allowed(java.net.SocketTimeoutException()))
        assertTrue("plain-http port, positively identified", allowed(javax.net.ssl.SSLProtocolException("synthetic"), plain = true))
        assertFalse("any other TLS failure stands", allowed(javax.net.ssl.SSLProtocolException("synthetic")))
        assertFalse(allowed(javax.net.ssl.SSLHandshakeException("Received fatal alert: handshake_failure")))
        assertFalse(allowed(java.net.SocketTimeoutException(), tls = true))
        assertFalse(allowed(java.net.SocketTimeoutException(), answered = true))
        assertFalse(allowed(java.net.UnknownHostException()))
        assertFalse(allowed(javax.net.ssl.SSLPeerUnverifiedException("host"), plain = true))
        val untrusted = javax.net.ssl.SSLHandshakeException("synthetic").apply {
            initCause(java.security.cert.CertificateException(java.security.cert.CertPathValidatorException("no trust anchor")))
        }
        assertFalse(allowed(untrusted, plain = true))
        val expired = javax.net.ssl.SSLHandshakeException("synthetic").apply { initCause(java.security.cert.CertificateExpiredException()) }
        assertFalse(allowed(expired, plain = true))
    }

    /** Review 3: the plain-http proof goes to the address whose handshake failed, not the first DNS answer. */
    @Test fun proofUsesTheFailedRouteWhenTheFirstAddressRefuses() {
        FakeSubsonicServer().use { server ->
            SchemeFrontDoor(server, SchemeFrontDoor.Tls.PLAIN_HTTP).use { door ->
                val first = InetAddress.getByAddress("music.example.org", byteArrayOf(192.toByte(), 0, 2, 1))
                val second = InetAddress.getByAddress("music.example.org", byteArrayOf(192.toByte(), 0, 2, 2))
                val doorPort = door.address.substringAfter(':').toInt()
                val client = OkHttpClient.Builder()
                    .dns(object : Dns { override fun lookup(hostname: String) = listOf(first, second) })
                    .socketFactory(AddressRemap(mapOf("192.0.2.1:443" to closed, "192.0.2.2:443" to doorPort,
                        "192.0.2.1:80" to doorPort, "192.0.2.2:80" to doorPort)))
                    .connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS).build()
                assertEquals(ServerAnswer("http://music.example.org", subsonic = true), resolve(ServerProbe(client, "Coda test"), "music.example.org"))
                assertEquals(1, server.anonymousPings.get())
            }
        }
    }

    /** Review 3: through a proxy there is no direct proof: no direct socket to the server and no http fallback. */
    @Test fun proxiedRouteNeverOpensADirectSocketOrFallsBack() {
        val direct = java.util.concurrent.atomic.AtomicInteger()
        val target = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread { while (!target.isClosed) try { target.accept().close(); direct.incrementAndGet() } catch (_: Exception) { break } }
            .apply { isDaemon = true }.start()
        val proxied = java.util.concurrent.atomic.AtomicInteger()
        // An HTTP proxy that opens the tunnel and then answers the ClientHello in plain HTTP (a plain-http server behind it).
        val proxy = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!proxy.isClosed) try {
                proxy.accept().use { socket ->
                    proxied.incrementAndGet()
                    val input = socket.getInputStream()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) { val b = input.read(); if (b < 0) break; header.append(b.toChar()) }
                    socket.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                    input.read(ByteArray(8192))
                    socket.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".toByteArray())
                    socket.shutdownOutput()
                    Thread.sleep(100)
                }
            } catch (_: Exception) { break }
        }.apply { isDaemon = true }.start()
        try {
            http.enqueue(MockResponse().setBody(envelope))
            val client = probeClient(target.localPort, trusted).newBuilder()
                .proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), proxy.localPort)))
                .build()
            val error = networkError { resolve(ServerProbe(client, "Coda test"), "music.example.org") }
            assertTrue(error.exception.toString(), error.exception is SSLException)
            assertTrue("the https attempt went through the proxy", proxied.get() >= 1)
            assertEquals("no direct socket to the server", 0, direct.get())
            assertEquals(0, http.requestCount)
        } finally { target.close(); proxy.close() }
    }

    /** Review: TLS failures that are not a plain-http port never fall back, even with Subsonic on http. */
    @Test fun protocolMismatchNeverFallsBack() = tlsFailureNeverFallsBack(protocols = arrayOf("TLSv1.3"),
        client = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_2).build())

    // Two DNS answers; a TLS alert on one route must not be hidden by a refusal on the other.
    private val routeA = InetAddress.getByAddress("music.example.org", byteArrayOf(192.toByte(), 0, 2, 1))
    private val routeB = InetAddress.getByAddress("music.example.org", byteArrayOf(192.toByte(), 0, 2, 2))
    private val tls12Only = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_2).build()

    /** TLS connections accepted by [tls13OnlyServer]. */
    private val alertHandshakes = java.util.concurrent.atomic.AtomicInteger()

    /** A real TLS server that only speaks TLS 1.3: a TLS 1.2-only client gets a `protocol_version` alert. */
    private fun tls13OnlyServer() = MockWebServer().apply {
        useHttps(object : DelegatingServerSockets(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory()) {
            override fun configure(socket: javax.net.ssl.SSLSocket) { alertHandshakes.incrementAndGet(); socket.enabledProtocols = arrayOf("TLSv1.3") }
        }, false)
        start(InetAddress.getByName("127.0.0.1"), 0)
    }

    private fun twoRouteProbe(portA: Int, portB: Int): ServerProbe {
        val client = OkHttpClient.Builder()
            .dns(object : Dns { override fun lookup(hostname: String) = listOf(routeA, routeB) })
            .socketFactory(AddressRemap(mapOf("192.0.2.1:443" to portA, "192.0.2.2:443" to portB,
                "192.0.2.1:80" to http.port, "192.0.2.2:80" to http.port)))
            .sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
            .connectionSpecs(listOf(tls12Only, ConnectionSpec.CLEARTEXT)) // cleartext too, so a wrong fallback would reach http
            .connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS).build()
        return ServerProbe(client, "Coda test")
    }

    @Test fun tlsAlertThenRefusalNeverFallsBack() {
        val alert = tls13OnlyServer()
        try {
            http.enqueue(MockResponse().setBody(envelope))
            val error = networkError { resolve(twoRouteProbe(alert.port, closed), "music.example.org") }
            // OkHttp throws only the last route's refusal; the first route's TLS alert must still forbid http.
            assertTrue(error.exception.toString(), error.exception is java.net.ConnectException)
            assertTrue("the first route reached the TLS server", alertHandshakes.get() >= 1)
            assertEquals("never http after a TLS alert on any route", 0, http.requestCount)
        } finally { alert.shutdown() }
    }

    @Test fun refusalThenTlsAlertNeverFallsBack() {
        val alert = tls13OnlyServer()
        try {
            http.enqueue(MockResponse().setBody(envelope))
            networkError { resolve(twoRouteProbe(closed, alert.port), "music.example.org") }
            assertTrue("the second route reached the TLS server", alertHandshakes.get() >= 1)
            assertEquals(0, http.requestCount)
        } finally { alert.shutdown() }
    }

    /** Both routes refuse: no TLS failure anywhere, so the plain fallback works across routes. */
    @Test fun refusalOnEveryRouteFallsBack() {
        http.enqueue(MockResponse().setBody(envelope))
        assertEquals(ServerAnswer("http://music.example.org", subsonic = true), resolve(twoRouteProbe(closed, closed), "music.example.org"))
        assertEquals(1, http.requestCount)
    }

    /**
     * The first proxy refuses (a recorded route failure), the second proxy's name does not resolve
     * (a terminal failure before any route, so never in connectFailed). Fail closed: no http attempt at all.
     */
    @Test fun proxyRefusalThenProxyDnsFailureNeverFallsBack() {
        val selected = java.util.concurrent.CopyOnWriteArrayList<String>()
        val selector = object : java.net.ProxySelector() {
            override fun select(uri: java.net.URI): List<java.net.Proxy> {
                selected += uri.scheme
                return listOf(
                    java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress(InetAddress.getByName("127.0.0.1"), closed)),
                    java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.invalid", 8080)),
                )
            }
            override fun connectFailed(uri: java.net.URI, sa: SocketAddress, ioe: java.io.IOException) = Unit
        }
        http.enqueue(MockResponse().setBody(envelope))
        val client = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String) = when {
                    hostname == "127.0.0.1" -> listOf(InetAddress.getByName("127.0.0.1"))
                    hostname.endsWith(".example.org") -> listOf(InetAddress.getByName("127.0.0.1"))
                    else -> throw java.net.UnknownHostException("synthetic")
                }
            })
            .proxySelector(selector)
            .connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS).build()
        val error = networkError { resolve(ServerProbe(client, "Coda test"), "music.example.org") }
        assertTrue(error.exception.toString(), error.exception is java.net.UnknownHostException)
        assertEquals("only the https attempt selected proxies; no http attempt", listOf("https"), selected.distinct())
        assertEquals(0, http.requestCount)
    }

    /** A server that worked over https before never tries http, even when https is refused. */
    @Test fun serverSeenOverHttpsNeverFallsBack() {
        http.enqueue(MockResponse().setBody(envelope))
        val error = networkError { runBlocking { probe(closed).resolve("music.example.org", httpsSeenBefore = true) } }
        assertTrue(error.exception is java.net.ConnectException)
        assertEquals(0, http.requestCount)
        FakeSubsonicServer().use { server ->
            SchemeFrontDoor(server, SchemeFrontDoor.Tls.PLAIN_HTTP).use { door ->
                networkError { runBlocking { ServerProbe(loopbackClient(), "Coda test").resolve(door.address, httpsSeenBefore = true) } }
                assertEquals("no plain-http proof, no http request", 0, door.plainConnections.get())
                assertEquals(0, server.anonymousPings.get())
            }
        }
    }

    // The whole check has a deadline; idle timeouts alone never end a trickling server.
    @Test fun httpsBodyTricklingBelowTheIdleTimeoutEndsAtTheDeadline() {
        https.enqueue(MockResponse().setBody("x".repeat(200_000)).throttleBody(16, 200, TimeUnit.MILLISECONDS))
        val base = probeClient(https.port, trusted)
        val started = System.nanoTime()
        val error = networkError { resolve(ServerProbe(base, "Coda test", deadlineMillis = 1_500), "music.example.org") }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue(error.exception.toString(), error.exception is java.net.SocketTimeoutException)
        assertTrue("ended at the deadline, not by an idle timeout: $elapsed ms", elapsed in 1_400..4_000)
        assertEquals(0, http.requestCount)
        assertEquals("the shared client gets no call timeout (streaming is unaffected)", 0, base.callTimeoutMillis)
        assertEquals(15_000L, ServerProbe.DEADLINE_MILLIS)
    }

    /**
     * A port that answers the TLS attempt with junk (a non-certificate TLS failure, so the plain-http proof runs) and
     * then holds the proof connection without a reply. [eof] counts down when the client closed the proof socket.
     */
    private class StallingProof : AutoCloseable {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val proofOpen = java.util.concurrent.CountDownLatch(1)
        val eof = java.util.concurrent.CountDownLatch(1)
        private val connections = java.util.concurrent.atomic.AtomicInteger()
        init {
            Thread {
                while (!server.isClosed) try {
                    val socket = server.accept()
                    if (connections.incrementAndGet() == 1) socket.use {
                        it.getInputStream().read(ByteArray(8192))
                        it.getOutputStream().write("NOT-TLS-AND-NOT-HTTP\r\n".toByteArray())
                        it.shutdownOutput()
                        Thread.sleep(100)
                    } else Thread {
                        socket.use {
                            val input = it.getInputStream()
                            input.read(ByteArray(8192)) // the ClientHello of the proof
                            proofOpen.countDown()
                            while (input.read() >= 0) Unit // never answers; returns when the client closes
                            eof.countDown()
                        }
                    }.apply { isDaemon = true }.start()
                } catch (_: Exception) { break }
            }.apply { isDaemon = true }.start()
        }
        val address get() = "127.0.0.1:${server.localPort}"
        override fun close() = server.close()
    }

    private fun slowClient() = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    private fun proofThreads() = Thread.getAllStackTraces().values.count { trace -> trace.any { it.methodName.startsWith("plainHttpReply") } }

    // Cancelling the check (a new address) closes the stalled proof socket at once, no thread left behind.
    @Test fun cancellationClosesTheStalledProofSocket() = StallingProof().use { stall ->
        runBlocking {
            val check = launch(kotlinx.coroutines.Dispatchers.Default) { ServerProbe(slowClient(), "Coda test").resolve(stall.address) }
            assertTrue("the proof connected", stall.proofOpen.await(5, TimeUnit.SECONDS))
            val started = System.nanoTime()
            kotlinx.coroutines.withTimeout(3_000) { check.cancelAndJoin() }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            assertTrue("cancel did not wait for the 10 s read timeout: $elapsed ms", elapsed < 2_000)
        }
        assertTrue("the server saw the proof socket closed", stall.eof.await(2, TimeUnit.SECONDS))
        assertEquals("no thread still blocked in the proof", 0, proofThreads())
    }

    @Test fun deadlineClosesTheStalledProofSocket() = StallingProof().use { stall ->
        val started = System.nanoTime()
        val error = networkError { resolve(ServerProbe(slowClient(), "Coda test", deadlineMillis = 1_500), stall.address) }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue(error.exception.toString(), error.exception is java.net.SocketTimeoutException)
        assertTrue("$elapsed ms", elapsed < 4_000)
        assertTrue("the server saw the proof socket closed", stall.eof.await(2, TimeUnit.SECONDS))
        assertEquals(0, proofThreads())
    }

    @Test fun cipherMismatchAlertNeverFallsBack() = tlsFailureNeverFallsBack(protocols = arrayOf("TLSv1.2"),
        suites = arrayOf("TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384"),
        client = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_2)
            .cipherSuites(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256).build())

    @Test fun handshakeEofNeverFallsBack() {
        val eof = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!eof.isClosed) try { eof.accept().use { it.getInputStream().read(ByteArray(8192)) } } catch (_: Exception) { break }
        }.apply { isDaemon = true }.start()
        try {
            http.enqueue(MockResponse().setBody(envelope))
            val error = networkError { resolve(probe(eof.localPort), "music.example.org") }
            assertTrue(error.exception.toString(), error.exception is SSLException)
            assertEquals(0, http.requestCount)
        } finally { eof.close() }
    }

    private fun tlsFailureNeverFallsBack(protocols: Array<String>, suites: Array<String>? = null, client: ConnectionSpec) {
        val restricted = object : DelegatingServerSockets(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory()) {
            override fun configure(socket: javax.net.ssl.SSLSocket) {
                socket.enabledProtocols = protocols
                suites?.let { socket.enabledCipherSuites = it }
            }
        }
        val server = MockWebServer().apply {
            useHttps(restricted, false)
            enqueue(MockResponse().setBody(envelope))
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        try {
            http.enqueue(MockResponse().setBody(envelope))
            val okClient = probeClient(server.port, trusted).newBuilder().connectionSpecs(listOf(client, ConnectionSpec.CLEARTEXT)).build() // cleartext too, so a wrong fallback would reach http
            val error = networkError { resolve(ServerProbe(okClient, "Coda test"), "music.example.org") }
            assertTrue(error.exception.toString(), error.exception is SSLException)
            assertFalse(ServerProbe.isCertificateProblem(error.exception))
            assertEquals("never http after a TLS failure that is not a plain-http port", 0, http.requestCount)
        } finally { server.shutdown() }
    }

    private abstract class DelegatingServerSockets(private val base: javax.net.ssl.SSLSocketFactory) : javax.net.ssl.SSLSocketFactory() {
        abstract fun configure(socket: javax.net.ssl.SSLSocket)
        private fun Socket.configured(): Socket = also { (it as? javax.net.ssl.SSLSocket)?.let(::configure) }
        override fun getDefaultCipherSuites(): Array<String> = base.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = base.supportedCipherSuites
        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket = base.createSocket(s, host, port, autoClose).configured()
        override fun createSocket(host: String?, port: Int): Socket = base.createSocket(host, port).configured()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = base.createSocket(host, port, localHost, localPort).configured()
        override fun createSocket(host: InetAddress?, port: Int): Socket = base.createSocket(host, port).configured()
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = base.createSocket(address, port, localAddress, localPort).configured()
    }

    @Test fun httpsThatAnsweredThenTimedOutDoesNotFallBack() {
        https.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        networkError { resolve(probe(https.port), "music.example.org") }
        assertEquals(1, https.requestCount)
        assertEquals(0, http.requestCount)
    }

    @Test fun anyHttpsAnswerWinsEvenWithoutSubsonic() {
        https.enqueue(MockResponse().setResponseCode(404).setBody("<html>Not found</html>"))
        assertEquals(ServerAnswer("https://music.example.org", subsonic = false), resolve(probe(https.port), "music.example.org"))
        assertEquals(0, http.requestCount)
    }

    @Test fun typedSchemeIsUsedAsItIs() {
        http.enqueue(MockResponse().setBody(envelope))
        assertEquals(ServerAnswer("http://music.example.org:4533", subsonic = true), resolve(probe(https.port), "http://music.example.org:4533"))
        assertEquals(0, https.requestCount)
        // A typed https that is refused is an error; it never tries http.
        networkError { resolve(probe(closed), "https://music.example.org") }
        assertEquals(1, http.requestCount)
    }

    @Test fun unknownHostIsAnErrorWithoutFallback() {
        networkError { resolve(probe(https.port), "nothing.example.net") }
        assertEquals(0, http.requestCount)
        assertEquals(0, https.requestCount)
    }

    @Test fun bothRefusedIsAnError() {
        http.shutdown()
        val error = networkError { resolve(probe(closed), "music.example.org") }
        assertTrue(error.exception is java.net.ConnectException)
    }

    @Test fun invalidAddressIsAConfigurationError() {
        try { resolve(probe(https.port), "  "); fail() } catch (_: SubsonicError.InvalidConfiguration) {}
        try { resolve(probe(https.port), "ftp://music.example.org"); fail() } catch (_: SubsonicError.InvalidConfiguration) {}
    }

    @Test fun envelopeRecognition() {
        assertTrue(ServerProbe.isSubsonicEnvelope(envelope))
        assertFalse(ServerProbe.isSubsonicEnvelope("<html></html>"))
        assertFalse(ServerProbe.isSubsonicEnvelope("""{"other":{}}"""))
        assertFalse(ServerProbe.isSubsonicEnvelope(null))
    }

    /** The fake server behind the device fixture: https silent, http answers on the same port. */
    @Test fun frontDoorSilentHttpsFallsBackOnTheSamePort() {
        FakeSubsonicServer().use { server ->
            SchemeFrontDoor(server, SchemeFrontDoor.Tls.SILENT).use { door ->
                val answer = resolve(ServerProbe(loopbackClient(), "Coda test"), door.address)
                assertEquals(ServerAnswer("http://${door.address}", subsonic = true), answer)
                assertTrue(door.tlsConnections.get() >= 1)
                assertEquals(1, server.anonymousPings.get())
            }
        }
    }

    @Test fun frontDoorUntrustedHttpsNeverReachesHttp() {
        FakeSubsonicServer().use { server ->
            SchemeFrontDoor(server, SchemeFrontDoor.Tls.UNTRUSTED).use { door ->
                val error = networkError { resolve(ServerProbe(loopbackClient(), "Coda test"), door.address) }
                assertTrue(error.exception is SSLException)
                assertEquals(0, door.plainConnections.get())
                assertEquals(0, server.anonymousPings.get())
            }
        }
    }
}
