package dev.coda.android.ui.queue

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import dev.coda.core.playback.QueueEntry
import dev.coda.core.library.QueueReorder
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Items 2, 3 and the review's merged-group case: the album-drop reveal, driven with controlled state. */
class QueueRevealTest {
    private val ids = List(9) { UUID.randomUUID() }
    private fun entry(i: Int, album: String) = QueueEntry("s$i", id = ids[i], albumId = album)
    private fun bounded(body: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(5_000) { body() } }
    private fun publish(change: () -> Unit) { Snapshot.withMutableSnapshot(change); Snapshot.sendApplyNotifications() }

    @Test fun expectedOrderComesFromTheReorderPreview() {
        val entries = listOf("a", "a", "a", "b", "b", "b", "c", "c", "c").mapIndexed(::entry)
        val expected = QueueReorder(entries, 0..2, true).at(6).preview.map { it.id }
        assertEquals(listOf(3, 4, 5, 6, 7, 8, 0, 1, 2).map { ids[it] }, expected)
        val pending = PendingReveal(ids[0], entries.map { it.id }, expected)
        assertEquals(RevealOutcome.WAITING, revealOutcome(ids, pending))
        assertEquals(RevealOutcome.LANDED, revealOutcome(expected, pending))
        assertEquals(RevealOutcome.SUPERSEDED, revealOutcome(ids.reversed(), pending))
    }

    @Test fun partlyCutOffAtEitherEdgeNeedsAScroll() {
        assertFalse(partlyOutside(0, 120, 0, 1000))
        assertFalse(partlyOutside(880, 120, 0, 1000))
        assertTrue(partlyOutside(-60, 120, 0, 1000))
        assertTrue(partlyOutside(940, 120, 0, 1000))
        assertTrue(partlyOutside(-1, 120, 0, 1000))
        assertTrue(partlyOutside(881, 120, 0, 1000))
    }

    @Test fun movedRunRightAfterARunOfTheSameAlbumRevealsTheMergedGroupsHeader() {
        // The queue groups an album from 3 consecutive tracks: runs x(0..2), y(3..5), x(6..8). Moving the second x run up to follow
        // the first merges them into one group.
        val before = listOf("x", "x", "x", "y", "y", "y", "x", "x", "x").mapIndexed(::entry)
        val after = QueueReorder(before, 6..8, true).at(3).preview
        assertEquals(listOf(0, 1, 2, 6, 7, 8, 3, 4, 5).map { ids[it] }, after.map { it.id })
        // The moved entry (6) has no header of its own any more: the merged group's header is the first run's.
        assertEquals("header-${ids[0]}", revealCellKey(after, ids[6]))
        assertEquals("header-${ids[3]}", revealCellKey(after, ids[4]))
        assertNull(revealCellKey(after, UUID.randomUUID()))
        // A standalone track is its own cell.
        assertEquals(ids[0].toString(), revealCellKey(listOf(entry(0, "x"), entry(1, "y")), ids[0]))
    }

    private class Fixture(val move: PendingReveal) {
        val order = mutableStateOf(move.original)
        val ready = mutableStateOf(false)
        var scrolls = mutableListOf<Int>()
        var scrollStarted = CompletableDeferred<Unit>()
        var scrollCancelled = false
        val finishScroll = CompletableDeferred<Unit>()
        suspend fun run(timeoutMs: Long = 1_000) = revealMovedAlbum(move, { order.value }, { ready.value }, { 3 }, { true },
            { index -> scrolls += index; scrollStarted.complete(Unit); try { finishScroll.await() } catch (c: CancellationException) { scrollCancelled = true; throw c } }, timeoutMs)
    }
    private fun fixture(): Fixture {
        val moved = ids.drop(2) + ids.take(2)
        return Fixture(PendingReveal(ids[0], ids, moved))
    }
    private val landed get() = ids.drop(2) + ids.take(2)

    @Test fun landsThenWaitsForTheLayoutThenScrollsOnce() = bounded {
        val f = fixture()
        val job = async { f.run() }
        delay(50); publish { f.order.value = landed }
        delay(100); assertTrue("no scroll before the layout shows the new order", f.scrolls.isEmpty())
        publish { f.ready.value = true }
        f.scrollStarted.await(); f.finishScroll.complete(Unit)
        assertEquals(RevealResult.SCROLLED, job.await())
        assertEquals(listOf(3), f.scrolls)
    }

    @Test fun aChangeDuringTheLayoutWaitSupersedesAndNeverScrolls() = bounded {
        val f = fixture()
        val job = async { f.run() }
        delay(50); publish { f.order.value = landed }
        delay(50); publish { f.order.value = landed.reversed() }     // another reorder before the layout caught up
        delay(50); publish { f.ready.value = true }                   // a layout matching the NEWER order must not trigger the old reveal
        assertEquals(RevealResult.SUPERSEDED, job.await())
        assertTrue(f.scrolls.isEmpty())
    }

    @Test fun aChangeDuringTheScrollCancelsTheAnimation() = bounded {
        val f = fixture()
        val job = async { f.run() }
        publish { f.order.value = landed; f.ready.value = true }
        f.scrollStarted.await()
        publish { f.order.value = landed + UUID.randomUUID() }        // an unrelated later change while the animation runs
        assertEquals(RevealResult.SUPERSEDED, job.await())
        assertTrue("the animation was cancelled", f.scrollCancelled)
    }

    @Test fun aReplacementRequestCancelsTheFirstReveal() = bounded {
        val f = fixture()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { f.run() }
        delay(30)
        first.cancelAndJoin()                                        // LaunchedEffect(pending) restarts for the new request
        publish { f.order.value = landed; f.ready.value = true }
        delay(100)
        assertTrue("a cancelled reveal never scrolls", f.scrolls.isEmpty())
    }

    @Test fun aMoveThatNeverLandsTimesOutWithoutScrolling() = bounded {
        val f = fixture()
        assertEquals(RevealResult.TIMED_OUT, f.run(timeoutMs = 150))
        publish { f.order.value = landed; f.ready.value = true }       // too late: nothing is listening any more
        delay(100)
        assertTrue(f.scrolls.isEmpty())
    }

    @Test fun landingWithoutALayoutTimesOutToo() = bounded {
        val f = fixture()
        publish { f.order.value = landed }
        assertEquals(RevealResult.TIMED_OUT, f.run(timeoutMs = 150))
        assertTrue(f.scrolls.isEmpty())
    }

    @Test fun disposalWhileScrollingCancelsTheAnimation() = bounded {
        val f = fixture()
        val job = launch { f.run() }
        publish { f.order.value = landed; f.ready.value = true }
        f.scrollStarted.await()
        job.cancelAndJoin()                                          // the sheet leaves the composition
        assertTrue(f.scrollCancelled)
    }
}
