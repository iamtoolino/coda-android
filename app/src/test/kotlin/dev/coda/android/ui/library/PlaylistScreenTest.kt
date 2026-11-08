package dev.coda.android.ui.library

import dev.coda.core.library.TrackListPresentation
import dev.coda.core.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.coda.core.subsonic.Album
import dev.coda.core.subsonic.AlbumPage
import kotlinx.coroutines.runBlocking

/** The collapse/expand-all button's icon points the way a tap goes, and its label says the same. */
class PlaylistScreenTest {
    @Test fun someAlbumsOpenOffersCollapseAll() {
        val action = collapseAllAction(allCollapsed = false)
        assertEquals("Collapse all albums", action.label)
        assertEquals("Filled.UnfoldLess", action.icon.name)
    }

    @Test fun everyAlbumClosedOffersExpandAll() {
        val action = collapseAllAction(allCollapsed = true)
        assertEquals("Expand all albums", action.label)
        assertEquals("Filled.UnfoldMore", action.icon.name)
    }

    /** The phone's side takes a header's album from the saved catalogue and never asks the server. */
    @Test fun aHeaderShownFromThePhoneNeverCallsGetAlbum() = runBlocking {
        val asked = mutableListOf<String>()
        val saved = mutableListOf<AlbumPage>()
        val catalogue = AlbumPage(Album("a", "A", songCount = 12, year = 1999), emptyList())
        val live: suspend (String) -> AlbumPage = { id -> asked += id; AlbumPage(Album(id, "Live", songCount = 14), emptyList()) }
        val phone = playlistHeaderAlbum("a", fromPhone = true, saved = { catalogue.takeIf { p -> p.album.id == it } }, live = live, save = { saved += it })
        assertEquals(emptyList<String>(), asked)
        assertEquals(emptyList<AlbumPage>(), saved)
        assertEquals(catalogue.album, phone)
        assertEquals("not in the catalogue: no metadata, still no request", null,
            playlistHeaderAlbum("b", fromPhone = true, saved = { null }, live = live, save = { saved += it }))
        assertEquals(emptyList<String>(), asked)
        // The live side enriches from the server as before and keeps the page.
        val online = playlistHeaderAlbum("a", fromPhone = false, saved = { error("live side reads no catalogue") }, live = live, save = { saved += it })
        assertEquals(listOf("a"), asked)
        assertEquals(14, online?.songCount)
        assertEquals(1, saved.size)
    }

    private fun songs(vararg albums: Pair<String, Int>) = albums.flatMap { (album, count) ->
        (0 until count).map { Song("$album-$it", "t", albumId = album) }
    }.mapIndexed { index, song -> song.copy(id = "${song.id}#$index") }

    /** The items PlaylistScreen emits, spelled out here from the layout rules (hero; lead gap; header; rows; trailing gap of an album). */
    private fun expectedKeys(groups: List<TrackListPresentation.PlaylistGroup>): List<String> = buildList {
        add("hero")
        groups.forEachIndexed { i, group ->
            if (i > 0 && groups[i - 1].isStandalone && !group.isStandalone) add("lead-${group.startIndex}")
            add("album-${group.startIndex}")
            group.songs.indices.forEach { add("track-${group.startIndex + it}") }
            if (!group.isStandalone) add("gap-${group.startIndex}")
        }
    }

    @Test fun leadingGapOnlyForAnAlbumAfterSingles() {
        val groups = TrackListPresentation.playlistGroups(songs("a" to 1, "b" to 1, "c" to 3, "d" to 1, "e" to 3, "f" to 3))
        // singles a, b; album c; single d; album e; album f
        assertEquals(listOf(false, false, true, false, true, false), groups.indices.map { hasLeadingGap(groups, it) })
        assertFalse(hasLeadingGap(TrackListPresentation.playlistGroups(songs("c" to 3, "d" to 3)), 0))
    }

    @Test fun playingRowIndexCountsLeadingAndTrailingGaps() {
        for (fixture in listOf(
            songs("a" to 1, "b" to 1, "c" to 3, "d" to 1, "e" to 1),            // singles, album, singles
            songs("c" to 3, "a" to 1, "e" to 4, "f" to 3, "b" to 1),            // album, single, album, album, single
            songs("a" to 1, "c" to 3))) {                                        // singles first, album last
            val groups = TrackListPresentation.playlistGroups(fixture)
            val keys = expectedKeys(groups)
            for (index in fixture.indices) assertEquals("track $index", keys.indexOf("track-$index"), playlistItemPosition(groups, index))
        }
        assertTrue(playlistItemPosition(TrackListPresentation.playlistGroups(songs("a" to 1)), 5) < 0)
    }
}
