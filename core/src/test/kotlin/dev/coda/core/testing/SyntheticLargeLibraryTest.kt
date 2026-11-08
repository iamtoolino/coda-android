package dev.coda.core.testing

import dev.coda.core.library.AlbumSectionKind
import dev.coda.core.library.AlbumSectioner
import dev.coda.core.library.ArtistIndex
import dev.coda.core.subsonic.AlbumListType
import dev.coda.core.subsonic.SubsonicClient
import dev.coda.core.subsonic.SubsonicConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SyntheticLargeLibraryTest {
    @Test fun largeLibraryThroughTheClient() = runBlocking {
        // A fixed mid-week clock: "This week" starts on Monday 00:00, so with the real clock the newest album (up to a day
        // old) fell into "Earlier in <month>" during the first day of every week.
        val now = java.time.ZonedDateTime.of(2026, 10, 21, 12, 0, 0, 0, java.time.ZoneId.systemDefault())
        val library = SyntheticLargeLibrary(now = now.toInstant())
        assertEquals(812, library.artists.size)
        assertEquals(library.albums.size, library.albums.distinctBy { it.id }.size)
        assertTrue("~2,000 albums, was ${library.albums.size}", library.albums.size in 1700..2600)
        FakeSubsonicServer().use { server ->
            server.largeLibrary = library
            val client = SubsonicClient(SubsonicConfig(server.baseUrl, "synthetic", "synthetic-password"))
            val artists = client.getArtists()
            assertEquals(812, artists.size)
            assertEquals("Velvet Orchard", artists.first { it.name == "The Velvet Orchard" }.sortName)
            val index = ArtistIndex.of(artists)
            assertEquals("#", index.sections.first().label)
            assertNull(index.sections.firstOrNull { it.label == "X" })
            val at = index.artists.indexOfFirst { it.name == "The Velvet Orchard" }
            assertEquals("V", index.sections[index.sectionOf(at)].label)
            assertTrue(index.sections.first { it.label == "V" }.let { v -> index.artists.subList(v.start, v.start + v.count).any { it.name == "The Velvet Orchard" } })

            val all = (0 until 30).flatMap { client.getAlbumList2(100, it * 100, AlbumListType.ALPHABETICAL).albums }
            assertEquals(library.albums.size, all.size)
            assertTrue(AlbumSectioner.sections(AlbumSectionKind.TITLE, all).size > 12)
            val added = client.getAlbumList2(500, 0, AlbumListType.NEWEST).albums
            assertEquals("This week", AlbumSectioner.sections(AlbumSectionKind.ADDED, added, now).first().label)
            val played = client.getAlbumList2(500, 0, AlbumListType.RECENTLY_PLAYED).albums
            assertTrue(played.all { it.played != null } && played.isNotEmpty())
            assertTrue(client.getAlbumList2(500, 0, AlbumListType.TOP_RATED).albums.all { (it.userRating ?: 0) > 0 })

            val chronology = client.getArtist(library.artistNamed(SyntheticLargeLibrary.CHRONOLOGY).id)
            assertEquals(21, chronology.albums.size)
            assertEquals(3, client.getArtist(library.artistNamed(SyntheticLargeLibrary.SHORT_CAREER).id).albums.size)
            assertEquals(1, client.getArtist(library.artistNamed(SyntheticLargeLibrary.ONE_RELEASE).id).albums.size)
            val page = client.getAlbum(chronology.albums.first().id)
            assertEquals(2, page.songs.size)
        }
    }

    /** Device tests use the real clock; the nightly of Monday 2026-11-02 ran at ~02:16 and had no "This week". */
    @Test fun recentlyAddedStartsWithThisWeekAtAnyClockEvenEarlyMonday() {
        val zone = java.time.ZoneId.systemDefault()
        val wrong = listOf(java.time.LocalTime.of(0, 0, 30), java.time.LocalTime.of(2, 16), java.time.LocalTime.of(12, 0)).flatMap { time ->
            listOf(java.time.LocalDate.of(2026, 11, 2), java.time.LocalDate.of(2026, 11, 4), java.time.LocalDate.of(2026, 11, 8)).mapNotNull { date ->
                val now = java.time.ZonedDateTime.of(date, time, zone)
                val newest = SyntheticLargeLibrary(now = now.toInstant()).albumList("newest", 0)
                AlbumSectioner.sections(AlbumSectionKind.ADDED, newest, now).first().label.takeIf { it != "This week" }?.let { "$now: $it" }
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test fun generatedCoverIsAPng() {
        val png = SyntheticLargeLibrary.cover("lib-cover-1")
        assertEquals(0x89.toByte(), png[0]); assertEquals('P'.code.toByte(), png[1])
        assertFalse(png.contentEquals(SyntheticLargeLibrary.cover("lib-cover-2")))
    }
}
