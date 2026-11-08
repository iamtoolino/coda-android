package dev.coda.core.waveform

import org.junit.Assert.*
import org.junit.Test

class SeekPlaceholderTest {
    @Test fun tideUnfoldsFromPlayheadAndFinishesEveryBar() {
        assertEquals(7f, SeekPlaceholder.height(0, 0f), 0f)
        assertTrue(SeekPlaceholder.unfold(15, 15f/43, .25f) > SeekPlaceholder.unfold(43, 15f/43, .25f))
        for (i in 0..43) {
            assertEquals(0f, SeekPlaceholder.unfold(i, .34f, 0f), 0f)
            assertEquals(1f, SeekPlaceholder.unfold(i, .34f, 1f), 0f)
            assertTrue(SeekPlaceholder.height(i, .6f) in 3.6f..10.4f)
        }
    }

    @Test fun wf2TheTideDriftsOnlyOnAVisibleNowPlayingWithTheScreenOn() {
        assertTrue(SeekPlaceholder.tideRuns(playing = true, waveformReady = false, reduceMotion = false, onScreen = true))
        assertFalse("screen off or Now Playing hidden: no frame loop", SeekPlaceholder.tideRuns(playing = true, waveformReady = false, reduceMotion = false, onScreen = false))
        assertFalse("waveform ready", SeekPlaceholder.tideRuns(playing = true, waveformReady = true, reduceMotion = false, onScreen = true))
        assertFalse("paused", SeekPlaceholder.tideRuns(playing = false, waveformReady = false, reduceMotion = false, onScreen = true))
        assertFalse("reduced motion: no tide", SeekPlaceholder.tideRuns(playing = true, waveformReady = false, reduceMotion = true, onScreen = true))
    }
}
