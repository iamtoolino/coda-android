package dev.coda.android.net

import dev.coda.core.playback.BackgroundWorkPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Screen off and playing, idle connections close 20 s after the last request activity (virtual clock). */
@OptIn(ExperimentalCoroutinesApi::class)
class IdleConnectionCloserTest {
    private val delayMs = BackgroundWorkPolicy.IDLE_CONNECTION_CLOSE_MS

    private fun TestScope.closer(quiet: MutableStateFlow<Boolean>, evictions: IntArray): IdleConnectionCloser {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return IdleConnectionCloser(backgroundScope, quiet, { evictions[0]++ }, delayMs, dispatcher).also { runCurrent() }
    }

    @Test fun closesTwentySecondsAfterTheBurstAndANewBurstRestartsTheDelay() = runTest {
        val evictions = IntArray(1)
        val quiet = MutableStateFlow(true)
        val closer = closer(quiet, evictions)
        closer.activity(); runCurrent()
        advanceTimeBy(10_000); closer.activity(); runCurrent() // a later request in the same burst
        advanceTimeBy(delayMs - 1); runCurrent()
        assertEquals(0, evictions[0])
        advanceTimeBy(2); runCurrent()
        assertEquals(1, evictions[0])
        // Nothing more until the next burst.
        advanceTimeBy(10 * delayMs); runCurrent()
        assertEquals(1, evictions[0])
        closer.activity(); runCurrent()
        advanceTimeBy(delayMs + 1); runCurrent()
        assertEquals(2, evictions[0])
    }

    @Test fun screenOnNeverCloses() = runTest {
        val evictions = IntArray(1)
        val quiet = MutableStateFlow(false)
        val closer = closer(quiet, evictions)
        closer.activity(); runCurrent()
        advanceTimeBy(10 * delayMs); runCurrent()
        assertEquals(0, evictions[0])
        // Screen comes on during the delay: no close.
        quiet.value = true; runCurrent()
        closer.activity(); runCurrent()
        advanceTimeBy(delayMs / 2); quiet.value = false; runCurrent()
        advanceTimeBy(delayMs); runCurrent()
        assertEquals(0, evictions[0])
    }

    @Test fun screenTurningOffArmsTheClose() = runTest {
        val evictions = IntArray(1)
        val quiet = MutableStateFlow(false)
        closer(quiet, evictions)
        quiet.value = true; runCurrent()
        advanceTimeBy(delayMs - 1); runCurrent()
        assertEquals(0, evictions[0])
        advanceTimeBy(2); runCurrent()
        assertEquals(1, evictions[0])
    }

    /** The interceptor reports the request and the body's end; evictAll keeps a connection whose body is still read. */
    @Test fun activityOnRequestAndBodyEndAndEvictAllKeepsInUseConnections() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(64 * 1024)))
            server.enqueue(MockResponse().setBody("y"))
            server.start()
            var activity = 0
            ConnectionActivity.onActivity = { activity++ }
            try {
                val client = OkHttpClient.Builder().addNetworkInterceptor(ConnectionActivity.interceptor).build()
                val streaming = client.newCall(Request.Builder().url(server.url("/stream")).build()).execute()
                assertEquals(1, activity)
                streaming.body!!.source().readByte()
                client.connectionPool.evictAll()
                assertEquals(1, client.connectionPool.connectionCount()) // in use: kept
                streaming.close()
                assertEquals(2, activity)
                client.newCall(Request.Builder().url(server.url("/api")).build()).execute().use { it.body!!.string() }
                assertEquals(4, activity)
                assertTrue(client.connectionPool.idleConnectionCount() >= 1)
                client.connectionPool.evictAll()
                assertEquals(0, client.connectionPool.connectionCount())
            } finally {
                ConnectionActivity.onActivity = null
            }
        }
    }

    @Test fun sharedClientReportsActivity() {
        assertTrue(HttpClientFactory.client.networkInterceptors.any { it === ConnectionActivity.interceptor })
    }
}
