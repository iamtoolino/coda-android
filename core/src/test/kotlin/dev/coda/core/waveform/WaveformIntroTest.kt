package dev.coda.core.waveform

import org.junit.Assert.*
import org.junit.Test

class WaveformIntroTest {
    @Test fun everyNewTrackPlaysTheIntroOnceEvenWhenItsWaveformIsCached() {
        val intro = WaveformIntro()
        assertFalse(intro.hasPlayed("a"))
        assertTrue("first sight of a track", intro.begin("a"))
        assertTrue(intro.hasPlayed("a"))
        assertFalse("re-composing the same track does not replay", intro.begin("a"))
        assertFalse(intro.begin("a"))
        assertTrue("the next track is new, cached or not", intro.begin("b"))
        assertFalse(intro.hasPlayed("a"))
        assertTrue("returning to an earlier track is a new play", intro.begin("a"))
    }

    @Test fun quickSkippingYieldsOneIntroPerTrackAndTheLastOneWins() {
        val intro = WaveformIntro()
        val started = listOf("a", "b", "c", "d", "e").map { intro.begin(it) }
        assertEquals(List(5) { true }, started)
        assertTrue(intro.hasPlayed("e"))
        assertFalse(intro.hasPlayed("d"))
        assertFalse("a restarted effect for the current track continues instead of starting a second intro", intro.begin("e"))
    }

    @Test fun emptySongIdNeverStartsAnIntro() {
        val intro = WaveformIntro()
        assertFalse(intro.begin(""))
        assertFalse(intro.hasPlayed(""))
    }

    @Test fun barsStartFullyDrawnOnlyForATrackThatAlreadyPlayedItsIntro() {
        assertEquals(0f, WaveformIntro.initialArrival(waveformReady = true, hasPlayed = false), 0f) // New track, cached waveform: unfolds
        assertEquals(0f, WaveformIntro.initialArrival(waveformReady = false, hasPlayed = false), 0f)
        assertEquals(0f, WaveformIntro.initialArrival(waveformReady = false, hasPlayed = true), 0f)
        assertEquals(1f, WaveformIntro.initialArrival(waveformReady = true, hasPlayed = true), 0f) // reopened on the same track
    }

    @Test fun reducedMotionKeepsTheShortFadeAndNormalMotionTheUnfold() {
        assertEquals(800, WaveformIntro.durationMs(reduceMotion = false))
        assertEquals(150, WaveformIntro.durationMs(reduceMotion = true))
    }

    @Test fun wf2TheIntroWaitsWhileNowPlayingIsHiddenOrTheScreenIsOffAndPlaysOnceShown() {
        val intro = WaveformIntro()
        assertFalse("screen off or hidden: no intro, no frames", intro.beginIfShown("a", onScreen = false))
        assertFalse("not marked as played while hidden", intro.hasPlayed("a"))
        assertTrue("shown again on the new track: it unfolds", intro.beginIfShown("a", onScreen = true))
        assertFalse("back to the same track: no replay", intro.beginIfShown("a", onScreen = true))
        assertTrue(intro.beginIfShown("b", onScreen = true))
    }
}
