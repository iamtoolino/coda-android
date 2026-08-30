package io.github.iamtoolino.coda.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundHandoffNavigationTest {
    @Test
    fun `external queue returns an untouched detail route directly to home`() {
        assertTrue(shouldReturnHomeForExternalQueue("now-playing", "now-playing"))
        assertTrue(shouldReturnHomeForExternalQueue("album/{id}", "album/{id}"))
    }

    @Test
    fun `home and user navigation are never redirected`() {
        assertFalse(shouldReturnHomeForExternalQueue("home", "home"))
        assertFalse(shouldReturnHomeForExternalQueue("now-playing", "album/{id}"))
        assertFalse(shouldReturnHomeForExternalQueue(null, "now-playing"))
    }
}
