package io.github.iamtoolino.coda

import kotlinx.coroutines.CancellationException
import org.junit.Test

class AppGraphTest {
    @Test
    fun `matching session generation remains publishable`() {
        ensureSessionGeneration(expected = 7, current = 7)
    }

    @Test(expected = CancellationException::class)
    fun `changed session generation cancels stale publication`() {
        ensureSessionGeneration(expected = 7, current = 8)
    }
}
