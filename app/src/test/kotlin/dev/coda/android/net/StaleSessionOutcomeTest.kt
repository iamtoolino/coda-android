package dev.coda.android.net

import dev.coda.core.connectivity.Reachability
import dev.coda.core.connectivity.Reachability.Link
import dev.coda.core.subsonic.SubsonicClient
import dev.coda.core.subsonic.SubsonicConfig
import dev.coda.core.subsonic.SubsonicError
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A request of the previous session (another account or server) that answers after the reachability
 * reset changes nothing for the new session, through the production callbacks: the Subsonic client's hook
 * ([ServerContact.issue]) and the shared client's interceptor (covers, streams). A request of the new session still counts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StaleSessionOutcomeTest {
    /** Held requests wait here until the test releases them (the old session's late reply). */
    private val arrived = CountDownLatch(1)
    private val release = CountDownLatch(1)
    @Volatile private var hold = true
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (hold) { arrived.countDown(); release.await(10, TimeUnit.SECONDS) }
                return if (request.path.orEmpty().contains("getCoverArt"))
                    MockResponse().setResponseCode(200).setHeader("Content-Type", "image/jpeg").setBody("synthetic")
                else MockResponse().setResponseCode(500)
            }
        }
        start()
    }
    private val client = SubsonicClient(SubsonicConfig(server.url("/").toString().removeSuffix("/"), "synthetic", "synthetic-password", "Test"),
        HttpClientFactory.client, issue = ServerContact::issue)

    @After fun tearDown() { release.countDown(); ServerContact.onSuccess = null; ServerContact.onSubsonicOutcome = null; server.shutdown() }

    private fun TestScope.at(ms: Long) { advanceTimeBy(ms - testScheduler.currentTime); runCurrent() }

    /** Starts [request] on a thread, waits until the server holds it, runs [meanwhile], then lets the reply go. */
    private fun late(request: () -> Unit, meanwhile: () -> Unit) {
        val worker = thread { runCatching(request) }
        assertTrue("the request reached the server", arrived.await(10, TimeUnit.SECONDS))
        meanwhile()
        release.countDown()
        worker.join(10_000)
        hold = false
    }

    @Test fun aLateApiFailureOfTheOldSessionStartsNoStreak() = runTest {
        val probes = mutableListOf<Long>()
        val r = Reachability(backgroundScope, { testScheduler.currentTime }, probe = { probes += testScheduler.currentTime; true })
        var gateResets = 0
        ServerContact.connect(r) { gateResets++ }

        late({ runBlocking { client.ping() } }) { r.reset() }
        at(30_000)
        assertEquals("no streak, so no confirming probe", emptyList<Long>(), probes)
        assertEquals(Link.ONLINE, r.state.value.link)

        // The new session's own failure still counts.
        runCatching { runBlocking { client.ping() } }
        at(40_000)
        assertEquals("its confirming probe 10 s later", listOf(40_000L), probes)
        assertEquals(0, gateResets)
    }

    @Test fun aLateCoverAnswerOfTheOldSessionNeitherEndsTheBackoffNorClearsTheNewStreak() = runTest {
        val probes = mutableListOf<Long>()
        val r = Reachability(backgroundScope, { testScheduler.currentTime }, probe = {
            probes += testScheduler.currentTime; throw SubsonicError.Network(java.io.IOException("down"))
        })
        var gateResets = 0
        ServerContact.connect(r) { gateResets++ }

        late({ HttpClientFactory.client.newCall(Request.Builder().url(client.coverArtUrl("album-1", 300)).build()).execute().close() }) {
            r.reset()
            r.failed() // the new session's first failure
        }
        assertEquals("the old answer does not end the new session's backoff", 0, gateResets)
        at(10_000)
        assertEquals("the new streak stands: its confirming probe runs", listOf(10_000L), probes)
        assertEquals(Link.UNREACHABLE, r.state.value.link)

        // A cover of the new session still counts.
        HttpClientFactory.client.newCall(Request.Builder().url(client.coverArtUrl("album-1", 300)).build()).execute().close()
        assertEquals(1, gateResets)
    }
}
