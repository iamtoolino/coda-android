package dev.coda.core.playback

import org.junit.Assert.*
import org.junit.Test

/** The volume during a reopen seek fades out within 30 ms, holds, and every way out restores the exact level. */
class SeekSilenceTest {
    private class Rig(start: Float = 1f) {
        var now = 0L
        var volume = start
        val applied = mutableListOf<Pair<Long, Float>>()
        private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val silence = SeekSilence({ volume }, { volume = it; applied += now to it; log += "v$it" }) { delay, block ->
            val task = (now + delay) to block
            tasks += task
            ({ tasks.remove(task) })
        }
        /** When the faded pause actually paused the player, as an entry in [applied]'s order. */
        val log = mutableListOf<String>()
        var pauses = 0
        val pause: () -> Unit = { pauses++; log += "pause@$now" }
        /** Runs each due task at its own time (the log then shows when a seek ran). */
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = tasks.filter { it.first <= end }.minByOrNull { it.first } ?: break
                tasks.remove(next); now = maxOf(now, next.first); next.second()
            }
            now = end
        }
        val pendingTasks get() = tasks.size
    }

    @Test fun itFadesToSilenceWithinThirtyMillisecondsAndHolds() {
        val rig = Rig()
        rig.silence.begin()
        assertTrue(rig.silence.active)
        rig.advance(SeekSilence.RAMP_MS)
        assertTrue("≤ 30 ms", SeekSilence.RAMP_MS <= 30)
        assertEquals(0f, rig.volume)
        val steps = rig.applied.map { it.second }
        assertEquals("a falling ramp, not a jump", steps.sortedDescending(), steps)
        assertTrue(steps.size >= 3 && steps.first() < 1f && steps.first() > 0f)
        rig.advance(1_000)
        assertEquals("held", 0f, rig.volume)
    }

    @Test fun everyExitRestoresTheExactPreviousLevel() {
        for (exit in SeekSilence.Exit.entries) for (stopAfterMs in listOf(0L, 10, 1_000)) {
            val rig = Rig(start = 0.73f) // Whatever the player had; ReplayGain itself is applied in the PCM, not here.
            rig.silence.begin()
            rig.advance(stopAfterMs)
            rig.silence.end(exit)
            assertEquals("$exit after $stopAfterMs ms", 0.73f, rig.volume)
            assertFalse(rig.silence.active)
            rig.advance(1_000)
            assertEquals("$exit: no ramp step after the restore", 0.73f, rig.volume)
            assertEquals(0, rig.pendingTasks)
        }
    }

    @Test fun aSupersedingReopenKeepsTheSilenceAndTheSavedLevel() {
        val rig = Rig()
        rig.silence.begin()
        rig.advance(100)
        rig.silence.begin() // A later seek that also reopens.
        rig.advance(100)
        assertEquals(0f, rig.volume)
        rig.silence.end(SeekSilence.Exit.REOPENED)
        assertEquals("not the silence it was holding", 1f, rig.volume)
    }

    @Test fun pauseRestoresAndAResumeWhileStillWaitingFadesAgain() {
        val rig = Rig()
        rig.silence.begin(); rig.advance(50)
        rig.silence.end(SeekSilence.Exit.PAUSED)
        assertEquals(1f, rig.volume)
        rig.silence.begin(); rig.advance(SeekSilence.RAMP_MS) // play() while the reopen still waits
        assertEquals(0f, rig.volume)
        rig.silence.end(SeekSilence.Exit.REOPENED)
        assertEquals(1f, rig.volume)
    }

    @Test fun anEndWithoutASilenceTouchesNothing() {
        val rig = Rig()
        for (exit in SeekSilence.Exit.entries) rig.silence.end(exit)
        assertTrue("local seeks, loads, pauses: no volume change", rig.applied.isEmpty())
    }

    @Test fun releaseStopsTheRampWithoutTouchingThePlayer() {
        val rig = Rig()
        rig.silence.begin()
        val before = rig.applied.size
        rig.silence.release()
        rig.advance(1_000)
        assertEquals(before, rig.applied.size)
        assertFalse(rig.silence.active)
    }
    // The pause and resume fades share this one volume state machine.

    @Test fun aPauseFadesOutOverAboutAHundredFiftyMillisecondsThenPausesAndRestores() {
        val rig = Rig(start = 0.73f)
        rig.silence.fadeOut(rig.pause)
        assertTrue(rig.silence.pausing)
        rig.advance(SeekSilence.FADE_MS - 1)
        assertEquals("still playing during the fade", 0, rig.pauses)
        assertTrue("halfway down", rig.volume in 0.001f..0.72f)
        rig.advance(1)
        assertEquals(1, rig.pauses)
        assertTrue("~150 ms", SeekSilence.FADE_MS in 120L..180L)
        val steps = rig.applied.dropLast(1).map { it.second }
        assertEquals("a falling ramp, not a cut", steps.sortedDescending(), steps)
        assertTrue("several steps: $steps", steps.size >= 10 && steps.last() == 0f)
        assertEquals("silent, paused, then exactly the previous level", listOf("v0.0", "pause@150", "v0.73"), rig.log.takeLast(3))
        assertFalse(rig.silence.active || rig.silence.pausing)
        rig.advance(1_000)
        assertEquals(0.73f, rig.volume); assertEquals(0, rig.pendingTasks)
    }

    @Test fun aResumeStartsSilentAndRampsUpToTheExactLevel() {
        val rig = Rig(start = 0.73f)
        rig.silence.fadeIn()
        assertEquals("starts at silence", 0f, rig.volume)
        rig.advance(SeekSilence.FADE_MS - 1)
        assertTrue(rig.volume in 0.01f..0.72f)
        rig.advance(1)
        val steps = rig.applied.map { it.second }
        assertEquals("a rising ramp", steps.sorted(), steps)
        assertEquals(0.73f, rig.volume)
        assertFalse(rig.silence.active)
        assertEquals(0, rig.pendingTasks)
    }

    @Test fun playDuringTheFadeOutCancelsThePauseAndRampsBackUp() {
        val rig = Rig(start = 0.73f)
        rig.silence.fadeOut(rig.pause); rig.advance(70)
        val low = rig.volume
        rig.silence.fadeIn()
        assertEquals("from where the fade was, not from silence", low, rig.volume)
        rig.advance(1_000)
        assertEquals(0, rig.pauses)
        assertEquals(0.73f, rig.volume)
        assertFalse(rig.silence.active)
    }

    @Test fun aPauseDuringTheFadeInFadesOutFromThereAndRestoresTheExactLevel() {
        val rig = Rig(start = 0.73f)
        rig.silence.fadeIn(); rig.advance(70)
        val mid = rig.volume
        rig.silence.fadeOut(rig.pause)
        rig.advance(10)
        assertTrue("down from $mid, not from the saved level", rig.volume < mid)
        rig.advance(1_000)
        assertEquals(1, rig.pauses)
        assertEquals(0.73f, rig.volume)
    }

    @Test fun everyExitDuringAFadeRestoresTheExactLevelWithoutPausing() {
        for (out in listOf(true, false)) for (exit in SeekSilence.Exit.entries) for (afterMs in listOf(0L, 70)) {
            val rig = Rig(start = 0.73f)
            if (out) rig.silence.fadeOut(rig.pause) else rig.silence.fadeIn()
            rig.advance(afterMs)
            rig.silence.end(exit)
            assertEquals("$exit after $afterMs ms", 0.73f, rig.volume)
            rig.advance(1_000)
            assertEquals("$exit: no step after the restore", 0.73f, rig.volume)
            assertEquals("$exit: the caller decides the player state", 0, rig.pauses)
            assertEquals(0, rig.pendingTasks)
            assertFalse(rig.silence.active || rig.silence.pausing)
        }
    }

    @Test fun completingTheFadeOutPausesAtOnceAndRestores() { // a seek or an automatic track change during it
        val rig = Rig(start = 0.73f)
        rig.silence.fadeOut(rig.pause); rig.advance(70)
        rig.silence.completePause()
        assertEquals(listOf("pause@70", "v0.73"), rig.log.takeLast(2))
        rig.advance(1_000)
        assertEquals(1, rig.pauses); assertEquals(0.73f, rig.volume); assertEquals(0, rig.pendingTasks)
        rig.silence.completePause()
        assertEquals("nothing pending: nothing happens", 1, rig.pauses)
    }

    @Test fun aPauseDuringTheSeekSilencePausesAtOnce() {
        val rig = Rig(start = 0.73f)
        rig.silence.begin(); rig.advance(50)
        rig.silence.fadeOut(rig.pause)
        assertEquals(listOf("pause@50", "v0.73"), rig.log.takeLast(2))
        assertFalse(rig.silence.active)
    }

    @Test fun aResumeDuringTheSeekSilenceStaysSilent() {
        val rig = Rig(start = 0.73f)
        rig.silence.begin(); rig.advance(50)
        rig.silence.fadeIn()
        rig.advance(1_000)
        assertEquals(0f, rig.volume)
        rig.silence.end(SeekSilence.Exit.REOPENED)
        assertEquals(0.73f, rig.volume)
    }

    @Test fun aReopenSeekDuringTheFadeInGoesSilentAndKeepsTheSavedLevel() {
        val rig = Rig(start = 0.73f)
        rig.silence.fadeIn(); rig.advance(70)
        val mid = rig.volume
        rig.silence.begin()
        assertTrue("no jump up", rig.applied.last().second <= mid)
        rig.advance(1_000)
        assertEquals(0f, rig.volume)
        rig.silence.end(SeekSilence.Exit.REOPENED)
        assertEquals("the level from before the fade-in", 0.73f, rig.volume)
    }

    @Test fun releaseDuringAFadeStopsItWithoutPausingOrTouchingThePlayer() {
        val rig = Rig()
        rig.silence.fadeOut(rig.pause); rig.advance(30)
        val before = rig.applied.size
        rig.silence.release()
        rig.advance(1_000)
        assertEquals(before, rig.applied.size); assertEquals(0, rig.pauses)
        assertFalse(rig.silence.active || rig.silence.pausing)
    }

    // Every seek while audio plays fades out, seeks once silent, holds, and fades in when the new position plays.

    private fun Rig.seek(target: Long) = silence.seek(target) { log += "seek$target@$now" }
    private val Rig.seeks get() = log.filter { it.startsWith("seek") }

    @Test fun aSeekWhilePlayingFadesOutSeeksOnceSilentHoldsAndFadesInWhenItPlays() {
        val rig = Rig(start = 0.73f)
        rig.seek(5_000)
        assertEquals("the target shows at once", 5_000L, rig.silence.pendingSeekMs)
        rig.advance(SeekSilence.RAMP_MS)
        assertEquals("silent within 30 ms", 0f, rig.volume); assertTrue(SeekSilence.RAMP_MS <= 30)
        assertTrue("not yet seeked", rig.seeks.isEmpty())
        val out = rig.applied.map { it.second }
        assertEquals("a falling ramp", out.sortedDescending(), out); assertTrue(out.size >= 3 && out.first() in 0.01f..0.72f)
        rig.advance(SeekSilence.SEEK_AT_MS - SeekSilence.RAMP_MS)
        assertEquals("seeked once, while silent", listOf("seek5000@${SeekSilence.SEEK_AT_MS}"), rig.seeks)
        assertTrue(SeekSilence.SEEK_AT_MS <= 30)
        assertNull(rig.silence.pendingSeekMs)
        rig.advance(1_000)
        assertEquals("held until the new position plays", 0f, rig.volume)
        val before = rig.applied.size
        rig.silence.landed()
        rig.advance(SeekSilence.SEEK_FADE_IN_MS - 1)
        assertTrue("fading in", rig.volume in 0.01f..0.72f)
        rig.advance(1)
        val rise = rig.applied.drop(before).map { it.second }
        assertEquals("a rising ramp: $rise", rise.sorted(), rise); assertTrue(rise.size >= 3)
        assertEquals("exactly the saved level", 0.73f, rig.volume)
        assertTrue("shorter than the resume fade", SeekSilence.SEEK_FADE_IN_MS < SeekSilence.FADE_MS)
        assertFalse(rig.silence.active); assertEquals(0, rig.pendingTasks)
        rig.silence.landed(); assertEquals("landed again: nothing", 0.73f, rig.volume)
    }

    @Test fun aBurstOfSeeksRunsOnlyTheLastOneWithOneFade() { // a drag ends, or several presses on a remote
        val rig = Rig(start = 0.73f)
        rig.seek(1_000); rig.advance(10); rig.seek(2_000); rig.advance(10); rig.seek(3_000)
        assertEquals(3_000L, rig.silence.pendingSeekMs)
        rig.advance(1_000)
        assertEquals("one seek, to the final position", listOf("seek3000@${SeekSilence.SEEK_AT_MS}"), rig.seeks)
        val out = rig.applied.map { it.second }
        assertEquals("one falling ramp, no dip and rise: $out", out.sortedDescending(), out)
        rig.silence.landed(); rig.advance(1_000)
        assertEquals(0.73f, rig.volume)
    }

    @Test fun aSeekWhileHeldRunsAtOnceAndASeekDuringTheFadeInFadesDownFromThere() {
        val rig = Rig(start = 0.73f)
        rig.seek(1_000); rig.advance(100)
        rig.seek(2_000) // held: silent already
        assertEquals(listOf("seek1000@30", "seek2000@100"), rig.seeks)
        rig.silence.landed(); rig.advance(30)
        val mid = rig.volume
        rig.seek(3_000)
        assertTrue("no jump up from $mid", rig.applied.last().second <= mid)
        rig.advance(1_000)
        assertEquals(listOf("seek1000@30", "seek2000@100", "seek3000@160"), rig.seeks)
        assertEquals(0f, rig.volume)
        rig.silence.landed(); rig.advance(1_000)
        assertEquals("the level from before the first seek", 0.73f, rig.volume)
    }

    @Test fun aSeekDuringTheReopenSilenceRunsAtOnceAndFadesInWhenItPlays() { // also the reopen itself
        val rig = Rig(start = 0.73f)
        rig.silence.begin(); rig.advance(200)
        rig.seek(9_000)
        assertEquals(listOf("seek9000@200"), rig.seeks)
        rig.advance(1_000); assertEquals(0f, rig.volume)
        rig.silence.landed(); rig.advance(SeekSilence.SEEK_FADE_IN_MS)
        assertEquals(0.73f, rig.volume); assertFalse(rig.silence.active)
    }

    @Test fun aReopenSeekDuringTheFadeOutDropsTheLocalSeek() {
        val rig = Rig(start = 0.73f)
        rig.seek(1_000); rig.advance(10)
        rig.silence.begin()
        rig.advance(1_000)
        assertTrue("superseded: ${rig.seeks}", rig.seeks.isEmpty()); assertNull(rig.silence.pendingSeekMs)
        assertEquals(0f, rig.volume)
        rig.silence.end(SeekSilence.Exit.REOPENED)
        assertEquals(0.73f, rig.volume)
    }

    @Test fun everyExitDuringASeekFadeRestoresTheExactLevelAndDropsTheSeek() {
        for (exit in SeekSilence.Exit.entries) for (afterMs in listOf(0L, 10, 100, -30)) {
            val rig = Rig(start = 0.73f)
            rig.seek(4_000)
            if (afterMs >= 0) rig.advance(afterMs) else { rig.advance(100); rig.silence.landed(); rig.advance(-afterMs) }
            val seeked = rig.seeks.size
            rig.silence.end(exit)
            assertEquals("$exit after $afterMs ms", 0.73f, rig.volume)
            rig.advance(1_000)
            assertEquals("$exit: no step and no seek after the restore", 0.73f, rig.volume)
            assertEquals(seeked, rig.seeks.size); assertNull(rig.silence.pendingSeekMs)
            assertEquals(0, rig.pendingTasks); assertFalse(rig.silence.active)
            rig.silence.landed(); assertEquals(0.73f, rig.volume)
        }
    }

    @Test fun aPauseDuringTheSeekFadeSeeksThenPausesAtOnceAndRestores() {
        for (afterMs in listOf(10L, 100)) {
            val rig = Rig(start = 0.73f)
            rig.seek(4_000); rig.advance(afterMs)
            rig.silence.fadeOut(rig.pause)
            assertEquals("$afterMs: the seek is kept, then the pause, then the level",
                listOf(if (afterMs < 30) "seek4000@$afterMs" else "seek4000@30", "pause@$afterMs", "v0.73"), rig.log.filter { !it.startsWith("v") || it == "v0.73" }.takeLast(3))
            rig.advance(1_000); assertEquals(0.73f, rig.volume); assertEquals(0, rig.pendingTasks)
        }
    }

    @Test fun completeSeekRunsAPendingSeekNowAndHolds() { // an immediate pause (headphones unplugged)
        val rig = Rig(start = 0.73f)
        rig.seek(4_000); rig.advance(10)
        rig.silence.completeSeek()
        assertEquals(listOf("seek4000@10"), rig.seeks)
        assertEquals(0f, rig.volume)
        rig.advance(1_000); assertEquals(1, rig.seeks.size)
        rig.silence.end(SeekSilence.Exit.PAUSED)
        assertEquals(0.73f, rig.volume)
        rig.silence.completeSeek(); assertEquals("nothing pending", 1, rig.seeks.size)
    }

    @Test fun releaseDuringASeekFadeDropsItWithoutTouchingThePlayer() {
        val rig = Rig()
        rig.seek(4_000); rig.advance(10)
        val before = rig.applied.size
        rig.silence.release(); rig.advance(1_000)
        assertEquals(before, rig.applied.size); assertTrue(rig.seeks.isEmpty())
        assertFalse(rig.silence.active)
    }
}
