package io.github.iamtoolino.coda.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumPresentationTest {
    @Test
    fun `compact album runtimes omit seconds`() {
        assertEquals("49m", formatCompactCollectionDuration(49 * 60 + 26))
        assertEquals("1h 5m", formatCompactCollectionDuration(65 * 60 + 54))
    }

    @Test
    fun `compact album runtimes handle boundaries`() {
        assertEquals("<1m", formatCompactCollectionDuration(59))
        assertEquals("1h", formatCompactCollectionDuration(60 * 60))
    }
}
