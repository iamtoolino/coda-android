package dev.coda.android.ui.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** The mini player swipe's direction convention, drag mapping and settle outcomes (the animated path runs on the device). */
@OptIn(ExperimentalCoroutinesApi::class)
class MiniSwipeTest {
    private fun swipe(width: Float = 300f, current: Int = 2, next: Int = 3, previous: Int = 1) =
        MiniSwipe().apply { this.width = width; begin(current, next, previous, "id-$current") }

    @Test fun towardTheStartEdgeIsNext() {
        assertTrue(swipesToNext(-80f, rtl = false))
        assertFalse(swipesToNext(80f, rtl = false))
        assertTrue(swipesToNext(80f, rtl = true))
        assertFalse(swipesToNext(-80f, rtl = true))
    }

    @Test fun contentFollowsTheFingerAndStopsOneWidthAway() {
        val s = swipe()
        s.drag(-120f, rtl = false); assertEquals(-120f, s.offset, 0f)
        s.drag(90f, rtl = false); assertEquals(90f, s.offset, 0f)
        s.drag(-900f, rtl = false); assertEquals(-300f, s.offset, 0f)
    }

    @Test fun dragTowardAMissingNeighbourIsResisted() {
        val last = swipe(next = NoTrack)
        last.drag(-100f, rtl = false); assertEquals(-100f * MiniSwipe.EdgeResistance, last.offset, 0f)
        last.drag(100f, rtl = false); assertEquals(100f, last.offset, 0f) // the previous track exists
        val rtlLast = swipe(next = NoTrack)
        rtlLast.drag(100f, rtl = true); assertEquals(100f * MiniSwipe.EdgeResistance, rtlLast.offset, 0f)
    }

    @Test fun commitSkipsTheNeighbourTheDragPulledIn() = runTest {
        for ((dx, rtl, expectNext) in listOf(Triple(-80f, false, true), Triple(80f, false, false), Triple(80f, true, true), Triple(-80f, true, false))) {
            val s = swipe(); s.drag(dx, rtl)
            val skips = mutableListOf<String>()
            var asked = NoTrack
            s.settle(true, dx, rtl, reduceMotion = true, stillCurrent = { true }, seek = { skips += if (expectNext) "next" else "previous" }, reached = { asked = it; true })
            assertEquals("dx=$dx rtl=$rtl", listOf(if (expectNext) "next" else "previous"), skips)
            assertEquals(if (expectNext) 3 else 1, asked)
            assertFalse(s.active); assertEquals(0f, s.offset, 0f)
        }
    }

    @Test fun releaseBelowTheThresholdOrAtTheEndOfTheQueueNeverSkips() = runTest {
        val short = swipe(); short.drag(-40f, false)
        var skips = 0
        short.settle(false, -40f, false, true, { true }, { skips++ }, { true })
        assertEquals(0, skips); assertFalse(short.active); assertEquals(0f, short.offset, 0f)
        val end = swipe(next = NoTrack); end.drag(-200f, false)
        end.settle(true, -200f, false, true, { true }, { skips++ }, { true })
        assertEquals("no next track: nothing to skip to", 0, skips); assertFalse(end.active)
    }

    @Test fun aCommittedSwipeWaitsForThePlayerButNotForever() = runTest {
        val s = swipe(); s.drag(-80f, false)
        s.settle(true, -80f, false, true, { true }, {}, { false })
        assertEquals(MiniSwipe.ArrivalTimeoutMs, currentTime)
        assertFalse(s.active)
    }

    @Test fun reducedMotionJumpsAndHoldingEndsOnLetGo() = runTest {
        val s = swipe(); assertTrue(s.holding && s.active)
        s.drag(-80f, false); s.letGo(); assertFalse(s.holding)
        s.settle(false, -80f, false, reduceMotion = true, stillCurrent = { true }, seek = {}, reached = { true })
        assertEquals(0L, currentTime)
        assertEquals(0f, s.offset, 0f)
    }

    /** The playing track changed while the finger was down; a skip would be relative to the new one, so it springs back. */
    @Test fun commitIsDroppedWhenThePinnedTrackIsNoLongerPlaying() = runTest {
        val s = swipe(); s.drag(-120f, false)
        var skips = 0
        s.settle(true, -120f, false, true, stillCurrent = { false }, seek = { skips++ }, reached = { true })
        assertEquals("no skip from a track that is no longer playing", 0, skips)
        assertEquals("and no wait for the player", 0L, currentTime)
        assertFalse(s.active); assertEquals(0f, s.offset, 0f)
    }

    /** A new swipe may start while the last commit still waits for the player; it carries on without a pop and is not cleared by the old settle. */
    @Test fun aNewSwipeTakesOverAWaitingCommitWithoutPopOrDoubleSkip() = runTest {
        val s = swipe(); s.drag(-100f, false)
        var skips = 0
        val first = launch { s.settle(true, -100f, false, true, { true }, { skips++ }, { false }) }
        testScheduler.advanceTimeBy(100)
        assertEquals(1, skips); assertEquals("the neighbour sits where it slid to", -300f, s.offset, 0f)
        // The player has reported Track 4 (index 3): the next flick pins it where the neighbour is on screen.
        s.begin(3, 4, 2, "id-3")
        assertEquals("the new swipe starts from the neighbour's place, no pop", 0f, s.offset, 0f)
        assertTrue(s.holding)
        testScheduler.advanceUntilIdle()
        assertTrue(first.isCompleted)
        assertTrue("the old settle's end did not clear the new swipe", s.active); assertEquals(3, s.current)
        s.drag(-120f, false); assertEquals(-120f, s.offset, 0f)
        s.letGo()
        s.settle(true, -120f, false, true, { true }, { skips++ }, { true })
        assertEquals("one skip per gesture", 2, skips); assertFalse(s.active)
    }

    /** A swipe that starts before the player has reported the skip does not trust the stale state: it starts from the pinned track. */
    @Test fun aNewSwipeBeforeThePlayerReportsStartsFromThePinnedTrack() = runTest {
        val s = swipe(); s.drag(-100f, false)
        val first = launch { s.settle(true, -100f, false, true, { true }, {}, { false }) }
        testScheduler.advanceTimeBy(100)
        s.begin(2, 3, 1, "id-2") // still reports Track 3 (index 2)
        assertEquals(0f, s.offset, 0f)
        testScheduler.advanceUntilIdle(); assertTrue(first.isCompleted); assertTrue(s.active)
    }

    @Test fun rtlCommitShiftsTheNeighbourTheOtherWay() = runTest {
        val s = swipe(); s.drag(100f, true)
        val first = launch { s.settle(true, 100f, true, true, { true }, {}, { false }) }
        testScheduler.advanceTimeBy(100)
        assertEquals(300f, s.offset, 0f)
        s.begin(3, 4, 2, "id-3"); assertEquals(0f, s.offset, 0f)
        testScheduler.advanceUntilIdle()
    }

    /** A queue as (media id, queue entry id) per index, keyed as PlayerLayer keys the controller's items. */
    private fun occurrences(vararg items: Pair<String, String?>): (Int) -> String? = { i -> items.getOrNull(i)?.let { occurrenceKey(it.first, it.second) } }

    @Test fun aSameSongReplacementAtTheSameIndexMakesTheSwipeStale() {
        val before = occurrences("song/a" to "e0", "song/b" to "e1", "song/b" to "e2", "song/c" to "e3")
        val s = MiniSwipe().apply { begin(2, 3, 1, before(2), before(3), before(1)) }
        val pin = s.pin()
        assertTrue("nothing changed: the skip goes ahead", pin.stillCurrent(2, before, toNext = true))
        // Another controller replaced the queue: the same song at the same index, a new occurrence.
        val replaced = occurrences("song/a" to "f0", "song/b" to "f1", "song/b" to "f2", "song/c" to "f3")
        assertFalse(pin.stillCurrent(2, replaced, toNext = true))
        assertFalse(pin.stillCurrent(2, replaced, toNext = false))
    }

    @Test fun aReplacedNeighbourMakesTheSwipeStale() {
        val before = occurrences("song/a" to "e0", "song/b" to "e1", "song/c" to "e2")
        val pin = MiniSwipe().apply { begin(1, 2, 0, before(1), before(2), before(0)) }.pin()
        val newNext = occurrences("song/a" to "e0", "song/b" to "e1", "song/d" to "e9")
        assertFalse("the next track the finger pulled in is gone", pin.stillCurrent(1, newNext, toNext = true))
        assertTrue("the previous one is unchanged", pin.stillCurrent(1, newNext, toNext = false))
        assertFalse("the player moved on", pin.stillCurrent(2, before, toNext = true))
    }
}
