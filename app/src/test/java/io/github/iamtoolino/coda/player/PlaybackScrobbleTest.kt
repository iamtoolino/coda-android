package io.github.iamtoolino.coda.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackScrobbleTest {
    @Test
    fun `played submission point is ninety five percent`() {
        assertEquals(228_000L, playedSubmissionPoint(240_000L))
        assertEquals(0L, playedSubmissionPoint(0L))
    }

    @Test
    fun `scrobble retries until it succeeds`() = runBlocking {
        var attempts = 0

        val result = retryScrobble(maxAttempts = 5, initialDelayMs = 0) {
            attempts++
            if (attempts < 3) error("Temporary failure")
        }

        assertTrue(result)
        assertEquals(3, attempts)
    }

    @Test
    fun `scrobble stops after maximum attempts`() = runBlocking {
        var attempts = 0

        val result = retryScrobble(maxAttempts = 4, initialDelayMs = 0) {
            attempts++
            error("Still offline")
        }

        assertFalse(result)
        assertEquals(4, attempts)
    }
}
