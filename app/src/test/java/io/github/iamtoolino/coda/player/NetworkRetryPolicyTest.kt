package io.github.iamtoolino.coda.player

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRetryPolicyTest {
    @Test fun `retry delays increase and stay capped`() {
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L),
            (0..5).map { networkRetryDelayMs(it) })
        assertEquals(10_000L, networkRetryDelayMs(0, initialDelayMs = 10_000))
        assertEquals(60_000L, networkRetryDelayMs(Int.MAX_VALUE))
    }
    @Test fun `temporary server responses retry but permanent responses do not`() {
        listOf(408, 429, 500, 502, 503, 504).forEach { assertTrue(isRetryableHttpStatus(it)) }
        listOf(200, 400, 401, 403, 404, 416).forEach { assertFalse(isRetryableHttpStatus(it)) }
    }
    @Test fun `network failures retry including wrapped playback causes`() {
        assertTrue(isRetryableNetworkFailure(SocketTimeoutException()))
        assertTrue(isRetryableNetworkFailure(UnknownHostException()))
        assertTrue(isRetryableNetworkFailure(IOException("wrapped", SocketTimeoutException())))
    }
    @Test fun `storage cancellation and decoder failures do not retry`() {
        assertFalse(isRetryableNetworkFailure(IOException("No space left on device")))
        assertFalse(isRetryableNetworkFailure(CancellationException()))
        assertFalse(isRetryableNetworkFailure(IllegalArgumentException("invalid media")))
    }
}
