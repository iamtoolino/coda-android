package dev.coda.core.library

import dev.coda.core.playback.QueueEntry
import org.junit.Assert.*
import org.junit.Test

class QueueReorderTest {
    private val entries = listOf("a", "a", "b", "b", "b", "c", "c").mapIndexed { i, album -> QueueEntry("$i", albumId = album) }
    @Test fun singleMoveUsesIndexAfterRemovalInBothDirections() {
        assertEquals(listOf("0", "2", "3", "1", "4", "5", "6"), QueueReorder(entries, 1..1, false).at(3).preview.map { it.songId })
        assertEquals(listOf("0", "4", "1", "2", "3", "5", "6"), QueueReorder(entries, 4..4, false).at(1).preview.map { it.songId })
    }
    @Test fun albumMoveOnlyAllowsRemainingGroupBoundaries() {
        val drag = QueueReorder(entries, 0..1, true)
        assertEquals(0, drag.at(1).destination)
        assertEquals(3, drag.at(2).destination)
        assertEquals(listOf("2", "3", "4", "0", "1", "5", "6"), drag.at(3).preview.map { it.songId })
        assertEquals(listOf("0", "1", "5", "6", "2", "3", "4"), QueueReorder(entries, 2..4, true).at(4).preview.map { it.songId })
    }
    @Test fun trackDroppedInsideAlbumSplitsRunAndMatchingNeighboursRegroup() {
        val split = QueueReorder(entries, 0..0, false).at(3).preview
        assertEquals(listOf(1, 1, 1, 1, 1, 1, 1), TrackListPresentation.queueGroups(split).map { it.entries.size })
        val restored = QueueReorder(split, 3..3, false).at(0).preview
        assertEquals(listOf(1, 1, 3, 1, 1), TrackListPresentation.queueGroups(restored).map { it.entries.size })
    }
    @Test fun returningAlbumToItsOriginalBoundaryDoesNotMergeAwayItsSlot() {
        val queue = listOf("a", "a", "a", "b", "b", "b", "a", "a", "a").mapIndexed { i, album -> QueueEntry("$i", albumId = album) }
        val drag = QueueReorder(queue, 3..5, true)
        assertEquals(3, drag.at(3).destination)
        assertEquals(queue, drag.at(3).preview)
        assertEquals(0, drag.at(0).destination)
        assertEquals(6, drag.at(6).destination)
    }
    @Test fun cancelRestoresExactOccurrencesWithoutMutatingOriginal() {
        val drag = QueueReorder(entries, 0..1, true).at(5)
        assertNotEquals(entries, drag.preview)
        assertEquals(entries, drag.cancel())
        assertEquals((0..6).map { "$it" }, entries.map { it.songId })
    }
    @Test fun controllerCommitsSingleAndRangeAndLeavesCurrentOccurrenceLoaded() {
        val engine = dev.coda.core.playback.FakeAudioEngine()
        val controller = dev.coda.core.playback.PlaybackController(engine)
        controller.replaceQueue(entries, startAt = 3)
        val loads = engine.loadCalls.size
        fun release(drag: QueueReorder) {
            controller.move(drag.moving.map { it.id }, drag.remaining.getOrNull(drag.destination)?.id)
            assertEquals(drag.preview, controller.queue)
            assertEquals(entries[3].id, controller.currentEntry?.id)
            assertEquals(loads, engine.loadCalls.size)
        }
        release(QueueReorder(controller.queue, 0..0, false).at(3))
        release(QueueReorder(controller.queue, 5..6, true).at(0))
        val beforeCancel = controller.queue
        QueueReorder(beforeCancel, 0..1, true).at(5).cancel()
        assertEquals(beforeCancel, controller.queue)
    }

}
