package dev.coda.core.library

import dev.coda.core.subsonic.Playlist
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistOrderTest {
    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private val us = Locale.US
    private fun playlist(id: String, name: String = id, tracks: Int? = 10, seconds: Int? = 600, changed: String? = null, created: String? = null) =
        Playlist(id, name, songCount = tracks, duration = seconds, changed = changed, created = created)
    private fun ids(sort: PlaylistSort, list: List<Playlist>) = PlaylistOrder.sort(sort, list, us).map { it.id }

    @Test fun labelsAreTheApprovedOnes() {
        assertEquals(listOf("Name", "Recently changed", "Recently created", "Most tracks", "Longest"), PlaylistSort.entries.map { it.label })
        assertEquals(PlaylistSort.NAME, PlaylistSort.entries.first())
    }

    @Test fun nameSortIsAToZWithHashFirstAndLeadingArticlesIgnored() {
        val list = listOf(playlist("c", "Charlie"), playlist("the", "The Bravo"), playlist("n", "99 Problems"),
            playlist("a", "alpha"), playlist("e", "Édith"))
        assertEquals(listOf("n", "a", "the", "c", "e"), ids(PlaylistSort.NAME, list))
    }

    @Test fun autoPlaylistsSortAndSectionByTheShownName() {
        val list = listOf(playlist("z", "Zen"), playlist("a", "[Auto] Recently Added"), playlist("b", "Bravo"), playlist("x", "My [Auto] mix"))
        // "Recently Added" files under R, between Bravo and Zen; "My [Auto] mix" keeps its own M.
        assertEquals(listOf("b", "x", "a", "z"), ids(PlaylistSort.NAME, list))
        val many = (1..60).map { playlist("n$it", "[Auto] R$it") } + playlist("c", "Charlie")
        assertEquals(listOf("C", "R"), PlaylistOrder.sections(PlaylistSort.NAME, PlaylistOrder.sort(PlaylistSort.NAME, many, us)).map { it.label })
    }

    @Test fun changedAndCreatedAreNewestFirstAndUndatedLast() {
        val list = listOf(
            playlist("old", changed = "2025-01-01T00:00:00Z", created = "2026-01-01T00:00:00Z"),
            playlist("new", changed = "2026-10-01T10:00:00.123456789Z", created = "2024-01-01T00:00:00Z"),
            playlist("none"),
            playlist("mid", changed = "2026-03-01T00:00:00+02:00", created = "2025-06-01T00:00:00Z"))
        assertEquals(listOf("new", "mid", "old", "none"), ids(PlaylistSort.CHANGED, list))
        assertEquals(listOf("old", "mid", "new", "none"), ids(PlaylistSort.CREATED, list))
    }

    @Test fun tracksAndLongestAreDescendingAndTiesFallBackToName() {
        val list = listOf(playlist("b", "Beta", tracks = 5, seconds = 100), playlist("a", "Alpha", tracks = 5, seconds = 300),
            playlist("big", "Zed", tracks = 50, seconds = 200), playlist("nil", "Nil", tracks = null, seconds = null))
        assertEquals(listOf("big", "a", "b", "nil"), ids(PlaylistSort.TRACKS, list))
        assertEquals(listOf("a", "big", "b", "nil"), ids(PlaylistSort.LONGEST, list))
    }

    @Test fun letterSectionsOnlyForNameAndOnlyFromTheAlbumThreshold() {
        val many = (0 until AlbumSectionThreshold).map { playlist("p$it", (if (it < 40) "Apple " else "Berry ") + it) }
        val sorted = PlaylistOrder.sort(PlaylistSort.NAME, many, us)
        assertEquals(listOf(LibrarySection("A", 40, 0), LibrarySection("B", 20, 40)), PlaylistOrder.sections(PlaylistSort.NAME, sorted))
        assertTrue(PlaylistOrder.sections(PlaylistSort.CHANGED, sorted).isEmpty())
        assertTrue(PlaylistOrder.sections(PlaylistSort.NAME, sorted.drop(1)).isEmpty())
    }

    @Test fun durationRoundsToTheMinute() {
        assertNull(PlaylistOrder.duration(null)); assertNull(PlaylistOrder.duration(0))
        assertEquals("1 min", PlaylistOrder.duration(5))
        assertEquals("1 min", PlaylistOrder.duration(89))
        assertEquals("2 min", PlaylistOrder.duration(90))
        assertEquals("59 min", PlaylistOrder.duration(59 * 60))
        assertEquals("1 h", PlaylistOrder.duration(3600))
        assertEquals("1 h 32 min", PlaylistOrder.duration(92 * 60))
        assertEquals("2 h", PlaylistOrder.duration(7200 + 20))
        assertEquals("1 h", PlaylistOrder.duration(3570)) // 59.5 min rounds up to the hour
    }

    @Test fun tracksArePluralised() {
        assertEquals("0 tracks", PlaylistOrder.tracks(0, us))
        assertEquals("1 track", PlaylistOrder.tracks(1, us))
        assertEquals("24 tracks", PlaylistOrder.tracks(24, us))
        assertEquals("1,204 tracks", PlaylistOrder.tracks(1204, us))
    }

    @Test fun relativeTime() {
        fun ago(seconds: Long) = PlaylistOrder.relativeTime(now.minusSeconds(seconds), now)
        assertEquals("just now", ago(0)); assertEquals("just now", ago(59)); assertEquals("just now", ago(-3600))
        assertEquals("1 min ago", ago(60)); assertEquals("59 min ago", ago(59 * 60 + 59))
        assertEquals("1 h ago", ago(3600)); assertEquals("23 h ago", ago(23 * 3600 + 3599))
        assertEquals("yesterday", ago(24 * 3600)); assertEquals("yesterday", ago(47 * 3600))
        assertEquals("2 days ago", ago(48 * 3600)); assertEquals("6 days ago", ago(6 * 86400 + 1))
        assertEquals("1 week ago", ago(7 * 86400)); assertEquals("2 weeks ago", ago(14 * 86400)); assertEquals("4 weeks ago", ago(29 * 86400))
        assertEquals("1 month ago", ago(30 * 86400)); assertEquals("11 months ago", ago(364 * 86400))
        assertEquals("1 year ago", ago(365 * 86400)); assertEquals("3 years ago", ago(3 * 365 * 86400 + 86400))
    }

    @Test fun metadataLine() {
        val p = playlist("p", tracks = 24, seconds = 92 * 60, changed = "2026-10-23T12:00:00Z", created = "2026-09-28T12:00:00Z")
        fun line(sort: PlaylistSort, playlist: Playlist = p) = PlaylistOrder.metadata(playlist, sort, now, us)
        assertEquals("24 tracks · 1 h 32 min", line(PlaylistSort.NAME))
        assertEquals("24 tracks · 1 h 32 min", line(PlaylistSort.TRACKS))
        assertEquals("24 tracks · 1 h 32 min", line(PlaylistSort.LONGEST))
        assertEquals("24 tracks · 1 h 32 min · 3 days ago", line(PlaylistSort.CHANGED))
        assertEquals("24 tracks · 1 h 32 min · 4 weeks ago", line(PlaylistSort.CREATED))
        assertEquals("1 track · 3 min", line(PlaylistSort.NAME, playlist("one", tracks = 1, seconds = 180)))
        assertEquals("0 tracks", line(PlaylistSort.NAME, playlist("empty", tracks = null, seconds = null)))
        // Missing or unreadable dates drop only the time part.
        assertEquals("24 tracks · 1 h 32 min", line(PlaylistSort.CHANGED, p.copy(changed = null)))
        assertEquals("24 tracks · 1 h 32 min", line(PlaylistSort.CREATED, p.copy(created = "garbage")))
    }
}
