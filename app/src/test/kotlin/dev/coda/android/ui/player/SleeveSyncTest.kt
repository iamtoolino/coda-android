package dev.coda.android.ui.player

import dev.coda.android.ui.player.SleeveSync.Rest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleeveSyncTest {
    /** A queue of entries "e0".."e6" unless edited; the queue key is just a number here. */
    private var queue = (0..6).map { "e$it" }
    private var key = 1
    private fun sync(index: Int) = SleeveSync(index, queue[index])
    private fun SleeveSync.started() = scrollStarted(key)
    private fun SleeveSync.changed(index: Int) = indexChanged(index, queue.getOrNull(index))
    private fun SleeveSync.rest(page: Int, index: Int) = atRest(page, queue.getOrNull(page), index, queue.getOrNull(index), queue.size, key)

    @Test fun aFreshSwipeSeeksAndTheResultingIndexIsNotAnExternalChange() {
        val sync = sync(1)
        sync.started()
        assertEquals(Rest.Seek(2), sync.rest(page = 2, index = 1))
        assertFalse(sync.changed(2))
    }

    @Test fun rapidSwipesSeekEachTimeBeforeTheControllerReportsBack() {
        val sync = sync(1)
        sync.started(); assertEquals(Rest.Seek(2), sync.rest(2, 1))
        // The controller masks the seek: it already reports 2 before the sleeve recomposes.
        sync.started(); assertEquals(Rest.Seek(3), sync.rest(3, 2))
        assertFalse(sync.changed(3))
    }

    @Test fun aSwipeBackToTheSamePageDoesNothing() {
        val sync = sync(1)
        sync.started()
        assertEquals(Rest.Stay, sync.rest(1, 1))
    }

    /** Hold a drag on page 1, playback advances to 2 meanwhile, release on page 1. */
    @Test fun aHeldDragThatPredatesAnExternalChangeFollowsInsteadOfSeekingBack() {
        val sync = sync(1)
        sync.started()
        assertTrue(sync.changed(2)) // the follow scroll is rejected by the held drag
        assertEquals(Rest.Follow(2), sync.rest(page = 1, index = 2))
        assertEquals(Rest.Stay, sync.rest(2, 2))
    }

    @Test fun aStaleGestureReleasedOnAnotherPageStillFollows() {
        val sync = sync(1)
        sync.started()
        assertTrue(sync.changed(2))
        assertEquals(Rest.Follow(2), sync.rest(page = 0, index = 2))
    }

    @Test fun anExternalChangeNotYetSeenByTheSleeveMakesTheGestureStale() {
        val sync = sync(1)
        sync.started()
        assertEquals(Rest.Follow(2), sync.rest(page = 0, index = 2))
        assertFalse(sync.changed(2))
    }

    /** Re-review P2: removing the playing entry elsewhere keeps its index (the successor moves in), during a held drag. */
    @Test fun removingThePlayingEntryDuringAHeldDragMakesItStale() {
        val sync = sync(1)
        sync.started()
        queue = queue - "e1"; key = 2
        assertTrue(sync.changed(1)) // same index, different entry
        assertEquals(Rest.Follow(1), sync.rest(page = 2, index = 1))
    }

    @Test fun replacingThePlayingEntryDuringAHeldDragMakesItStaleEvenBeforeTheSleeveSawIt() {
        val sync = sync(1)
        sync.started()
        queue = queue.toMutableList().apply { this[1] = "x1" }; key = 2
        assertEquals(Rest.Follow(1), sync.rest(page = 2, index = 1))
    }

    @Test fun anEditElsewhereInTheQueueDuringAHeldDragMakesItStale() {
        val sync = sync(1)
        sync.started()
        queue = queue.toMutableList().apply { add(2, "new") }; key = 2
        assertEquals(Rest.Follow(1), sync.rest(page = 2, index = 1))
        // The next swipe on the edited queue is fresh again.
        sync.started()
        assertEquals(Rest.Seek(2), sync.rest(page = 2, index = 1))
    }

    @Test fun aDragThatInterruptsAFollowIsFresh() {
        val sync = sync(1)
        assertTrue(sync.changed(2)) // follow animation to 2 starts
        sync.started()              // the user grabs the sleeve mid-animation
        assertEquals(Rest.Seek(4), sync.rest(4, 2))
    }

    @Test fun aCancelledFollowWithoutAnyGestureCatchesUpAtRest() {
        val sync = sync(5)
        assertTrue(sync.changed(4)); assertTrue(sync.changed(3)); assertTrue(sync.changed(2))
        assertEquals(Rest.Follow(2), sync.rest(4, 2))
    }

    @Test fun eachGestureIsUsedOnce() {
        val sync = sync(1)
        sync.started(); assertEquals(Rest.Seek(2), sync.rest(2, 1))
        assertEquals(Rest.Follow(2), sync.rest(3, 2))
    }

    @Test fun outOfRangeValuesNeverSeek() {
        queue = queue.take(3)
        val sync = sync(1)
        sync.started(); assertEquals(Rest.Follow(1), sync.rest(9, 1))
        assertEquals(Rest.Stay, sync.rest(0, -1))
    }

    /**
     * Re-review (Sol, 12f817e) P2: a drag that ends on the playing page ends the gesture. A later page change that no
     * drag or accessibility action started (a wheel or keyboard scroll) follows back instead of seeking. The frame
     * between a drag and its fling never reaches [SleeveSync.atRest]: SleevePager reports only settled rests (pinned in
     * SleevePagerComposeTest).
     */
    @Test fun aRestOnThePlayingPageEndsTheGesture() {
        val sync = sync(1)
        sync.started()
        assertEquals(Rest.Stay, sync.rest(page = 1, index = 1)) // the drag was released where it started
        assertEquals(Rest.Follow(1), sync.rest(page = 2, index = 1)) // an unarmed scroll: back to the playing item
        assertEquals(Rest.Stay, sync.rest(page = 1, index = 1))
    }

    /** A gesture still dies with any change made elsewhere before it comes to rest. */
    @Test fun aGestureIsStaleAfterAChangeElsewhereBeforeItsRest() {
        val sync = sync(1)
        sync.started()
        assertEquals(Rest.Follow(4), sync.rest(page = 2, index = 4)) // playback moved to 4 in between
        sync.started()
        key = 2 // the queue was edited
        assertEquals(Rest.Follow(4), sync.rest(page = 3, index = 4))
    }

    /** Second opinion P2 (b): a page change with no armed gesture (anything not announced by a drag or an accessibility action) never seeks; the sleeve goes back to the playing item. */
    @Test fun aPageChangeWithNoArmedGestureFollowsInsteadOfSeeking() {
        val sync = sync(1)
        assertEquals(Rest.Follow(1), sync.rest(page = 2, index = 1))
    }
}
