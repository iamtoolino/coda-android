package dev.coda.android.ui.queue

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A long press on a queue track must never start playback (seek + play). */
class QueueTapGuardTest {
    private val timeout = 400L
    private fun guard() = QueueTapGuard { timeout }

    /** Mimics the track row: pointer events feed the guard, the click handler runs the play action iff the guard allows it. */
    private fun release(guard: QueueTapGuard, pressMillis: Long): Boolean {
        var played = false
        guard.onDown(1_000)
        guard.onUp(1_000 + pressMillis)
        if (guard.shouldRunTap()) played = true
        return played
    }

    @Test fun shortTapStillPlays() {
        assertTrue(release(guard(), 80))
        assertTrue(release(guard(), 299))
    }

    @Test fun longPressReleasedInPlaceDoesNotPlay() {
        // Compose's longClick() presses for longPressTimeout + 100 ms.
        assertFalse(release(guard(), timeout + 100))
        assertFalse(release(guard(), timeout))
    }

    @Test fun pressJustUnderTimeoutIsStillATap() {
        assertTrue(release(guard(), 300))
        assertTrue(release(guard(), 350))
        assertTrue(release(guard(), timeout - 1))
    }

    @Test fun pressAtOrAfterTimeoutDoesNotPlay() {
        assertFalse(release(guard(), timeout))
        assertFalse(release(guard(), timeout + 1))
    }

    @Test fun albumHeaderKeepsItsEarlierCutoff() {
        assertTrue(QueueTapGuard.isAlbumTap(299, timeout))
        assertFalse(QueueTapGuard.isAlbumTap(300, timeout))
    }

    @Test fun tapAfterLongPressPlaysAgain() {
        val g = guard()
        assertFalse(release(g, 600))
        assertTrue(release(g, 60))
    }

    @Test fun accessibilityClickWithoutPointerEventsPlays() {
        assertTrue(guard().shouldRunTap())
    }

    @Test fun cancelledPressDoesNotPlayNorPoisonNextTap() {
        val g = guard()
        g.onDown(0); g.onCancel()
        assertTrue(g.shouldRunTap())
    }
}
