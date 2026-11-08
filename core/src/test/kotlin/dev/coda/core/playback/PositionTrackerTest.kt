package dev.coda.core.playback

import org.junit.Assert.*
import org.junit.Test

/** Defects C (backward steps) and A (position clamped to a duration estimate). */
class PositionTrackerTest {
    /** Media3 on emulator-5556: the audio position holds for ~150 ms and then catches up. */
    @Test fun aLateSampleNeverStepsTheReportedPositionBackwards() {
        val tracker = PositionTracker()
        tracker.sample(1_000, 0, true, null, "a")
        assertEquals(1_100L, tracker.positionMs(100)) // extrapolated 100 ms
        tracker.sample(1_040, 100, true, null, "a")   // the audio is only at 1040: raw would be 1040
        assertEquals(1_100L, tracker.positionMs(100))
        // It does not stand still waiting for the audio either: it keeps (almost) the wall clock rate.
        assertEquals(1_130L, tracker.positionMs(130))
        assertEquals(1_169L, tracker.positionMs(170))
    }

    @Test fun positionIsMonotonicOverAWholeSegmentOfJitteredSamples() {
        val tracker = PositionTracker()
        val random = java.util.Random(7)
        var audio = 0L
        var last = -1L
        for (tick in 0 until 200) {
            val now = tick * 100L
            // The audio clock advances 100 ms per tick, but is read with +-120 ms of jitter.
            audio += 100
            tracker.sample((audio + random.nextInt(241) - 120).coerceAtLeast(0), now, true, null, "a")
            for (frame in 0 until 6) {
                val shown = tracker.positionMs(now + frame * 16L)
                assertTrue("backwards at tick $tick: $last -> $shown", shown >= last)
                last = shown
            }
        }
    }

    @Test fun anExactSampleAddsNoLatency() {
        val tracker = PositionTracker()
        tracker.sample(5_000, 1_000, true, null, "a")
        assertEquals(5_000L, tracker.positionMs(1_000))
        assertEquals(5_250L, tracker.positionMs(1_250))
        tracker.sample(5_250, 1_250, true, null, "a")
        assertEquals(5_250L, tracker.positionMs(1_250))
    }

    @Test fun aSeekBackwardsIsShownImmediately() {
        val tracker = PositionTracker()
        tracker.sample(10_000, 0, true, null, "a")
        assertEquals(10_000L, tracker.positionMs(0))
        tracker.sample(2_000, 10, true, null, "a", rebase = true)
        assertEquals(2_000L, tracker.positionMs(10))
        assertEquals(2_050L, tracker.positionMs(60))
    }

    @Test fun anotherItemStartsAFreshSegment() {
        val tracker = PositionTracker()
        tracker.sample(20_000, 0, true, 20_300, "a")
        assertEquals(20_000L, tracker.positionMs(0))
        tracker.sample(40, 50, true, 180_000, "b") // gapless transition, no rebase flag needed
        assertEquals(40L, tracker.positionMs(50))
    }

    /** ExoPlayer's position went 2.0 s back once, with no seek, and came back. Not shown. */
    @Test fun aTransientTwoSecondRewindWithoutASeekIsNotShown() {
        val tracker = PositionTracker()
        tracker.sample(10_000, 0, true, null, "a", rebase = true)
        var last = tracker.positionMs(0)
        var now = 0L
        while (now < 6_000) {
            now += 10
            // The audio clock reads 2047 ms too low between 2.0 s and 3.5 s.
            val audio = 10_000 + now - if (now in 2_000..3_500) 2_047 else 0
            if (now % 100 == 0L) tracker.sample(audio, now, true, null, "a")
            val shown = tracker.positionMs(now)
            assertTrue("shown $shown after $last at $now", shown >= last && shown - last <= 11)
            last = shown
        }
        assertEquals(16_000.0, last.toDouble(), 40.0)
    }

    /** ... but a clock that stays 2 s behind is believed after 2 s (the audio really is there). */
    @Test fun aLastingBigDisagreementIsAcceptedAfterTwoSeconds() {
        val tracker = PositionTracker()
        tracker.sample(10_000, 0, true, null, "a", rebase = true)
        tracker.positionMs(0)
        var now = 0L
        var shown = 0L
        while (now < 5_000) {
            now += 10
            if (now % 100 == 0L) tracker.sample(10_000 + now - 3_000, now, true, null, "a")
            shown = tracker.positionMs(now)
        }
        assertEquals(12_000.0, shown.toDouble(), 40.0)
    }

    @Test fun aSeekBackwardsIsStillShownAtOnceWithTheRebase() {
        val tracker = PositionTracker()
        tracker.sample(30_000, 0, true, null, "a")
        assertEquals(30_000L, tracker.positionMs(0))
        tracker.sample(10_000, 100, true, null, "a", rebase = true)
        assertEquals(10_000L, tracker.positionMs(100))
    }

    @Test fun pausedPositionDoesNotAdvance() {
        val tracker = PositionTracker()
        tracker.sample(3_000, 0, false, null, "a")
        assertEquals(3_000L, tracker.positionMs(5_000))
    }

    @Test fun extrapolationStopsAtAKnownEndOfTrack() {
        val tracker = PositionTracker()
        tracker.sample(20_250, 0, true, 20_299, "a")
        assertEquals(20_299L, tracker.positionMs(500))
    }

    /** Header-less VBR MP3 (NPS-2 `short-vbr-noxing.mp3`): Media3 estimates 3488 ms for 20.3 s of audio. */
    @Test fun positionIsNotClampedToAnEstimatePlaybackHasPassed() {
        val tracker = PositionTracker()
        var shown = 0L
        for (tick in 0..200) {
            tracker.sample(tick * 100L, tick * 100L, true, 3_488, "a")
            shown = tracker.positionMs(tick * 100L + 50)
        }
        assertTrue("shown $shown", Math.abs(shown - 20_050L) <= 30)
    }

    @Test fun anEstimatePassedStaysIgnoredAfterASeekWithinTheItem() {
        val tracker = PositionTracker()
        tracker.sample(5_000, 0, true, 3_488, "a")
        tracker.sample(9_000, 10, true, 3_488, "a", rebase = true)
        assertEquals(9_000L, tracker.positionMs(10))
    }

    @Test fun theNextItemTrustsItsOwnDurationAgain() {
        val tracker = PositionTracker()
        tracker.sample(5_000, 0, true, 3_488, "a")
        tracker.sample(100, 10, true, 20_299, "b")
        tracker.sample(20_250, 20, true, 20_299, "b", rebase = true) // a seek near the end
        assertEquals(20_299L, tracker.positionMs(520))
    }

    /**
     * A MediaController (Media3) extrapolates with the wall clock from the position it was last sent and re-anchors on
     * the session's position every 3 s. This is its arithmetic, fed from a tracker whose player samples every 100 ms.
     * Returns the controller's step at each re-anchor (the extrapolated value subtracted from the new anchor).
     */
    private fun controllerAnchorSteps(audioAt: (Long) -> Long, seconds: Int, tracker: PositionTracker = PositionTracker()): List<Long> {
        var anchorValue = 0L
        var anchorAt = 0L
        val steps = mutableListOf<Long>()
        tracker.sample(0, 0, true, null, "a", rebase = true)
        var now = 0L
        while (now < seconds * 1_000L) {
            now += 10
            if (now % 100 == 0L) tracker.sample(audioAt(now), now, true, null, "a")
            if (now % 3_000 == 0L) {
                val extrapolated = anchorValue + (now - anchorAt)
                anchorValue = tracker.positionMs(now); anchorAt = now
                steps += anchorValue - extrapolated
            }
        }
        return steps
    }

    /** The audio output starts 300 ms after the player says it plays; the first re-anchor was 300 ms back. */
    @Test fun theOutputStartLatencyIsNotSnappedBackAtTheFirstControllerReanchor() {
        val steps = controllerAnchorSteps({ now -> (now - 300).coerceAtLeast(0) }, 60)
        assertTrue("anchor steps $steps", steps.all { it >= -35 })
        assertTrue("anchor steps $steps", steps.all { it <= 35 })
        // ... and it does follow the audio: after a minute the lead over the audio is gone.
        val tracker = PositionTracker()
        controllerAnchorSteps({ now -> (now - 300).coerceAtLeast(0) }, 60, tracker)
        assertEquals(59_700.0, tracker.positionMs(60_000).toDouble(), 40.0)
    }

    /** The Media3 audio position holds for ~170 ms and jumps ahead again every ~330 ms (emulator-5556 series of NPS-2c). */
    @Test fun lumpyAudioPositionDoesNotMakeTheControllerReanchorStepsVisible() {
        val lumpy = { now: Long -> (now - 300).coerceAtLeast(0) / 330 * 330 + minOf((now - 300).coerceAtLeast(0) % 330, 160) }
        val steps = controllerAnchorSteps(lumpy, 60)
        assertTrue("anchor steps $steps", steps.all { Math.abs(it) <= 35 })
    }

    @Test fun theShownClockNeverLeavesTheWallClockRateByMoreThanTheSlew() {
        val tracker = PositionTracker()
        tracker.sample(0, 0, true, null, "a", rebase = true)
        var previous = tracker.positionMs(0)
        var now = 0L
        val random = java.util.Random(11)
        while (now < 30_000) {
            now += 10
            if (now % 100 == 0L) tracker.sample((now - 250 + random.nextInt(301) - 150).coerceAtLeast(0), now, true, null, "a")
            val shown = tracker.positionMs(now)
            assertTrue("step ${shown - previous} at $now", shown - previous in 9..11)
            previous = shown
        }
    }

    @Test fun aPausedPlayerDoesNotFreeRun() {
        val tracker = PositionTracker()
        tracker.sample(4_000, 0, true, null, "a")
        assertEquals(4_100L, tracker.positionMs(100))
        tracker.sample(4_100, 100, false, null, "a")
        assertEquals(4_100L, tracker.positionMs(5_000))
        tracker.sample(4_100, 5_000, true, null, "a") // resumes where it stopped, without the time it was paused
        assertEquals(4_150L, tracker.positionMs(5_050))
    }

    /** A clock that stood still while the audio played (a flicker of isPlaying) is behind it; it catches up five times faster than it gives way. */
    @Test fun aClockBehindTheAudioCatchesUpAtFivePercent() {
        val tracker = PositionTracker()
        tracker.sample(0, 0, true, null, "a", rebase = true)
        var now = 0L
        var shown = 0L
        while (now < 10_000) {
            now += 10
            if (now % 100 == 0L) tracker.sample(now + 250, now, true, null, "a") // the audio is 250 ms ahead
            shown = tracker.positionMs(now)
        }
        assertEquals(10_250.0, shown.toDouble(), 30.0)
    }

    /** Review of NPS-2c: a stopped player (paused, buffering) shows a position that stands still, whichever side the clock is on. */
    private fun standsStill(offset: Long) {
        val tracker = PositionTracker()
        tracker.sample(0, 0, true, null, "a", rebase = true)
        var now = 0L
        while (now < 2_700) { now += 10; if (now % 100 == 0L) tracker.sample(now - offset, now, true, null, "a"); tracker.positionMs(now) }
        tracker.sample(now - offset, now, false, null, "a") // paused / buffering
        val shown = tracker.positionMs(now)
        while (now < 7_700) { now += 10; if (now % 100 == 0L) tracker.sample(2_700 - offset, now, false, null, "a"); assertEquals("at $now", shown, tracker.positionMs(now)) }
        // It plays again: the offset is corrected from here, at the usual rates, without a step.
        tracker.sample(2_700 - offset, now, true, null, "a")
        assertTrue(Math.abs(tracker.positionMs(now) - shown) <= 1)
        assertTrue(Math.abs(tracker.positionMs(now + 100) - shown - 100) <= 6)
    }

    @Test fun aPausedPlayerStandsStillWhileTheClockLeadsTheAudio() = standsStill(300)
    @Test fun aPausedPlayerStandsStillWhileTheClockTrailsTheAudio() = standsStill(-300)

    @Test fun aSeekWhilePausedIsStillShown() {
        val tracker = PositionTracker()
        tracker.sample(5_000, 0, false, null, "a", rebase = true)
        assertEquals(5_000L, tracker.positionMs(100))
        tracker.sample(9_000, 200, false, null, "a", rebase = true)
        assertEquals(9_000L, tracker.positionMs(300))
    }

    /** Catch-up through the controller model: audio 250 ms ahead of the clock is closed by forward steps of at most 5 % x 3 s. */
    @Test fun catchUpReachesTheControllerAsForwardStepsOfAtMostFivePercent() {
        val steps = controllerAnchorSteps({ now -> now + 250 }, 30)
        assertTrue("anchor steps $steps", steps.all { it in -35..155 })
        assertTrue("catch-up never reached the controller: $steps", steps.first() > 100)
        assertEquals(0L, steps.last())
    }

    /**
     * A handoff loads an unseekable stream (Navidrome's fresh Opus transcode) at 8 s; the audio starts at 0. Before
     * the fix the ready sample was taken for a clock glitch and 8 s and more were shown for GLITCH_MS (2 s) while the
     * audio played from its beginning; settling the start believes it at once.
     */
    @Test fun aStartTheSourceCouldNotHonourIsShownAsSoonAsThePlayerSettles() {
        val glitch = PositionTracker()
        glitch.sample(8_000, 0, false, 20_000, "a", rebase = true) // load at the handed-off position
        glitch.sample(0, 300, true, 20_000, "a")                   // ready: playing from 0 (an ordinary sample)
        assertEquals(8_000L, glitch.positionMs(300))                // the old path: the requested start runs on
        assertEquals(8_500L, glitch.positionMs(800))

        val tracker = PositionTracker()
        tracker.sample(8_000, 0, false, 20_000, "a", rebase = true)
        tracker.settle(0, 300, true, 20_000, "a")
        assertEquals(0L, tracker.positionMs(300))
        assertEquals(500L, tracker.positionMs(800))
    }

    /** A start that was honoured (within JUMP_MS) settles as an ordinary sample: no step, so the smoothness holds. */
    @Test fun aHonouredStartSettlesWithoutAStep() {
        val tracker = PositionTracker()
        tracker.sample(8_000, 0, false, 20_000, "a", rebase = true)
        assertEquals(8_000L, tracker.positionMs(200))
        tracker.settle(7_850, 200, true, 20_000, "a") // ready a little behind (seek to a sync frame, output latency)
        val first = tracker.positionMs(200)
        assertEquals(8_000L, first)                   // what was shown stands: no step back
        assertTrue(tracker.positionMs(300) >= first + 90)
    }

    /** Settling before anything was reported is a plain sample. */
    @Test fun settlingAFreshTrackerTakesTheSample() {
        val tracker = PositionTracker()
        tracker.settle(1_200, 0, false, null, "a")
        assertEquals(1_200L, tracker.positionMs(0))
    }

    /**
     * A cold start restores the queue paused at its saved point (10 s) on an unseekable stream. The paused
     * ready sample (0) is an ordinary sample (the engine does not settle before it plays), so the saved point stays shown
     * and saved; once it plays, the settle shows where the audio really is.
     */
    @Test fun aPausedRestoreKeepsTheSavedPositionUntilItPlays() {
        val start = PendingStart()
        val tracker = PositionTracker()
        tracker.sample(10_000, 0, false, 20_000, "a", rebase = true); start.arm() // paused load at the resume point
        assertFalse(start.take(ready = true, playing = false))
        tracker.sample(0, 300, false, 20_000, "a")                                // paused ready at 0
        assertEquals(10_000L, tracker.positionMs(300))
        assertEquals(10_000L, tracker.positionMs(60_000))                         // what a save at exit captures
        assertTrue(start.take(ready = true, playing = true))                      // play pressed: audio from 0
        tracker.settle(0, 60_000, true, 20_000, "a")
        assertEquals(0L, tracker.positionMs(60_000))
        assertEquals(500L, tracker.positionMs(60_500))
    }
}
