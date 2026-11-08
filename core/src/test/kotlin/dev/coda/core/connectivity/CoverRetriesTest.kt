package dev.coda.core.connectivity

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Covers that failed offline are tried again when ONLINE returns, only with the screen on. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoverRetriesTest {
    @Test fun backOnlineWithTheScreenOnRetriesOnceAndTheScreenOffWaits() = runTest(UnconfinedTestDispatcher()) {
        val online = MutableStateFlow(true)
        val screen = MutableStateFlow(true)
        val retries = CoverRetries(backgroundScope, online, screen)
        assertEquals("online from the start: nothing to retry", 0, retries.ticks.value)
        online.value = false; online.value = true
        assertEquals("back ONLINE with the screen on: one retry", 1, retries.ticks.value)
        screen.value = false; screen.value = true
        assertEquals("the screen alone retries nothing", 1, retries.ticks.value)
        // Back online while the screen is off: nothing retries until the screen comes on.
        online.value = false; screen.value = false; online.value = true
        assertEquals("no retry with the screen off", 1, retries.ticks.value)
        screen.value = true
        assertEquals("the screen on retries what failed meanwhile", 2, retries.ticks.value)
        screen.value = false; screen.value = true
        assertEquals(2, retries.ticks.value)
    }
}
