package dev.coda.android.playback

import dev.coda.core.playback.*
import org.junit.Assert.*
import org.junit.Test

class AudioFocusPolicyTest {
    private class Fixture {
        val engine = FakeAudioEngine()
        val controller = PlaybackController(engine)
        var grant = true
        var requests = 0
        var abandons = 0
        val policy = AudioFocusPolicy(controller, { requests++; grant }, { abandons++ })
        init { controller.playbackGate = policy }
        fun load() = controller.replaceQueue(listOf(QueueEntry("synthetic")))
    }

    @Test fun denialCannotStartInitialLoadOrResume() {
        val f = Fixture()
        f.grant = false
        f.load()
        assertFalse(f.engine.loadCalls.single().playWhenReady)
        assertFalse(f.controller.playWhenReady)
        assertFalse(f.policy.focused)
        f.controller.play()
        assertEquals(0, f.engine.playCalls)
        assertFalse(f.controller.playWhenReady)
        assertEquals(2, f.requests)
        f.grant = true
        f.controller.play()
        assertEquals(1, f.engine.playCalls)
        assertTrue(f.controller.playWhenReady)
        f.controller.pause()
        f.grant = false
        f.controller.play()
        assertEquals(1, f.engine.playCalls)
        assertFalse(f.controller.playWhenReady)
    }

    @Test fun explicitPauseOrStopAfterTransientLossCancelsResumeIncludingQueuedGain() {
        for (stop in listOf(false, true)) {
            val f = Fixture()
            f.load()
            f.policy.transientLoss()
            f.policy.playbackInactive() // Same callback as the service's state collector.
            assertFalse(f.policy.focused)
            assertFalse(f.controller.playWhenReady)
            assertEquals(0, f.abandons)
            if (stop) f.controller.stop() else f.controller.pause()
            assertEquals(1, f.abandons)
            f.policy.gain()
            assertFalse(f.controller.playWhenReady)
            assertFalse(f.policy.focused)
            assertEquals(0, f.engine.playCalls)
        }
    }

    @Test fun transientLossResumesOnlyOwnedRequestAndManualPlayReacquires() {
        val f = Fixture()
        f.load()
        f.policy.transientLoss()
        f.policy.transientLoss() // A repeated callback must not erase the original play intent.
        f.policy.gain()
        assertTrue(f.policy.focused)
        assertTrue(f.controller.playWhenReady)
        assertEquals(1, f.requests)
        assertEquals(1, f.engine.playCalls)
        f.policy.transientLoss()
        f.grant = false
        f.controller.play()
        assertEquals(2, f.requests)
        assertFalse(f.policy.focused)
        assertFalse(f.controller.playWhenReady)
        f.policy.gain()
        assertEquals(1, f.engine.playCalls)
        assertFalse(f.controller.playWhenReady)
    }

    @Test fun permanentLossAndNaturalCompletionAbandonFocus() {
        val f = Fixture()
        f.load()
        f.policy.permanentLoss()
        f.policy.gain()
        assertFalse(f.controller.playWhenReady)
        assertFalse(f.policy.focused)
        assertEquals(1, f.abandons)
        f.controller.play()
        f.engine.finish()
        f.policy.playbackInactive()
        assertFalse(f.policy.focused)
        assertEquals(2, f.abandons)
    }
}
