package dev.coda.android.net

import dev.coda.core.connectivity.Reachability
import dev.coda.core.connectivity.Reachability.Link
import dev.coda.core.subsonic.SubsonicClient
import dev.coda.core.subsonic.SubsonicConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Subsonic API answer that does not decode (a `200 application/json` `{}`) is a failure, not a success
 * first and a failure after: through both production callbacks ([ServerContact.onSuccess], the interceptor, and
 * [ServerContact.onSubsonicOutcome], the client's hook) it confirms Server unreachable once, with no 3 s probe loop,
 * and idle stays probe-free. Covers and streams still count through the interceptor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerContactReachabilityTest {
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("{}")
        }
        start()
    }
    private val client = SubsonicClient(SubsonicConfig(server.url("/").toString().removeSuffix("/"), "synthetic", "synthetic-password", "Test"),
        HttpClientFactory.client, issue = ServerContact::issue)

    @After fun tearDown() { ServerContact.onSuccess = null; ServerContact.onSubsonicOutcome = null; server.shutdown() }

    private fun TestScope.at(ms: Long) { advanceTimeBy(ms - testScheduler.currentTime); runCurrent() }

    @Test fun anUndecodableApiAnswerConfirmsUnreachableWithoutAProbeLoop() = runTest {
        val probes = mutableListOf<Long>()
        val attended = MutableStateFlow(true)
        var gateResets = 0
        // The probe is the app's own (CodaApp): a ping through the shared client. Blocking keeps virtual time still.
        val r = Reachability(backgroundScope, { testScheduler.currentTime }, probe = {
            probes += testScheduler.currentTime
            runBlocking { client.ping() }; true
        }, attended = attended)
        ServerContact.connect(r) { gateResets++ }

        runCatching { client.ping() }
        at(40_000)
        assertEquals("the API request's failure probes once after 10 s and confirms", Link.UNREACHABLE, r.state.value.link)
        assertEquals("then the back schedule (5 s, 10 s, ...), no 10 s loop", listOf(10_000L, 15_000L, 25_000L), probes)
        assertEquals("an undecodable answer is no server answer for the gate", 0, gateResets)

        attended.value = false
        val before = probes.size
        at(600_000)
        assertEquals("idle: no probe at all", before, probes.size)
    }

    @Test fun coversAndStreamsStillCountThroughTheInterceptor() = runTest {
        var gateResets = 0
        val r = Reachability(backgroundScope, { testScheduler.currentTime }, probe = { null })
        ServerContact.connect(r) { gateResets++ }
        HttpClientFactory.client.newCall(Request.Builder().url(client.coverArtUrl("album-1", 300)).build()).execute().close()
        assertEquals(1, gateResets)
        assertTrue(r.state.value.known)
    }
}
