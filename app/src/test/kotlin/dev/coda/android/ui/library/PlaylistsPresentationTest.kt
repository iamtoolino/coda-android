package dev.coda.android.ui.library

import dev.coda.android.ui.offline.OnPhone
import dev.coda.core.library.OfflineCatalogue
import dev.coda.core.library.PlaylistOrder
import dev.coda.core.library.PlaylistSort
import dev.coda.core.subsonic.Playlist
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The Playlists tab's order, sections and lines are made off Main, and are what the tab showed before. */
class PlaylistsPresentationTest {
    private val now = Instant.parse("2026-11-08T12:00:00Z")
    private val source = (0 until 80).map { i ->
        Playlist("p$i", listOf("The Mix", "Zed", "alpha", "[Auto] Recently Added", "Beta", "émile")[i % 6] + " $i", songCount = (i * 7) % 23,
            duration = (i * 997) % 9000, changed = "2026-0${(i % 9) + 1}-0${(i % 8) + 1}T10:00:00Z",
            created = "2025-0${(i % 9) + 1}-1${i % 10}T08:00:00Z")
    }
    private val onPhone = OnPhone(emptySet(), mapOf("playlist/p3" to OfflineCatalogue.Count(1, 2), "playlist/p5" to OfflineCatalogue.Count(4, 4)))

    @Test fun thePresentationIsMadeOnTheDefaultDispatcher() = runBlocking {
        var thread: String? = null
        presentPlaylists(source, PlaylistSort.NAME, null) { thread = Thread.currentThread().name; now }
        assertTrue("made on $thread", thread.orEmpty().startsWith("DefaultDispatcher-worker"))
    }

    @Test fun thePresentationIsUnchanged() = runBlocking {
        for (sort in PlaylistSort.entries) for (phone in listOf(null, onPhone)) {
            val shown = presentPlaylists(source, sort, phone) { now }
            // As the tab computed it in composition before RV2-9.
            val playlists = PlaylistOrder.sort(sort, source)
            assertEquals("$sort order", playlists, shown.playlists)
            assertEquals("$sort sections", PlaylistOrder.sections(sort, playlists), shown.sections)
            assertEquals("$sort lines", playlists.map { p -> PlaylistOrder.metadata(p, sort, now, tracks = phone?.playlist(p.id)?.let { "$it tracks" }) }, shown.lines)
            assertTrue(shown.isFor(source, sort, phone))
        }
    }

    /** Made at once for the first frame and when playlists arrive after an empty list; otherwise off Main. */
    @Test fun aPresentationIsMadeAtOnceOnlyWhenNothingWouldShow() {
        val first = playlistsShownAtOnce(null, emptyList(), PlaylistSort.NAME, null)!!
        assertTrue(first.playlists.isEmpty())
        val arrived = playlistsShownAtOnce(first, source, PlaylistSort.NAME, null)
        assertEquals("the list that arrived shows in the same frame", PlaylistOrder.sort(PlaylistSort.NAME, source), arrived?.playlists)
        assertNull("a sort change waits for Default", playlistsShownAtOnce(arrived, source, PlaylistSort.TRACKS, null))
        assertNull("a refresh too", playlistsShownAtOnce(arrived, source.drop(1), PlaylistSort.NAME, null))
        assertNull("empty after filled: made off Main", playlistsShownAtOnce(arrived, emptyList(), PlaylistSort.NAME, null))
    }

    /** The fade and jump to the top follow the sort shown, not the chip, so they wait for the new order. */
    @Test fun theSortChangeFadeWaitsForTheNewOrder() = runBlocking {
        val byName = presentPlaylists(source, PlaylistSort.NAME, null) { now }
        // The chip says TRACKS, the presentation shown is still by name: not yet.
        assertNull(playlistsShownAtOnce(byName, source, PlaylistSort.TRACKS, null))
        assertFalse(sortFadeDue(PlaylistSort.NAME, byName))
        val byTracks = presentPlaylists(source, PlaylistSort.TRACKS, null) { now }
        assertTrue(sortFadeDue(PlaylistSort.NAME, byTracks))
        assertFalse("once", sortFadeDue(PlaylistSort.TRACKS, byTracks))
    }
}
