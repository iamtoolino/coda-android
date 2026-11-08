package dev.coda.core.testing

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/**
 * One loopback port in front of a [FakeSubsonicServer] for the sign-in scheme tests. Plain http is forwarded to
 * the fake server; a TLS ClientHello (first byte 0x16) is handled by [tls]:
 * - [Tls.SILENT] keeps the connection open and never answers, so https times out (the only way to make https "get no
 *   answer" and http answer on the same port; a refused https needs separate ports, see ServerProbeTest);
 * - [Tls.UNTRUSTED] forwards to an https server whose self-signed certificate no client trusts (a certificate error);
 * - [Tls.PLAIN_HTTP] answers the ClientHello the way a plain-http Go server (Navidrome on `host:4533`) does: a plain-text
 *   `400 Bad Request` and close, so the client's handshake fails with a non-certificate TLS error.
 */
class SchemeFrontDoor(private val target: Int, tls: Tls) : Closeable {
    enum class Tls { SILENT, UNTRUSTED, PLAIN_HTTP }

    constructor(server: FakeSubsonicServer, tls: Tls) : this(server.port, tls)

    private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val held = CopyOnWriteArrayList<Socket>()
    private val https: MockWebServer? = if (tls == Tls.UNTRUSTED) MockWebServer().apply {
        val certificate = HeldCertificate.Builder().commonName("synthetic.example.org").addSubjectAlternativeName("127.0.0.1").build()
        useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        enqueue(MockResponse().setBody("{}"))
        start(InetAddress.getByName("127.0.0.1"), 0)
    } else null
    /** Connections that started a TLS handshake, and connections that spoke plain http. */
    val tlsConnections = AtomicInteger()
    val plainConnections = AtomicInteger()

    /** The address as a user would type it, without a scheme. */
    val address: String get() = "127.0.0.1:${listener.localPort}"

    init {
        Thread({
            while (!listener.isClosed) {
                val client = try { listener.accept() } catch (_: Exception) { break }
                held += client
                Thread({ handle(client, tls) }, "scheme-front-door-conn").apply { isDaemon = true }.start()
            }
        }, "scheme-front-door").apply { isDaemon = true }.start()
    }

    private fun handle(client: Socket, tls: Tls) { try {
        val input = BufferedInputStream(client.getInputStream())
        input.mark(1)
        val first = input.read()
        input.reset()
        if (first == 0x16) {
            tlsConnections.incrementAndGet()
            if (tls == Tls.UNTRUSTED) pipe(client, input, requireNotNull(https).port)
            if (tls == Tls.PLAIN_HTTP) client.use {
                input.read(ByteArray(8192)) // read the ClientHello first, as the Go server does, so closing sends no reset
                it.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\n400 Bad Request".toByteArray())
                it.getOutputStream().flush()
                it.shutdownOutput()
                Thread.sleep(200)
            }
            // SILENT: hold the socket until close(); the client's handshake read times out.
        } else if (first >= 0) {
            plainConnections.incrementAndGet()
            pipe(client, input, target)
        } else client.close()
    } catch (_: Exception) {
        runCatching { client.close() }
    } }

    private fun pipe(client: Socket, input: InputStream, port: Int) {
        val upstream = Socket(InetAddress.getByName("127.0.0.1"), port)
        held += upstream
        Thread({
            runCatching { input.copyTo(upstream.getOutputStream()); upstream.shutdownOutput() }
        }, "scheme-front-door-up").apply { isDaemon = true }.start()
        runCatching { upstream.getInputStream().copyTo(client.getOutputStream()) }
        runCatching { client.close() }
        runCatching { upstream.close() }
    }

    override fun close() {
        runCatching { listener.close() }
        held.forEach { runCatching { it.close() } }
        https?.shutdown()
    }
}
