package dev.coda.core.playback

import org.junit.Assert.*
import org.junit.Test

/** When the engine settles a start. */
class PendingStartTest {
    @Test fun nothingSettlesBeforeALoadOrSeek() {
        val start = PendingStart()
        assertFalse(start.take(ready = true, playing = true))
    }

    @Test fun aPausedReadyKeepsItArmedUntilTheFirstPlayingReady() {
        val start = PendingStart()
        start.arm() // load (paused restore), replaceCurrentSource or seekTo
        assertFalse(start.take(ready = false, playing = false)) // buffering
        assertFalse(start.take(ready = true, playing = false))  // ready but paused
        assertTrue(start.armed)
        assertTrue(start.take(ready = true, playing = true))    // play pressed
        assertFalse(start.armed)
        assertFalse(start.take(ready = true, playing = true))   // settles only once
    }

    @Test fun stopClearsIt() {
        val start = PendingStart()
        start.arm(); start.clear()
        assertFalse(start.take(ready = true, playing = true))
    }

    @Test fun aNewLoadOrSeekReArmsIt() {
        val start = PendingStart()
        start.arm(); assertTrue(start.take(ready = true, playing = true))
        start.arm(); assertTrue(start.armed)
    }
}
