package dev.coda.core.cache

import org.junit.Assert.*
import org.junit.Test

/** A dragged custom time always lands where it was dragged; the other two follow so once <= again <= kept. */
class PushOrderedTest {
    private val top = Lifetime.entries.lastIndex
    private val all = (0..top)

    @Test fun everySliderToEveryStopFromEveryValidStateKeepsOrderAndLandsOnTarget() {
        for (k in all) for (a in 0..k) for (o in 0..a) for (slot in TimeSlot.entries) for (to in all) {
            val (nk, na, no) = pushOrdered(k, a, o, slot, to)
            assertTrue("$k,$a,$o $slot->$to gave $nk,$na,$no", no <= na && na <= nk)
            assertEquals(to, when (slot) { TimeSlot.KEPT -> nk; TimeSlot.HEARD_AGAIN -> na; TimeSlot.HEARD_ONCE -> no })
        }
    }

    @Test fun extremesFromAllMaxAndAllMin() {
        assertEquals(Triple(0, 0, 0), pushOrdered(top, top, top, TimeSlot.KEPT, 0))
        assertEquals(Triple(top, 0, 0), pushOrdered(top, top, top, TimeSlot.HEARD_AGAIN, 0))
        assertEquals(Triple(top, top, 0), pushOrdered(top, top, top, TimeSlot.HEARD_ONCE, 0))
        assertEquals(Triple(top, top, top), pushOrdered(0, 0, 0, TimeSlot.HEARD_ONCE, top))
        assertEquals(Triple(top, top, 0), pushOrdered(0, 0, 0, TimeSlot.HEARD_AGAIN, top))
        assertEquals(Triple(top, 0, 0), pushOrdered(0, 0, 0, TimeSlot.KEPT, top))
    }

    @Test fun neighboursOnlyMoveWhenCrossed() {
        assertEquals(Triple(5, 3, 1), pushOrdered(5, 3, 1, TimeSlot.HEARD_AGAIN, 3))
        assertEquals(Triple(8, 3, 1), pushOrdered(5, 3, 1, TimeSlot.KEPT, 8))
        assertEquals(Triple(4, 3, 1), pushOrdered(5, 3, 1, TimeSlot.KEPT, 4))
        assertEquals(Triple(2, 2, 1), pushOrdered(5, 3, 1, TimeSlot.KEPT, 2))
        assertEquals(Triple(6, 6, 6), pushOrdered(5, 3, 1, TimeSlot.HEARD_ONCE, 6))
    }

    @Test fun movedOnPolicy() {
        val p = CachePolicy(Lifetime.MAX, Lifetime.MAX, Lifetime.MAX).moved(TimeSlot.KEPT, Lifetime.HALF_DAY)
        assertEquals(Triple(Lifetime.HALF_DAY, Lifetime.HALF_DAY, Lifetime.HALF_DAY), Triple(p.kept, p.heardAgain, p.heardOnce))
    }
}
