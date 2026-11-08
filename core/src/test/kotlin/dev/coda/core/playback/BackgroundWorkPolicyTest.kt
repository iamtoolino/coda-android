package dev.coda.core.playback

import org.junit.Assert.*
import org.junit.Test

class BackgroundWorkPolicyTest {
    @Test fun pausedWithoutUiPollsNothing() {
        assertNull(BackgroundWorkPolicy.positionSampleMs(playing = false, uiVisible = false))
        assertNull(BackgroundWorkPolicy.probePollMs(waiting = false))
        assertNull(BackgroundWorkPolicy.extrasPollMs(uiVisible = false))
        assertNull(BackgroundWorkPolicy.waveformPollMs(uiVisible = false))
        assertEquals(30_000L, BackgroundWorkPolicy.readAheadIdleMs(uiVisible = false))
    }

    @Test fun playingWithScreenOffKeepsOnlySlowWork() {
        assertEquals(1_000L, BackgroundWorkPolicy.positionSampleMs(playing = true, uiVisible = false))
        assertNull(BackgroundWorkPolicy.extrasPollMs(uiVisible = false))
        assertNull("BAT-2: no decode without the UI, playing or not", BackgroundWorkPolicy.waveformPollMs(uiVisible = false))
        assertEquals(30_000L, BackgroundWorkPolicy.readAheadIdleMs(uiVisible = false))
    }

    @Test fun visibleUiKeepsTheFormerCadence() {
        assertEquals(100L, BackgroundWorkPolicy.positionSampleMs(playing = true, uiVisible = true))
        assertEquals("paused with the UI: unchanged", 100L, BackgroundWorkPolicy.positionSampleMs(playing = false, uiVisible = true))
        assertEquals(250L, BackgroundWorkPolicy.extrasPollMs(uiVisible = true))
        assertEquals(500L, BackgroundWorkPolicy.waveformPollMs(uiVisible = true))
        assertNull("screen off while the UI is still started (lifecycle lag): no decode", BackgroundWorkPolicy.waveformPollMs(uiVisible = true, screenOn = false))
        assertNull(BackgroundWorkPolicy.waveformPollMs(uiVisible = false, screenOn = true))
        assertEquals(1_000L, BackgroundWorkPolicy.readAheadIdleMs(uiVisible = true))
        assertEquals(200L, BackgroundWorkPolicy.probePollMs(waiting = true))
    }

    @Test fun bat2QuietIsScreenOffAndPlayingOnly() {
        assertTrue(BackgroundWorkPolicy.quiet(screenOn = false, playing = true))
        assertFalse(BackgroundWorkPolicy.quiet(screenOn = true, playing = true))
        assertFalse(BackgroundWorkPolicy.quiet(screenOn = false, playing = false))
        assertFalse(BackgroundWorkPolicy.quiet(screenOn = true, playing = false))
        assertTrue(BackgroundWorkPolicy.defersScrobbles(screenOn = false, playing = true))
        assertFalse(BackgroundWorkPolicy.defersScrobbles(screenOn = false, playing = false))
        assertFalse(BackgroundWorkPolicy.periodicQueueSaves(screenOn = false))
        assertTrue(BackgroundWorkPolicy.periodicQueueSaves(screenOn = true))
        assertFalse(BackgroundWorkPolicy.sendsNowPlaying(screenOn = false))
        assertTrue(BackgroundWorkPolicy.sendsNowPlaying(screenOn = true))
    }

    @Test fun bat2CoverArtGating() {
        assertEquals("screen off, playing: current and next cover (no gap at a track change)", listOf(0, 1), BackgroundWorkPolicy.sessionCoverOffsets(screenOn = false, playing = true))
        assertEquals(listOf(0, 1, -1), BackgroundWorkPolicy.sessionCoverOffsets(screenOn = true, playing = true))
        assertEquals(listOf(0, 1, -1), BackgroundWorkPolicy.sessionCoverOffsets(screenOn = false, playing = false))
    }

    @Test fun wf2WaveformMotionOnlyWithTheUiStartedAndTheScreenOn() {
        assertTrue(BackgroundWorkPolicy.waveformMotion(uiVisible = true, screenOn = true))
        assertFalse("screen off while the UI is still started (lifecycle lag)", BackgroundWorkPolicy.waveformMotion(uiVisible = true, screenOn = false))
        assertFalse(BackgroundWorkPolicy.waveformMotion(uiVisible = false, screenOn = true))
        assertFalse(BackgroundWorkPolicy.waveformMotion(uiVisible = false, screenOn = false))
    }
}
