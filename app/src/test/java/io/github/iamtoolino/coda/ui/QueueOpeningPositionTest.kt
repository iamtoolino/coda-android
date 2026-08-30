package io.github.iamtoolino.coda.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueOpeningPositionTest {
    @Test
    fun `playing track opens below one preceding queue row`() {
        // The LazyColumn header occupies index zero, so the queue index itself leaves the previous
        // song above the playing row while still guaranteeing that the playing row is visible.
        assertEquals(9, queueOpeningFirstVisibleItemIndex(currentIndex = 9, queueSize = 20))
    }

    @Test
    fun `first and unavailable playing tracks keep the queue at its start`() {
        assertEquals(0, queueOpeningFirstVisibleItemIndex(currentIndex = 0, queueSize = 20))
        assertEquals(0, queueOpeningFirstVisibleItemIndex(currentIndex = -1, queueSize = 20))
        assertEquals(0, queueOpeningFirstVisibleItemIndex(currentIndex = 20, queueSize = 20))
        assertEquals(0, queueOpeningFirstVisibleItemIndex(currentIndex = 0, queueSize = 0))
    }
}
