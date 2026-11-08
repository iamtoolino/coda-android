package dev.coda.android.ui.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What offline actions take, their notices, the way back's retries, the scroll anchor and the keep-sync guard. */
class PhoneActionsTest {
    private val album = listOf("t1", "t2", "t3", "t4", "t5")
    private val here = setOf("t1", "t3", "t5")

    @Test fun playTakesTheTracksOnThePhoneInTheViewsOrder() {
        val pick = pickOnPhone(album, 0) { it in here }
        assertEquals(listOf("t1", "t3", "t5"), pick.items)
        assertEquals(0, pick.start)
        assertEquals(2, pick.leftOut)
        // Shuffle: the order given (already shuffled) is kept.
        assertEquals(listOf("t5", "t1", "t3"), pickOnPhone(listOf("t4", "t5", "t2", "t1", "t3"), 0) { it in here }.items)
    }

    @Test fun theStartMovesToTheTappedTrackOrTheNextOneHere() {
        assertEquals(1, pickOnPhone(album, 2) { it in here }.start) // t3 is the second track here
        assertEquals(2, pickOnPhone(album, 3) { it in here }.start) // t4 missing: t5
        assertEquals(0, pickOnPhone(listOf("t1", "t2"), 1) { it in here }.start) // nothing here after: the first
    }

    @Test fun nothingLeftOutAndNothingHere() {
        val all = pickOnPhone(album, 4) { true }
        assertEquals(album, all.items); assertEquals(4, all.start); assertEquals(0, all.leftOut)
        val none = pickOnPhone(album, 0) { false }
        assertTrue(none.items.isEmpty()); assertEquals(5, none.leftOut)
    }

    @Test fun theNoticesSayHowManyPlay() {
        assertEquals("Playing the 7 tracks on this phone", playingOnPhone(7))
        assertEquals("Playing the 1 track on this phone", playingOnPhone(1))
        assertEquals("Added to queue: Bright · the 2 tracks on this phone", queuedOnPhone("Added to queue: Bright", 2))
    }

    /** The actions follow what the views show, also after OFFLINE while the server is unreachable. */
    @Test fun actionsTakeTheTracksHereWhileAViewShowsThePhone() {
        val shown = PhoneShown()
        assertTrue(phoneActions(phoneMode = true, shown))
        assertFalse(phoneActions(phoneMode = false, shown))
        shown.views = 1 // a view keeps the phone's content on the way back
        assertTrue(phoneActions(phoneMode = false, shown))
        shown.views = 0 // its live answer is in
        assertFalse(phoneActions(phoneMode = false, shown))
    }

    @Test fun theWayBackRetriesOnTheOf1bSchedule() {
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L), (0..6).map(::retryDelay))
    }

    @Test fun neverABlankThePhoneSideWaitsForItsContent() {
        val onPhone = OnPhone(setOf("t1"))
        assertFalse(phoneReady(fromPhone = true, phone = null, onPhone = onPhone))
        assertFalse(phoneReady(fromPhone = true, phone = "page", onPhone = null))
        assertTrue(phoneReady(fromPhone = true, phone = "page", onPhone = onPhone))
        assertFalse(phoneReady(fromPhone = false, phone = "page", onPhone = onPhone))
    }

    @Test fun theAnchorKeepsTheSameItemByKeyElseByIndex() {
        val anchor = ScrollAnchor().apply { keys = listOf("album-7"); index = 12; offset = 30 }
        assertEquals(4 to 30, anchor.start { if (it == "album-7") 4 else null })
        assertEquals(12 to 30, anchor.start { null })
        assertEquals(0 to 0, ScrollAnchor().start { 3 })
    }

    // A section header first in view, and its first album gone from the other list: the next album
    // in view that the other list has is kept in view (at the top), never the old numeric index.
    @Test fun aSectionedListKeepsTheNearestRemainingAlbumInView() {
        val live = (0 until 120).map { dev.coda.core.subsonic.Album("a%03d".format(it), "x") }
        // The phone's list: every third album, in sections of 10.
        val phone = live.filterIndexed { i, _ -> i % 3 == 0 }
        val sections = phone.indices.step(10).map { dev.coda.core.library.LibrarySection("S$it", minOf(10, phone.size - it), it) }
        // In view on the live list: a header, then a061 (not on the phone), a062 (not), a063 (on the phone, its 21st).
        val anchor = ScrollAnchor().apply { keys = listOf("section-6-G", "a061", "a062", "a063"); index = 66; offset = 40 }
        val (index, offset) = anchor.start { dev.coda.android.ui.library.albumGridIndex(phone, sections, key = it) }
        // a063 is phone index 21: three section headers (0, 10, 20) before it.
        assertEquals(24 to 0, index to offset)
    }

    // The rows' index after the lead items and one header per section (Artists' dividers, Playlists' sections).
    @Test fun aRowsIndexCountsTheLeadAndTheHeadersBeforeIt() {
        val sections = listOf(dev.coda.core.library.LibrarySection("A", 3, 0), dev.coda.core.library.LibrarySection("B", 4, 3))
        assertEquals(7, sectionedIndex(5, lead = 2))
        assertEquals(3, sectionedIndex(2, lead = 0, sections))
        assertEquals(6, sectionedIndex(3, lead = 1, sections))
    }

    // Home's keys follow its list (shelves only when they have items). A failed refresh adds no line.
    @Test fun homesKeysFollowItsList() {
        val album = listOf(dev.coda.core.subsonic.Album("al-1", "x"))
        val state = dev.coda.android.ui.home.HomeState(albums = album, recentlyPlayed = album, loading = false, failed = true)
        assertEquals(listOf("title", "handoff", "Recently Added Albums", "Recently Played"), dev.coda.android.ui.home.homeKeys(state))
        assertEquals(listOf("title", "progress", "handoff", "Recently Added Albums", "Recent Releases", "Recently Played"),
            dev.coda.android.ui.home.homeKeys(dev.coda.android.ui.home.HomeState()))
    }

    @Test fun keptSetsReSyncOnlyFromAFreshLivePage() {
        assertTrue(keepSyncFresh(loading = false, failed = false, onPhone = null))
        // The phone's copy never re-syncs a kept set (it may miss tracks the server has).
        assertFalse(keepSyncFresh(loading = false, failed = false, onPhone = OnPhone(setOf("t1"))))
        assertFalse(keepSyncFresh(loading = true, failed = false, onPhone = null))
        assertFalse(keepSyncFresh(loading = false, failed = true, onPhone = null))
    }
}
