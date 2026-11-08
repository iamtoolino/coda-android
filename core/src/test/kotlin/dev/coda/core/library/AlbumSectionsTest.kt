package dev.coda.core.library

import dev.coda.core.playback.QueueEntry
import dev.coda.core.subsonic.*
import dev.coda.core.sync.resumeProgress
import org.junit.Assert.*
import org.junit.Test

/** Q2-01 (disc headings), B04 (resume progress) and Q2-03 (group removal range). */
class AlbumSectionsTest {
    private fun song(id: String, disc: Int?, track: Int, seconds: Int, subtitle: String? = null) =
        Song(id, "T$id", discNumber = disc, track = track, duration = seconds, discSubtitle = subtitle)

    @Test fun singleDiscAlbumHasNoHeading() {
        val rows = AlbumSections.rows(listOf(song("1", null, 1, 60), song("2", 1, 2, 60)))
        assertTrue(rows.all { it is AlbumRow.Track })
        assertEquals(listOf(0, 1), rows.map { (it as AlbumRow.Track).index })
    }

    @Test fun multiDiscAlbumGetsOneHeadingPerDiscWithCountsAndTitle() {
        val songs = listOf(song("1", 1, 1, 300), song("2", 1, 2, 300, "Studio"), song("3", 2, 1, 600), song("4", 2, 2, 540, "Live"))
        val rows = AlbumSections.rows(songs)
        assertEquals(6, rows.size)
        val first = rows[0] as AlbumRow.Disc
        assertEquals("DISC 1 · 2 TRACKS · 10 MIN", first.label)
        assertEquals("Studio", first.title)
        assertEquals(listOf("1", "2"), listOf(rows[1], rows[2]).map { (it as AlbumRow.Track).song.id })
        val second = rows[3] as AlbumRow.Disc
        assertEquals("DISC 2 · 2 TRACKS · 19 MIN", second.label)
        assertEquals("Live", second.title)
        // Track rows keep their index into the full list, so tapping a row plays from the right song.
        assertEquals(listOf(2, 3), listOf(rows[4], rows[5]).map { (it as AlbumRow.Track).index })
        assertEquals(2, AlbumSections.discCount(songs))
    }

    @Test fun singularTrackAndDurationFormatting() {
        val rows = AlbumSections.rows(listOf(song("1", 1, 1, 30), song("2", 2, 1, 30)))
        assertEquals("DISC 1 · 1 TRACK · 1 MIN", (rows[0] as AlbumRow.Disc).label)
        assertEquals("43 min", AlbumSections.duration(43 * 60 + 5))
        assertEquals("2 h 20 min", AlbumSections.duration(140 * 60))
    }

    @Test fun resumeProgressForSingleAndMultiDiscAlbums() {
        val single = (1..12).map { song("s$it", 1, it, 300) }
        val a = resumeProgress(single, "s5")!!
        assertEquals(5, a.trackNumber); assertEquals(12, a.trackCount); assertNull(a.disc)
        assertEquals(8 * 300, a.remainingSeconds)
        assertEquals(4f / 12f, a.fraction, 1e-4f)
        assertEquals("5 of 12 · 40 min left", a.subtitle)
        val multi = listOf(song("1", 1, 1, 1800), song("2", 1, 2, 1800), song("3", 2, 1, 1860))
        val b = resumeProgress(multi, "3")!!
        assertEquals(2, b.disc)
        assertEquals("Disc 2 · 31 min left", b.subtitle)
        assertNull("A resume target that is not on the album gives no progress", resumeProgress(single, "gone"))
    }

    @Test fun groupRemovalIncludesAllVisibleOccurrences() {
        val entries = listOf("a", "a", "a", "b", "b").mapIndexed { i, album -> QueueEntry("s$i", albumId = album) }
        val groups = TrackListPresentation.queueGroups(entries)
        assertEquals(0..2, groups[0].removableRange())
        assertEquals(3..3, groups[1].removableRange())
        assertEquals(4..4, groups[2].removableRange())
    }
}
