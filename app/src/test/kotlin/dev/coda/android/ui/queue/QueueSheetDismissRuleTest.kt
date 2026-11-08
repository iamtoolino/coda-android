package dev.coda.android.ui.queue

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The release rule follows Material's ModalBottomSheet: 56 dp of travel, or a downward fling over 125 dp/s; upward flings never dismiss. */
class QueueSheetDismissRuleTest {
    private val density = 2.5f
    private fun hide(offsetDp: Float, flingDpPerSecond: Float) = shouldDismissQueueSheet(offsetDp * density, flingDpPerSecond * density, density)

    @Test fun releasePastTheDistanceAtRestDismisses() = assertTrue(hide(57f, 0f))
    @Test fun releaseShortOfTheDistanceAtRestSpringsBack() { assertFalse(hide(55f, 0f)); assertFalse(hide(30f, 0f)) }
    @Test fun slowDownwardMovementUsesTheDistance() { assertTrue(hide(100f, 100f)); assertFalse(hide(30f, 100f)) }
    @Test fun downwardFlingDismissesEvenFromAShortTravel() { assertTrue(hide(10f, 126f)); assertTrue(hide(10f, 1500f)) }
    @Test fun upwardFlingNeverDismissesEvenPastTheDistance() { assertFalse(hide(200f, -126f)); assertFalse(hide(80f, -3000f)) }
    @Test fun weakUpwardMovementPastTheDistanceStillDismisses() = assertTrue(hide(100f, -100f))
}
