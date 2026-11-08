package dev.coda.android.ui.queue

import dev.coda.core.library.QueueReorder
import dev.coda.core.playback.QueueEntry
import org.junit.Assert.*
import org.junit.Test

class QueueCellsTest {
    private val entries = listOf("a", "a", "a", "b", "b", "b", "single").map { QueueEntry("song", albumId = it) }

    @Test fun defaultHidesAlbumTracksButKeepsHeadersAndSingletons() {
        val cells = queueCells(entries)
        assertEquals(listOf(entries[6].id), cells.filter { it.group == null }.map { it.entry.id })
        assertEquals(listOf(entries[0].id, entries[3].id), cells.mapNotNull { it.group?.id })
    }

    @Test fun onlyPlayingGroupStartsExpanded() {
        val expanded = initiallyExpandedQueueEntries(entries, entries[1].id)
        assertEquals(entries.take(3).map { it.id }.toSet(), expanded)
        assertEquals(entries.take(3).map { it.id } + entries[6].id,
            queueCells(entries, expanded).filter { it.group == null }.map { it.entry.id })
        assertTrue(initiallyExpandedQueueEntries(entries, entries[6].id).isEmpty())
        assertTrue(initiallyExpandedQueueEntries(entries, null).isEmpty())
    }

    @Test fun expansionUsesRunOccurrenceRatherThanAlbumId() {
        val repeated = entries + entries.take(3).map { it.copy(id = java.util.UUID.randomUUID()) }
        val visible = queueCells(repeated, setOf(entries[0].id)).filter { it.group == null }.map { it.entry.id }
        assertEquals(entries.take(3).map { it.id } + entries[6].id, visible)
    }

    @Test fun albumPickupLeavesEveryCellInPlace() {
        // No slot and no fold while an album is held, wherever its target is; its rows stay expanded.
        val expanded = setOf(entries[0].id, entries[3].id)
        val before = queueCells(entries, expanded)
        for (target in 0..entries.size) {
            assertEquals(before, queueDragCells(QueueReorder(entries, 3..5, true).at(target), expanded))
        }
    }

    @Test fun movingFirstTrackDoesNotCollapseTheRestOfAnExpandedAlbum() {
        val expanded = entries.take(3).map { it.id }.toSet()
        val reordered = entries.take(3) + entries[0].copy(id = java.util.UUID.randomUUID()) + entries.drop(3)
        val moved = reordered.toMutableList().apply { add(removeAt(0)) }
        assertTrue(queueCells(moved, expanded).any { it.group == null && it.entry.id == entries[1].id })
    }
}
