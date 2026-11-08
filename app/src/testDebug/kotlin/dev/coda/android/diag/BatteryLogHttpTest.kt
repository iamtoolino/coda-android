package dev.coda.android.diag

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The HTTP counters against a real (local) server: bytes of a stream that breaks half way, one failure per call. */
class BatteryLogHttpTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().eventListenerFactory(BatteryLog.httpEvents()).addNetworkInterceptor(BatteryLog.httpInterceptor())
        .readTimeout(5, TimeUnit.SECONDS).build()
    @After fun stop() = server.shutdown()

    private fun delta(counter: Int, block: () -> Unit): Long {
        val before = BatteryLog.counterValues()[counter]
        block()
        return BatteryLog.counterValues()[counter] - before
    }

    private fun get(path: String, onBody: (okhttp3.Response) -> Unit): Unit =
        client.newCall(Request.Builder().url(server.url(path)).build()).execute().use(onBody)

    @Test fun aCompleteBodyCountsItsBytesOnce() {
        server.enqueue(MockResponse().setBody("x".repeat(5_000)))
        server.start()
        var failures = 0L
        val bytes = delta(BatteryIds.HTTP_BYTES_IN) {
            failures = delta(BatteryIds.HTTP_FAILURES) { get("/rest/ping") { assertEquals(5_000, it.body!!.bytes().size) } }
        }
        assertEquals(5_000L, bytes); assertEquals(0L, failures)
    }

    @Test fun aStreamThatDisconnectsHalfWayCountsItsBytesAndOneFailure() {
        server.enqueue(MockResponse().setBody("y".repeat(100_000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        server.start()
        var failures = 0L
        val bytes = delta(BatteryIds.HTTP_BYTES_IN) {
            failures = delta(BatteryIds.HTTP_FAILURES) {
                try { get("/rest/stream") { it.body!!.bytes() }; throw AssertionError("the read should fail") } catch (_: IOException) {}
            }
        }
        assertTrue("some, not all, bytes: $bytes", bytes in 1 until 100_000)
        assertEquals("one failure for one call", 1L, failures)
    }

    @Test fun aRefusedCallCountsOneFailureAndACancelledCallNone() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.start()
        val failures = delta(BatteryIds.HTTP_FAILURES) { try { get("/rest/ping") {} } catch (_: IOException) {} }
        assertEquals(1L, failures)
        val call = client.newCall(Request.Builder().url(server.url("/rest/ping")).build())
        val cancelled = delta(BatteryIds.HTTP_FAILURES) { call.cancel(); try { call.execute() } catch (_: IOException) {} }
        assertEquals(0L, cancelled)
    }
}
