package dev.coda.android.ui.offline

import dev.coda.core.connectivity.Reachability.Link
import dev.coda.core.connectivity.Reachability.State
import dev.coda.core.library.OfflineCatalogue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When views show the phone, the way back, the held offer block and the "7 of 12" lookups. */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneViewsTest {
    @Test fun onlyOfflineShowsThePhone() {
        assertTrue(showsPhone(State(Link.OFFLINE, known = true)))
        assertTrue(showsPhone(State(Link.OFFLINE_CHOSEN, known = true)))
        // Unreachable is the offer: nothing changes until it is taken.
        assertFalse(showsPhone(State(Link.UNREACHABLE, known = true)))
        assertFalse(showsPhone(State(Link.ONLINE, known = true)))
    }

    @Test fun theWayBackWaitsForTheViewsNextLiveAnswer() {
        val way = WayBack(phone = true)
        way.update(phone = true, answers = 3)
        assertTrue(way.fromPhone)
        // Back online: the phone's content stays while the model still has only its old answers.
        way.update(phone = false, answers = 3)
        assertTrue(way.fromPhone)
        way.update(phone = false, answers = 3)
        assertTrue(way.fromPhone)
        // The live data arrived: one switch (the cross-fade).
        way.update(phone = false, answers = 4)
        assertFalse(way.fromPhone)
        // Offline again: the phone again.
        way.update(phone = true, answers = 4)
        assertTrue(way.fromPhone)
    }

    @Test fun aLiveAnswerThatFailsWithTheServerAnsweringEndsTheUpdating() {
        val back = FailedBack()
        // The failure from before the way back (server unreachable) doesn't count: no live request yet.
        assertFalse(back.update(shown = true, online = true, loading = false, failed = true))
        assertFalse(back.update(shown = true, online = true, loading = true, failed = false))
        // The way back's request failed with the server answering (an error answer): no more "updating".
        assertTrue(back.update(shown = true, online = true, loading = false, failed = true))
        // Its retries don't bring the line back while they run.
        assertTrue(back.update(shown = true, online = true, loading = true, failed = false))
        // The server is gone again: the failure no longer counts (the next way back waits again).
        assertFalse(back.update(shown = true, online = false, loading = false, failed = true))
        assertFalse(back.update(shown = true, online = true, loading = false, failed = true))
        // The live answer is in (the phone's side left): reset.
        assertTrue(back.run { update(true, true, true, false); update(true, true, false, true) })
        assertFalse(back.update(shown = false, online = true, loading = false, failed = true))
    }

    @Test fun aViewOpenedOnlineNeverShowsThePhone() {
        val way = WayBack(phone = false)
        way.update(phone = false, answers = 0)
        way.update(phone = false, answers = 1)
        assertFalse(way.fromPhone)
    }

    @Test fun offerBlocksAreHeldBetweenTwoPages() = runTest {
        val shown = MutableStateFlow(false)
        val seen = mutableListOf<Boolean>()
        val job = launch { shown.held(OFFER_BLOCK_HOLD_MS).collect { seen += it } }
        runCurrent(); advanceTimeBy(OFFER_BLOCK_HOLD_MS + 1)
        shown.value = true; runCurrent()
        // Page A's block leaves, page B's arrives 100 ms later: no drop in between.
        shown.value = false; runCurrent(); advanceTimeBy(100)
        shown.value = true; runCurrent()
        assertEquals(listOf(false, true), seen)
        // The last block leaves: the drop comes after the hold.
        shown.value = false; runCurrent(); advanceTimeBy(OFFER_BLOCK_HOLD_MS - 1)
        assertEquals(listOf(false, true), seen)
        advanceTimeBy(2)
        assertEquals(listOf(false, true, false), seen)
        job.cancel()
    }

    @Test fun partialCountsOnly() {
        val onPhone = OnPhone(setOf("s-1"), mapOf("album/a" to OfflineCatalogue.Count(7, 12), "album/b" to OfflineCatalogue.Count(3, 3),
            "playlist/p" to OfflineCatalogue.Count(21, 23)))
        assertEquals("7 of 12", onPhone.album("a"))
        assertNull(onPhone.album("b"))
        assertNull(onPhone.album("p"))
        assertEquals("21 of 23", onPhone.playlist("p"))
        assertTrue(onPhone.has("s-1"))
        assertFalse(onPhone.has("s-2"))
    }
}
