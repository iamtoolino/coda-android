package io.github.iamtoolino.coda.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedQueueSyncPolicyTest {
    @Test
    fun `restored paused queue does not claim server queue`() {
        assertEquals(
            QueueSyncDecision(active = false),
            queueSyncDecision(false, hasPlaybackIntent = false, mediaItemTransition = false),
        )
    }

    @Test
    fun `first local play and track transition request saves`() {
        assertEquals(
            QueueSyncDecision(active = true, requestSave = true),
            queueSyncDecision(false, hasPlaybackIntent = true, mediaItemTransition = false),
        )
        assertEquals(
            QueueSyncDecision(active = true, requestSave = true),
            queueSyncDecision(true, hasPlaybackIntent = true, mediaItemTransition = true),
        )
    }

    @Test
    fun `queue mutation alone while playing does not immediately save`() {
        assertEquals(
            QueueSyncDecision(active = true),
            queueSyncDecision(true, hasPlaybackIntent = true, mediaItemTransition = false),
        )
    }

    @Test
    fun `pause cancels pending saves`() {
        assertEquals(
            QueueSyncDecision(active = false, cancelPending = true),
            queueSyncDecision(true, hasPlaybackIntent = false, mediaItemTransition = false),
        )
    }
}
