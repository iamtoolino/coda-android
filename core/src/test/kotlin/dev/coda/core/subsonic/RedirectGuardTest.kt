package dev.coda.core.subsonic

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
 * Upgrade-only redirects for authenticated requests (RedirectGuard), against real sockets: `music.example.org` and
 * `other.example.org` resolve to loopback; port 80 is the http server, 443 the https one, 4600 another host's server.
 */
class RedirectGuardTest {
    init { Logger.getLogger(MockWebServer::class.java.name).level = Level.OFF }
    private val certificate = HeldCertificate.Builder().commonName("music.example.org").addSubjectAlternativeName("music.example.org").build()
    private val ok = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""
    private val http = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    private val https = MockWebServer().apply {
        useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        start(InetAddress.getByName("127.0.0.1"), 0)
    }
    private val other = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }

    @After fun tearDown() { http.shutdown(); https.shutdown(); other.shutdown() }

    private fun client(): OkHttpClient {
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        return OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String) = if (hostname.endsWith(".example.org")) listOf(InetAddress.getByName("127.0.0.1"))
                    else throw java.net.UnknownHostException("synthetic")
            })
            .socketFactory(Remap(mapOf(80 to http.port, 443 to https.port, 4600 to other.port, 8443 to https.port, 4533 to http.port)))
            .sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
    }

    private fun subsonic(server: String) = SubsonicClient(SubsonicConfig(server, "synthetic", "synthetic-password"), client())

    /** Replies with a redirect to [target] plus the request's own path and query (so the authentication travels along). */
    private fun redirectTo(target: String, code: Int = 301) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(code)
            .setHeader("Location", target + request.requestUrl!!.encodedPath + (request.requestUrl!!.encodedQuery?.let { "?$it" } ?: ""))
    }

    private fun refused(block: suspend () -> Unit) {
        try { runBlocking { block() }; fail("The redirect must be refused") }
        catch (error: SubsonicError.Network) {
            assertTrue(error.exception.toString(), error.exception is RedirectRefused)
            assertFalse("no URL in the message", error.exception.message.orEmpty().contains("example"))
        }
    }

    @Test fun sameHostUpgradeIsFollowedAndRemembered() {
        http.dispatcher = redirectTo("https://music.example.org")
        https.enqueue(MockResponse().setBody(ok))
        val client = subsonic("http://music.example.org")
        runBlocking { client.ping() }
        assertEquals(1, https.requestCount)
        assertEquals("/rest/ping.view", https.takeRequest().requestUrl?.encodedPath)
        assertEquals("https://music.example.org", client.upgradedServerUrl)
    }

    @Test fun anHttpsServerIsNotAnUpgrade() {
        https.enqueue(MockResponse().setBody(ok))
        val client = subsonic("https://music.example.org")
        runBlocking { client.ping() }
        assertNull(client.upgradedServerUrl)
    }

    @Test fun upgradeThenDowngradeChainIsRefused() {
        http.dispatcher = redirectTo("https://music.example.org")
        https.dispatcher = redirectTo("http://music.example.org", 302)
        refused { subsonic("http://music.example.org").ping() }
        assertEquals("only the first hop reached http", 1, http.requestCount)
        assertEquals(1, https.requestCount)
    }

    @Test fun httpsToHttpIsRefused() {
        https.dispatcher = redirectTo("http://music.example.org", 302)
        refused { subsonic("https://music.example.org").ping() }
        assertEquals(0, http.requestCount)
    }

    @Test fun crossHostRedirectWithTheQueryReachesNothing() {
        http.dispatcher = redirectTo("http://other.example.org:4600", 302)
        other.enqueue(MockResponse().setBody(ok))
        refused { subsonic("http://music.example.org").ping() }
        assertEquals(0, other.requestCount)
    }

    @Test fun crossHostRedirectWithTheFormBodyReachesNothing() {
        // 307 keeps the method and the form body (which carries u/t/s), so it must not be followed to another host.
        http.dispatcher = redirectTo("http://other.example.org:4600", 307)
        other.enqueue(MockResponse().setBody(ok))
        refused { subsonic("http://music.example.org").savePlayQueue(listOf("track-1"), 0, 0) }
        assertEquals(1, http.requestCount)
        assertEquals("POST", http.takeRequest().method)
        assertEquals(0, other.requestCount)
    }

    /** Review 3: a redirect that carries user info is refused, even a same-host upgrade. */
    @Test fun redirectWithUserInfoIsRefused() {
        http.dispatcher = redirectTo("https://synthetic-user:synthetic-pass@music.example.org")
        https.enqueue(MockResponse().setBody(ok))
        refused { subsonic("http://music.example.org").ping() }
        assertEquals(0, https.requestCount)
    }

    /** The recorded upgrade is credential-free even if an answer's URL carried user info (defence behind the guard). */
    @Test fun recordedUpgradeHasNoUserInfoQueryOrFragment() {
        val faked = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.newBuilder().scheme("https").username("synthetic-user").password("synthetic-pass").build()
            okhttp3.Response.Builder().request(chain.request().newBuilder().url(url).build()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(ok.toResponseBody(null)).build()
        }.build()
        val client = SubsonicClient(SubsonicConfig("http://music.example.org", "synthetic", "synthetic-password"), faked)
        runBlocking { client.ping() }
        assertEquals("https://music.example.org", client.upgradedServerUrl)
    }

    /** `Host` (HTTP/1.1) or `:authority` (HTTP/2, what https negotiates here). */
    private fun authority(request: RecordedRequest) = request.getHeader("Host") ?: request.getHeader(":authority")

    // The host name is the trust boundary (LG-1 decision), so a same-host redirect may change the port.
    @Test fun sameHostUpgradeToAnotherPortIsFollowedAndRemembered() {
        http.dispatcher = redirectTo("https://music.example.org:8443")
        https.enqueue(MockResponse().setBody(ok))
        val client = subsonic("http://music.example.org:4533")
        runBlocking { client.ping() }
        assertEquals(1, https.requestCount)
        assertEquals("https://music.example.org:8443", client.upgradedServerUrl)
    }

    @Test fun sameHostHttpsPortChangeIsFollowed() {
        https.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                if (authority(request) == "music.example.org") redirectTo("https://music.example.org:8443", 302).dispatch(request)
                else MockResponse().setBody(ok)
        }
        runBlocking { subsonic("https://music.example.org").ping() }
        assertEquals(2, https.requestCount)
        https.takeRequest()
        assertEquals("music.example.org:8443", authority(https.takeRequest()))
    }

    @Test fun samePlainHttpHostPortChangeIsFollowed() {
        http.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                if (authority(request) == "music.example.org") redirectTo("http://music.example.org:4533", 302).dispatch(request)
                else MockResponse().setBody(ok)
        }
        val client = subsonic("http://music.example.org")
        runBlocking { client.ping() }
        assertEquals(2, http.requestCount)
        http.takeRequest()
        assertEquals("music.example.org:4533", authority(http.takeRequest()))
        assertNull("not an upgrade", client.upgradedServerUrl)
    }

    @Test fun portChangeDoesNotExcuseADowngrade() {
        https.dispatcher = redirectTo("http://music.example.org:4533", 302)
        refused { subsonic("https://music.example.org").ping() }
        assertEquals(0, http.requestCount)
    }

    @Test fun guardRules() {
        assertTrue(RedirectGuard.allowed("music.example.org", hopIsHttps = false, targetHost = "MUSIC.example.org", targetIsHttps = true))
        assertTrue(RedirectGuard.allowed("music.example.org", hopIsHttps = false, targetHost = "music.example.org", targetIsHttps = false))
        assertTrue(RedirectGuard.allowed("music.example.org", hopIsHttps = true, targetHost = "music.example.org", targetIsHttps = true))
        assertFalse(RedirectGuard.allowed("music.example.org", hopIsHttps = true, targetHost = "music.example.org", targetIsHttps = false))
        assertFalse(RedirectGuard.allowed("music.example.org", hopIsHttps = false, targetHost = "other.example.org", targetIsHttps = true))
    }
}
