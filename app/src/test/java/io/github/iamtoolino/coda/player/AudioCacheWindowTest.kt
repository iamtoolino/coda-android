package io.github.iamtoolino.coda.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioCacheWindowTest {
    @Test
    fun `empty queue produces an empty cache window`() {
        assertEquals(emptyList<Int>(), audioCacheWindowIndices(itemCount = 0, currentIndex = 0))
    }

    @Test
    fun `new queue starts with current track and next three in order`() {
        assertEquals(
            listOf(0, 1, 2, 3),
            audioCacheWindowIndices(itemCount = 12, currentIndex = 0),
        )
    }

    @Test
    fun `cache window follows current item and stops at queue end`() {
        assertEquals(
            listOf(8, 9),
            audioCacheWindowIndices(itemCount = 10, currentIndex = 8),
        )
    }

    @Test
    fun `unset current index uses first queued track`() {
        assertEquals(
            listOf(0, 1, 2),
            audioCacheWindowIndices(itemCount = 3, currentIndex = -1),
        )
    }
}
