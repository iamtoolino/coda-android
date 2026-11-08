package dev.coda.android.ui.library

import org.junit.Assert.*
import org.junit.Test

/** The fast scroller's proportional mapping between the list position and the thumb fraction. */
class Ds13ScrollerTest {
    /** [count] visible items of [size] px from item [first], the first one's top at [top] (container coordinates). */
    private fun visible(first: Int, count: Int, size: Int, top: Int, perRow: Int = 1) =
        (0 until count).map { i -> VisibleItem(first + i, top + i / perRow * size, size, null) }

    @Test fun endsAreExactAndTheMiddleIsProportional() {
        // 1,000 rows of 100 px, 2,000 px viewport, 200 px padding before, 100 after: 98,300 px of scroll.
        val atTop = ScrollGeometry(visible(0, 21, 100, 200), 1000, 2000, 200, 100)
        assertEquals(0f, atTop.fraction(atStart = true, atEnd = false), 0f)
        assertEquals(1f, atTop.fraction(atStart = false, atEnd = true), 0f)
        // Item 500 at the content top (200 px): 50,000 px scrolled.
        val middle = ScrollGeometry(visible(498, 22, 100, 0), 1000, 2000, 200, 100)
        assertEquals(50_000f / 98_300f, middle.fraction(atStart = false, atEnd = false), 1e-4f)
    }

    @Test fun seekIsTheInverseOfTheFraction() {
        val geometry = ScrollGeometry(visible(300, 22, 100, -50), 1000, 2000, 200, 100)
        for (fraction in listOf(0f, .1f, .25f, .5f, .9f, 1f)) {
            val item = geometry.itemAt(fraction)
            // The list puts [item] at the content top: its fraction there is the requested one, within one item.
            val landed = ScrollGeometry(visible(item - 2, 22, 100, 0), 1000, 2000, 200, 100)
            assertEquals("fraction $fraction", fraction, landed.fraction(atStart = item == 0, atEnd = false), 100f / 98_300f + 1e-4f)
        }
        assertEquals(0, geometry.itemAt(-1f)); assertEquals(999, ScrollGeometry(visible(0, 1, 100, 0), 1000, 100, 0, 0).itemAt(2f))
    }

    @Test fun gridRowsCountOncePerTile() {
        // 2 tiles per 250 px row: 125 px per item, 600 items → 75,000 px of content.
        val grid = ScrollGeometry(visible(100, 20, 250, 0, perRow = 2), 600, 2500, 0, 0)
        assertEquals(100 * 125f / (75_000f - 2500f), grid.fraction(atStart = false, atEnd = false), 1e-4f)
        assertEquals(290, grid.itemAt(.5f))
    }

    @Test fun oneGestureMapsFractionsMonotonically() {
        // The geometry is read once per drag (after the fling stopped): mixed 40 px headers and 250 px rows cannot reverse it.
        val mixed = listOf(VisibleItem(10, 0, 40, null), VisibleItem(11, 40, 250, null), VisibleItem(12, 40, 250, null), VisibleItem(13, 290, 40, null),
            VisibleItem(14, 330, 250, null), VisibleItem(15, 330, 250, null))
        val geometry = ScrollGeometry(mixed, 800, 2400, 300, 200)
        val items = (0..1000).map { geometry.itemAt(it / 1000f) }
        assertEquals(items.sorted(), items)
        assertEquals(0, items.first()); assertTrue(items.last() <= 799)
    }

    @Test fun anEmptyLayoutStaysAtTheTop() {
        val empty = ScrollGeometry(emptyList(), 0, 2000, 0, 0)
        assertEquals(0f, empty.fraction(atStart = false, atEnd = false), 0f)
        assertEquals(0, empty.itemAt(.7f))
    }
}
