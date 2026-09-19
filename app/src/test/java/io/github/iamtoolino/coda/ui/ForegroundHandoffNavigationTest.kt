package io.github.iamtoolino.coda.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundHandoffNavigationTest {
    @Test
    fun `external queue only returns untouched now playing to home`() {
        assertTrue(shouldReturnHomeForExternalQueue("now-playing", "now-playing"))
        assertFalse(shouldReturnHomeForExternalQueue("album/{id}", "album/{id}"))
        assertFalse(shouldReturnHomeForExternalQueue("queue", "queue"))
        assertFalse(shouldReturnHomeForExternalQueue("search", "search"))
    }

    @Test
    fun `home and user navigation are never redirected`() {
        assertFalse(shouldReturnHomeForExternalQueue("home", "home"))
        assertFalse(shouldReturnHomeForExternalQueue("now-playing", "album/{id}"))
        assertFalse(shouldReturnHomeForExternalQueue(null, "now-playing"))
    }
}
