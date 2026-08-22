package io.github.iamtoolino.coda.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRestorationGateTest {
    @Test
    fun `old account cannot apply after disconnect wins race`() {
        val capturedGeneration = 4L

        assertFalse(
            shouldApplyPlaybackRestoration(
                restorationGeneration = capturedGeneration,
                currentGeneration = 5L,
                playerItemCount = 0,
                playWhenReady = false,
            ),
        )
    }

    @Test
    fun `current account applies only into an idle empty player`() {
        assertTrue(shouldApplyPlaybackRestoration(4L, 4L, 0, playWhenReady = false))
        assertFalse(shouldApplyPlaybackRestoration(4L, 4L, 1, playWhenReady = false))
        assertFalse(shouldApplyPlaybackRestoration(4L, 4L, 0, playWhenReady = true))
        assertFalse(shouldApplyPlaybackRestoration(4L, null, 0, playWhenReady = false))
    }

    @Test
    fun `service rejects missing and obsolete timeline generations`() {
        assertTrue(generationsBelongToCurrentAccount(emptyList(), currentGeneration = null))
        assertTrue(generationsBelongToCurrentAccount(listOf(5L, 5L), currentGeneration = 5L))
        assertFalse(generationsBelongToCurrentAccount(listOf(4L), currentGeneration = 5L))
        assertFalse(generationsBelongToCurrentAccount(listOf(null), currentGeneration = 5L))
        assertFalse(generationsBelongToCurrentAccount(listOf(5L), currentGeneration = null))
    }
}
