package io.github.iamtoolino.coda.player

import io.github.iamtoolino.coda.data.PlayQueue
import io.github.iamtoolino.coda.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueHandoffTest {
    private val song = Song(id = "song-1", title = "First")
    private val ownClientName = "Coda on Galaxy S25"

    @Test
    fun `empty server queue does nothing`() {
        assertEquals(
            HandoffDisposition.NONE,
            handoffDisposition(
                null,
                coldStart = true,
                localQueueEmpty = true,
                ownClientName = ownClientName,
            ),
        )
        assertEquals(
            HandoffDisposition.NONE,
            handoffDisposition(
                PlayQueue(),
                coldStart = true,
                localQueueEmpty = true,
                ownClientName = ownClientName,
            ),
        )
    }

    @Test
    fun `own queue is restored paused only on cold start with no local queue`() {
        val queue = PlayQueue(
            changedBy = ownClientName,
            entry = listOf(song),
        )

        assertEquals(
            HandoffDisposition.RESTORE_PAUSED,
            handoffDisposition(
                queue,
                coldStart = true,
                localQueueEmpty = true,
                ownClientName = ownClientName,
            ),
        )
        assertEquals(
            HandoffDisposition.NONE,
            handoffDisposition(
                queue,
                coldStart = false,
                localQueueEmpty = true,
                ownClientName = ownClientName,
            ),
        )
        assertEquals(
            HandoffDisposition.NONE,
            handoffDisposition(
                queue,
                coldStart = true,
                localQueueEmpty = false,
                ownClientName = ownClientName,
            ),
        )
    }

    @Test
    fun `legacy Android client name is recognized as our own queue`() {
        assertTrue(isAndroidQueue("Coda", ownClientName))
        assertTrue(isAndroidQueue("codaandroid", ownClientName))
        assertTrue(isAndroidQueue("coda on galaxy s25", ownClientName))
        assertTrue(isAndroidQueue("  Coda   on Galaxy S25  ", ownClientName))
        assertFalse(isAndroidQueue("Coda on Pixel 9", ownClientName))
        assertFalse(isAndroidQueue("CodaMac", ownClientName))
    }

    @Test
    fun `external queue is offered even when a local paused queue exists`() {
        val queue = PlayQueue(changedBy = "CodaMac", entry = listOf(song))

        assertEquals(
            HandoffDisposition.OFFER_EXTERNAL,
            handoffDisposition(
                queue,
                coldStart = false,
                localQueueEmpty = false,
                ownClientName = ownClientName,
            ),
        )
    }
}
