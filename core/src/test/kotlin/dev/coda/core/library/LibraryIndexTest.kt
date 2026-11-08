package dev.coda.core.library

import dev.coda.core.subsonic.Album
import dev.coda.core.subsonic.ArtistReference
import dev.coda.core.subsonic.Artist
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class LibraryIndexTest {
    private fun artist(name: String, sortName: String? = null) = Artist("id-$name", name, 1, sortName = sortName)

    @Test fun sectionLettersFoldDiacriticsAndSpecialLetters() {
        assertEquals('E', sectionLetter("Élodie Marchetti"))
        assertEquals('O', sectionLetter("Øst"))
        assertEquals('A', sectionLetter("Ægir"))
        assertEquals('O', sectionLetter("Œuvre"))
        assertEquals('S', sectionLetter("ßeta"))
        assertEquals('T', sectionLetter("Þór"))
        assertEquals('L', sectionLetter("Łukasz"))
        assertEquals('#', sectionLetter("2 Minute Lanterns"))
        assertEquals('#', sectionLetter("+Vessel+"))
        assertEquals('#', sectionLetter("日本"))
        assertEquals('#', sectionLetter(""))
    }

    @Test fun ignoredArticlesMoveArtistsToTheirSortLetter() {
        assertEquals("Velvet Orchard", artistSortKey("The Velvet Orchard", null, listOf("The", "El", "La")))
        assertEquals("Theremin Club", artistSortKey("Theremin Club", null, listOf("The")))
        assertEquals("The", artistSortKey("The", null, listOf("The")))
        assertEquals("Orchard, The", artistSortKey("The Orchard", "Orchard, The", listOf("The")))
    }

    @Test fun artistIndexPutsHashFirstAndGroupsByFoldedLetter() {
        val index = ArtistIndex.of(listOf(artist("Beta"), artist("Élodie"), artist("alpha"), artist("+Vessel+"),
            artist("The Velvet Orchard", sortName = "Velvet Orchard"), artist("Ewan"), artist("2 Minute")), Locale.ENGLISH)
        assertEquals(listOf("+Vessel+", "2 Minute", "alpha", "Beta", "Élodie", "Ewan", "The Velvet Orchard"), index.artists.map { it.name })
        assertEquals(listOf("#" to 2, "A" to 1, "B" to 1, "E" to 2, "V" to 1), index.sections.map { it.label to it.count })
        assertEquals(listOf(0, 2, 3, 4, 6), index.sections.map { it.start })
        assertEquals(3, index.sectionOf(5)); assertEquals(0, index.sectionOf(1))
    }

    @Test fun filterIsCaseAndDiacriticInsensitiveWithOriginalRanges() {
        val list = listOf(artist("Ada Marrow"), artist("Élodie Marchetti"), artist("Omar Haddad"), artist("Bjørk"), artist("Nobody"))
        val result = ArtistFilter.matches(list, " MAR ")
        assertEquals(listOf("Ada Marrow", "Élodie Marchetti", "Omar Haddad"), result.map { it.artist.name })
        assertEquals(4..6, result[0].range); assertEquals(1..3, result[2].range)
        assertEquals(listOf("Élodie Marchetti"), ArtistFilter.matches(list, "elod").map { it.artist.name })
        assertEquals(listOf("Bjørk"), ArtistFilter.matches(list, "BJORK").map { it.artist.name })
        assertEquals(5, ArtistFilter.matches(list, "  ").size)
    }

    @Test fun recentArtistsDeduplicateSkipCompilationsAndLimit() {
        val library = listOf(Artist("a", "Ada", 3, coverArt = "ar-a"))
        val albums = listOf(
            Album("1", "One", artist = "Ada", artistId = "a"),
            Album("2", "Two", artist = "Various Artists", artistId = "va"),
            Album("3", "Three", artist = "Ada", artistId = "a"),
            Album("4", "Comp", artist = "Mix", artistId = "m", isCompilation = true),
            Album("5", "Five", artist = "Bo", artists = listOf(ArtistReference("b", "Bo"))),
            Album("6", "Six")) + (0 until 20).map { Album("x$it", "X", artist = "Z$it", artistId = "z$it") }
        val faces = recentArtists(albums, library)
        assertEquals(listOf("a", "b") + (0 until 8).map { "z$it" }, faces.map { it.id })
        assertEquals("ar-a", faces[0].coverArt); assertEquals("Bo", faces[1].name)
    }

    private val now = ZonedDateTime.of(2026, 10, 22, 15, 0, 0, 0, ZoneId.of("Europe/Zurich")) // a Thursday

    private fun filler(n: Int, make: (Int) -> Album) = (0 until n).map(make)

    @Test fun noSectionsBelowSixtyAlbumsOrForRandom() {
        val albums = filler(59) { Album("$it", "A$it") }
        assertTrue(AlbumSectioner.sections(AlbumSectionKind.TITLE, albums, now).isEmpty())
        assertTrue(AlbumSectioner.sections(AlbumSectionKind.NONE, albums + albums, now).isEmpty())
        assertEquals(1, AlbumSectioner.sections(AlbumSectionKind.TITLE, albums + Album("x", "Ax"), now).size)
    }

    @Test fun recentlyAddedSections() {
        val dates = listOf("2026-10-20T08:00:00Z", "2026-10-19T00:30:00+02:00", "2026-10-03T10:00:00Z",
            "2026-09-30T10:00:00Z", "2026-01-02T10:00:00Z", "2025-12-31T10:00:00Z", "2019-05-05T10:00:00Z", null)
        val albums = dates.mapIndexed { i, d -> Album("d$i", "D$i", created = d) }
        val padded = albums + filler(60) { Album("old$it", "Old", created = "2018-01-01T00:00:00Z") }
        val sections = AlbumSectioner.sections(AlbumSectionKind.ADDED, padded, now, Locale.ENGLISH)
        assertEquals(listOf("This week", "Earlier in October", "September", "January", "2025", "2019", "Unknown date", "2018"),
            sections.map { it.label })
        assertEquals(listOf(2, 1, 1, 1, 1, 1, 1, 60), sections.map { it.count })
    }

    @Test fun recentlyPlayedAddsTodayAndYesterday() {
        val albums = listOf("2026-10-22T09:00:00Z", "2026-10-21T09:00:00Z", "2026-10-19T09:00:00Z", "2026-10-02T09:00:00Z", "2026-08-01T09:00:00Z")
            .mapIndexed { i, d -> Album("p$i", "P", played = d) } + filler(60) { Album("q$it", "Q", played = "2024-03-01T00:00:00Z") }
        assertEquals(listOf("Today", "Yesterday", "This week", "Earlier in October", "August", "2024"),
            AlbumSectioner.sections(AlbumSectionKind.PLAYED, albums, now, Locale.ENGLISH).map { it.label })
    }

    @Test fun recentReleasesYearsThenDecadesThenUnknown() {
        val years = listOf(2026, 2025, 2017, 2016, 1999, 1994, 1987, null)
        val albums = years.mapIndexed { i, y -> Album("r$i", "R", year = y) } + filler(60) { Album("u$it", "U") }
        assertEquals(listOf("2026", "2025", "2017", "2010s", "1990s", "1980s", "Unknown year"),
            AlbumSectioner.sections(AlbumSectionKind.RELEASED, albums, now).map { it.label })
    }

    @Test fun mostPlayedTiersAndTopRatedStars() {
        val plays = listOf(250, 100, 99, 50, 49, 20, 19, 10, 9, 1, 0)
        val albums = plays.mapIndexed { i, p -> Album("f$i", "F", playCount = p) } + filler(60) { Album("z$it", "Z", playCount = 0) }
        val tiers = AlbumSectioner.sections(AlbumSectionKind.FREQUENT, albums, now)
        assertEquals(listOf("100+ plays", "50–99 plays", "20–49 plays", "10–19 plays", "1–9 plays", "Not played"), tiers.map { it.label })
        assertEquals(listOf(2, 2, 2, 2, 2, 61), tiers.map { it.count })
        val rated = (listOf(5, 5, 4, 2, 1) + List(60) { 1 }).mapIndexed { i, r -> Album("t$i", "T", userRating = r) }
        val stars = AlbumSectioner.sections(AlbumSectionKind.RATED, rated, now)
        assertEquals(listOf(5, 4, 2, 1), stars.map { it.stars }); assertEquals(listOf(0, 2, 3, 4), stars.map { it.start })
    }

    @Test fun titleOrderFoldsArticlesAndKeepsSectionsWhole() {
        val albums = listOf(Album("1", "The Wall"), Album("2", "A Night Out"), Album("3", "Abbey Road"), Album("4", "Zed"),
            Album("5", "The Dark Side"), Album("6", "Weird", sortName = "Alpha"), Album("7", "7 Rings"))
        val ordered = AlbumSectioner.titleOrder(albums)
        assertEquals(listOf("7 Rings", "Abbey Road", "Weird", "The Dark Side", "A Night Out", "The Wall", "Zed"), ordered.map { it.name })
    }

    @Test fun filterMatchesDecomposedAccentsAndMapsBackToTheOriginalName() {
        val decomposed = "E\u0301lodie Marchetti"
        val list = listOf(Artist("1", decomposed), Artist("2", "Bjork"))
        val match = ArtistFilter.matches(list, "elodie").single()
        assertEquals(decomposed, match.artist.name)
        assertEquals(0..6, match.range)
        assertEquals(listOf("1"), ArtistFilter.Index(list).matches("MAR").map { it.artist.id })
    }
}
