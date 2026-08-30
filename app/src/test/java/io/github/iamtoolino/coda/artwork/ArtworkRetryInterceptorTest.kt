package io.github.iamtoolino.coda.artwork

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkRetryInterceptorTest {
    @Test
    fun `retryable failures use both delays and stop on success`() = runBlocking {
        var attempts = 0
        val delays = mutableListOf<Long>()

        val result = retryArtworkRequest(
            retryDelaysMillis = listOf(250L, 750L),
            isRetryable = { it == "failure" },
            delayRequest = { delays += it },
            request = {
                attempts++
                if (attempts < 3) "failure" else "success"
            },
        )

        assertEquals("success", result)
        assertEquals(3, attempts)
        assertEquals(listOf(250L, 750L), delays)
    }

    @Test
    fun `non-retryable result returns after one attempt`() = runBlocking {
        var attempts = 0

        val result = retryArtworkRequest(
            retryDelaysMillis = listOf(250L, 750L),
            isRetryable = { false },
            delayRequest = { error("No delay expected") },
            request = { ++attempts },
        )

        assertEquals(1, result)
        assertEquals(1, attempts)
    }
}
