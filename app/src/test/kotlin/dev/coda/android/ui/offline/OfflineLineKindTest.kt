package dev.coda.android.ui.offline

import dev.coda.core.connectivity.Reachability.Link
import dev.coda.core.connectivity.Reachability.State
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineLineKindTest {
    private fun kind(link: Link, block: Boolean = false, updating: Boolean = false) =
        offlineLineKind(State(link, known = true), offerBlockShown = block, updating = updating)

    @Test fun eachStateHasItsLine() {
        assertEquals(OfflineLineKind.NONE, kind(Link.ONLINE))
        assertEquals(OfflineLineKind.OFFLINE, kind(Link.OFFLINE))
        assertEquals(OfflineLineKind.OFFER, kind(Link.UNREACHABLE))
        assertEquals(OfflineLineKind.OFFLINE, kind(Link.OFFLINE_CHOSEN))
    }

    @Test fun aPageOfferBlockHidesOnlyTheOffer() {
        assertEquals(OfflineLineKind.NONE, kind(Link.UNREACHABLE, block = true))
        assertEquals(OfflineLineKind.OFFLINE, kind(Link.OFFLINE, block = true))
    }

    /** Back online, the line reads "Back online · updating" while a view still shows the phone's content. */
    @Test fun backOnlineWhileAViewStillShowsThePhone() {
        assertEquals(OfflineLineKind.BACK_ONLINE, kind(Link.ONLINE, updating = true))
        assertEquals(OfflineLineKind.OFFLINE, kind(Link.OFFLINE, updating = true))
        assertEquals(OfflineLineKind.OFFLINE, kind(Link.OFFLINE_CHOSEN, updating = true))
        assertEquals(OfflineLineKind.OFFER, kind(Link.UNREACHABLE, updating = true))
        assertEquals(OfflineLineKind.NONE, kind(Link.UNREACHABLE, block = true, updating = true))
    }
}
