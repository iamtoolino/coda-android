package dev.coda.android.ui.queue

import dev.coda.core.library.QueueReorder
import dev.coda.core.library.TrackListPresentation
import dev.coda.core.playback.QueueEntry
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** The boundary a held album lands on, as a pure function of the finger and the laid-out cells. */
class QueueAlbumDropTest {
    // A(3) B(3) C(5, expanded) S(standalone) D(3); runs under 3 tracks are standalone rows: 10 cells, every cell 100 px high.
    private val entries = listOf("A" to 3, "B" to 3, "C" to 5, "S" to 1, "D" to 3).flatMap { (album, n) ->
        List(n) { QueueEntry("song", albumId = album, album = "Album $album", title = "$album track ${it + 1}") }
    }
    private val expanded = entries.filter { it.albumId == "C" }.map { it.id }.toSet()
    private val cells = queueCells(entries, expanded)
    private val starts = TrackListPresentation.queueGroups(entries).map { it.startIndex }.toSet() + entries.size

    /** Cells [from]..[to] laid out from viewport y [top] on, 100 px each. */
    private fun visible(from: Int = 0, to: Int = cells.lastIndex, top: Int = 0) =
        (from..to).map { CellSpan(it, cells[it].key, top + (it - from) * 100, top + (it - from + 1) * 100) }
    private fun pick(finger: Float, visible: List<CellSpan> = visible(), viewport: Int = 1000) =
        pickAlbumDrop(entries, cells, visible, viewport, finger)!!

    @Test fun cellsAreTheExpectedLayout() {
        assertEquals(10, cells.size)
        assertEquals(listOf(0, 3, 6, 11, 12), TrackListPresentation.queueGroups(entries).map { it.startIndex })
    }

    @Test fun nearestBoundaryOnScreen() {
        // Boundaries: A 0 @0, B 3 @100, C 6 @200, S 11 @800, D 12 @900, end 15 @1000.
        assertEquals(AlbumDrop(3, 100f, 0, "Album A", 3 until 6), pick(140f))
        assertEquals(6, pick(160f).boundary)
        assertEquals(12, pick(870f).boundary)
    }

    @Test fun overAnExpandedAlbumTheNearerEndWinsByPixels() {
        // C spans 200..800: its top in the upper half, its end (S's start) in the lower half; never a row inside it.
        assertEquals(6, pick(260f).boundary)
        assertEquals(6, pick(499f).boundary)
        assertEquals(11, pick(501f).boundary)
        assertEquals(11, pick(760f).boundary)
        assertEquals(6 until 11, pick(450f).block)
        assertEquals(6 until 11, pick(650f).block)
    }

    @Test fun noTargetInsideAnAlbum() {
        for (finger in -300..1300 step 7) {
            val drop = pick(finger.toFloat())
            assertTrue("finger $finger -> ${drop.boundary}", drop.boundary in starts)
        }
        // Scrolled and partly visible too.
        for (finger in -300..700 step 7) assertTrue(pick(finger.toFloat(), visible(4, 7, -40), 350).boundary in starts)
    }

    @Test fun firstAndLastPositions() {
        assertEquals(AlbumDrop(0, 0f, 0, "Album A", 0 until 3), pick(20f))
        assertEquals(0, pick(-400f).boundary)
        val end = pick(990f)
        assertEquals(entries.size, end.boundary)
        assertEquals(1000f, end.y)
        assertEquals(0, end.edge)
        assertEquals(entries.size, pick(5000f).boundary)
    }

    @Test fun offScreenBoundaryAbovePinsToTheTopEdge() {
        // C's rows 2..5 fill a 350 px viewport; C's header (6) is 200 px above it, S (11) 50 px below it.
        val drop = pick(50f, visible(4, 7), 350)
        assertEquals(6, drop.boundary)
        assertEquals(-1, drop.edge)
        assertEquals(0f, drop.y)
        assertEquals("Album C", drop.neighbour)
        assertEquals("Above “Album C”", drop.chip)
        assertEquals("Album lands above Album C, off screen", drop.spoken)
        assertEquals(6 until 11, drop.block)
    }

    @Test fun offScreenBoundaryBelowPinsToTheBottomEdge() {
        val drop = pick(300f, visible(4, 7), 350)
        assertEquals(11, drop.boundary)
        assertEquals(1, drop.edge)
        assertEquals(350f, drop.y)
        assertEquals("Below “Album C”", drop.chip)
        assertEquals("Album lands below Album C, off screen", drop.spoken)
    }

    @Test fun offScreenPositionsUseTheMeanVisibleHeight() {
        // Only S and D on screen (two 50 px cells): C's header is 6 cells x 50 px above S.
        val drop = pickAlbumDrop(entries, cells, listOf(CellSpan(8, cells[8].key, 0, 50), CellSpan(9, cells[9].key, 50, 100)), 100, -200f)!!
        assertEquals(6, drop.boundary)
        assertEquals(-1, drop.edge)
        // Below the last visible cell: the queue end is right after D.
        assertEquals(entries.size, pickAlbumDrop(entries, cells, listOf(CellSpan(0, cells[0].key, 0, 50), CellSpan(1, cells[1].key, 50, 100)), 100, 2000f)!!.boundary)
    }

    @Test fun standaloneTrackIsNamedByItsTitle() {
        // S (8) and D (9) are off screen below: the queue end is nearest and lands below D; D's start lands below S.
        val visible = visible(0, 7)
        assertEquals("Album D", pickAlbumDrop(entries, cells, visible, 800, 1100f)!!.neighbour)
        assertEquals("S track 1", pickAlbumDrop(entries, cells, visible, 800, 880f)!!.neighbour)
    }

    @Test fun nothingToPickWithoutLayout() {
        assertNull(pickAlbumDrop(entries, cells, emptyList(), 1000, 10f))
        assertNull(pickAlbumDrop(emptyList(), emptyList(), visible(), 1000, 10f))
    }

    @Test fun boundaryMapsToTheReorderDestination() {
        val holdA = QueueReorder(entries, 0..2, true)
        assertEquals(0, holdA.atBoundary(0).destination)  // its own start: no move
        assertEquals(0, holdA.atBoundary(3).destination)  // its own end: no move
        assertEquals(listOf("B", "B", "B", "A", "A", "A", "C"), holdA.atBoundary(6).preview.take(7).map { it.albumId })
        assertEquals(entries.size - 3, holdA.atBoundary(entries.size).destination)
        val holdD = QueueReorder(entries, 12..14, true)
        assertEquals(listOf("D", "D", "D", "A"), holdD.atBoundary(0).preview.take(4).map { it.albumId })
        assertEquals(12, holdD.atBoundary(entries.size).destination) // its own end: no move
        val holdC = QueueReorder(entries, 6..10, true)
        assertEquals(listOf("S", "C"), holdC.atBoundary(12).preview.drop(6).take(2).map { it.albumId })
    }

    @Test fun repeatedAlbumRunsKeepSeparateBoundaries() {
        val aba = listOf("a", "a", "a", "b", "b", "b", "a", "a", "a").map { QueueEntry("song", albumId = it, album = it) }
        val abaCells = queueCells(aba)
        val drop = pickAlbumDrop(aba, abaCells, abaCells.indices.map { CellSpan(it, abaCells[it].key, it * 100, it * 100 + 100) }, 300, 190f)!!
        assertEquals(6, drop.boundary)
        assertEquals(listOf("a", "a", "a", "a", "a", "a", "b", "b", "b"), QueueReorder(aba, 3..5, true).atBoundary(9).preview.map { it.albumId })
    }

    // ---- Review P1: inconsistent input (a queue change during the drag) never crashes, it gives no target ----------

    @Test fun replacedQueueWithNewOccurrencesGivesNoTarget() {
        // Same songs and albums, new occurrence ids (another controller replaced the queue): no cell matches.
        val replaced = entries.map { it.copy(id = java.util.UUID.randomUUID()) }
        for (finger in listOf(-500f, 0f, 450f, 999f, 5000f)) assertNull(pickAlbumDrop(replaced, cells, visible(), 1000, finger))
        // The reverse: the new queue's cells against the old layout keys.
        assertNull(pickAlbumDrop(replaced, queueCells(replaced, expanded), visible(), 1000, 450f))
    }

    @Test fun removedEntryGivesNoTarget() {
        assertNull(pickAlbumDrop(entries.filterNot { it.albumId == "S" }, cells, visible(), 1000, 450f))
        assertNull(pickAlbumDrop(entries.drop(1), cells, visible(), 1000, 450f))
    }

    @Test fun cellsOfAnotherExpansionGiveNoTargetAgainstThisLayout() {
        // Layout still shows C expanded, cells already collapsed (or the reverse): keys at the visible indices differ.
        assertNull(pickAlbumDrop(entries, queueCells(entries), visible(), 1000, 450f))
    }

    @Test fun malformedLayoutGivesNoTarget() {
        assertNull(pickAlbumDrop(entries, cells, listOf(CellSpan(10, "x", 0, 100)), 1000, 50f))           // index past the cells
        assertNull(pickAlbumDrop(entries, cells, listOf(CellSpan(0, cells[0].key, 0, 100), CellSpan(2, cells[2].key, 100, 200)), 1000, 50f)) // gap
        assertNull(pickAlbumDrop(entries, cells, listOf(CellSpan(0, cells[1].key, 0, 100)), 1000, 50f))   // wrong key
    }

    // ---- Review P3: unequal header and row heights, against geometry computed from the full layout ----------------

    /** A long mixed queue: collapsed and expanded albums, singles; [header]/[row] px heights, scrolled by [scroll]. */
    private inner class Mixed(val header: Int, val row: Int) {
        val queue = listOf("A" to 3, "B" to 12, "S1" to 1, "C" to 4, "D" to 3, "E" to 14, "S2" to 1, "F" to 3).flatMap { (album, n) ->
            List(n) { QueueEntry("song", albumId = album, album = "Album $album", title = "$album ${it + 1}") }
        }
        val open = queue.filter { it.albumId in setOf("B", "C", "E") }.map { it.id }.toSet()
        val cells = queueCells(queue, open)
        val tops = cells.runningFold(0) { y, cell -> y + if (cell.group != null) header else row }  // full layout, tops[cells.size] = end
        val groups = TrackListPresentation.queueGroups(queue)
        /** True boundary positions in the viewport: the first cell of each group, and the end. */
        fun truth(scroll: Int): List<Pair<Int, Int>> = groups.map { g ->
            g.startIndex to tops[cells.indexOfFirst { it.entry.id == g.entries.first().id }] - scroll
        } + (queue.size to tops.last() - scroll)
        fun visible(scroll: Int, viewport: Int) = cells.indices.filter { tops[it + 1] - scroll > 0 && tops[it] - scroll < viewport }
            .map { CellSpan(it, cells[it].key, tops[it] - scroll, tops[it + 1] - scroll) }
    }

    @Test fun mixedHeightsPickTheTrueNearestBoundaryIncludingOffScreenEdges() {
        for ((header, row) in listOf(147 to 137, 300 to 100, 100 to 260)) {   // 56/52 dp at 2.625x, and two exaggerated mixes
            val m = Mixed(header, row)
            val viewport = 1700
            var offAbove = 0; var offBelow = 0
            for (scroll in 0..(m.tops.last() - viewport) step 37) {
                val visible = m.visible(scroll, viewport)
                val types = visible.map { m.cells[it.index].group != null }.toSet()
                for (finger in 60..(viewport - 60) step 23) {
                    val drop = pickAlbumDrop(m.queue, m.cells, visible, viewport, finger.toFloat())!!
                    val truth = m.truth(scroll)
                    val best = truth.minOf { abs(it.second - finger) }
                    val nearest = truth.filter { abs(it.second - finger) == best }.map { it.first }
                    val at = "header $header row $row scroll $scroll finger $finger"
                    if (types.size == 2) {
                        // Both cell types on screen: the per-type estimate is the true layout, so the pick is exact.
                        assertTrue("$at: ${drop.boundary} not in $nearest", drop.boundary in nearest)
                        val y = truth.first { it.first == drop.boundary }.second
                        assertEquals(at, if (y < 0) -1 else if (y > viewport) 1 else 0, drop.edge)
                        if (drop.edge < 0) offAbove++
                        if (drop.edge > 0) offBelow++
                    } else {
                        // One type only (inside a long album): the target is still one of the two boundaries around the finger.
                        val around = truth.zipWithNext().first { (a, b) -> finger >= a.second && finger < b.second }
                        assertTrue("$at: ${drop.boundary} not around the finger $around", drop.boundary == around.first.first || drop.boundary == around.second.first)
                    }
                }
            }
            assertTrue("header $header row $row: exact off-screen picks above $offAbove / below $offBelow", offAbove > 0 && offBelow > 0)
        }
    }

    @Test fun mixedHeightsExactNearTheMidpointOfAnOffScreenAlbum() {
        // E (14 rows) runs off both edges; D's header is visible above it. Independently: E starts at tops[E header],
        // ends at tops[S2]. The pick flips exactly at the true midpoint, which a single mean height would misplace.
        val m = Mixed(147, 137)
        val eHeader = m.cells.indexOfFirst { it.group?.entries?.first()?.albumId == "E" }
        val eStart = m.tops[eHeader]; val eEnd = m.tops[eHeader + 15]
        val viewport = 1500
        val scroll = eStart - 40                               // E's header visible at the top, its end far below
        val visible = m.visible(scroll, viewport)
        assertTrue(visible.any { m.cells[it.index].group != null } && visible.any { m.cells[it.index].group == null })
        val mid = (eStart + eEnd) / 2f - scroll
        val eStartIndex = m.groups.first { it.entries.first().albumId == "E" }.startIndex
        assertEquals(eStartIndex, pickAlbumDrop(m.queue, m.cells, visible, viewport, mid - 2)!!.boundary)
        val below = pickAlbumDrop(m.queue, m.cells, visible, viewport, mid + 2)!!
        assertEquals(eStartIndex + 14, below.boundary)
        assertEquals(1, below.edge)
        // The top edge: scroll E's header off screen with D visible above none; only E rows plus S2/F below.
        val scrollUp = eEnd - viewport + 200                     // E's end and S2/F visible near the bottom, E's header far above
        val visibleUp = m.visible(scrollUp, viewport)
        val midUp = (eStart + eEnd) / 2f - scrollUp
        val above = pickAlbumDrop(m.queue, m.cells, visibleUp, viewport, midUp - 2)!!
        assertEquals(eStartIndex, above.boundary)
        assertEquals(-1, above.edge)
        assertEquals(eStartIndex + 14, pickAlbumDrop(m.queue, m.cells, visibleUp, viewport, midUp + 2)!!.boundary)
    }
}
