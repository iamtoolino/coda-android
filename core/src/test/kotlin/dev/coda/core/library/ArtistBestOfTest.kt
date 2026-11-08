package dev.coda.core.library

import dev.coda.core.subsonic.Album
import org.junit.Assert.*
import org.junit.Test

class ArtistBestOfTest {
    private fun album(id: String, rating: Int? = null, plays: Int? = null, year: Int? = null) =
        Album(id, id, userRating = rating, playCount = plays, year = year)
    @Test fun lowRatingsNeverQualify() {
        assertEquals(listOf("three"), artistBestOf(listOf(album("one", 1), album("two", 2), album("three", 3))).map { it.id })
        assertTrue(artistBestOf(listOf(album("one", 1), album("two", 2))).isEmpty())
        assertTrue(artistBestOf(emptyList()).isEmpty())
    }
    @Test fun unratedFallbackOnlyWithoutHighlyRatedAlbums() {
        val unrated = listOf(album("null"), album("zero", 0))
        assertEquals(unrated, artistBestOf(unrated + album("excluded", 2)))
        val rated = album("rated", 3)
        assertEquals(listOf(rated), artistBestOf(unrated + rated))
    }
    @Test fun ratingThenPlayCountThenNewestYearWithStableTies() {
        val albums = listOf(album("three", 3, 99, 2026), album("fourOld", 4, 5, 2000),
            album("fourNew", 4, 5, 2020), album("fourPlayed", 4, 6, 1990), album("five", 5), album("tie", 4, 5, 2020))
        assertEquals(listOf("five", "fourPlayed", "fourNew", "tie", "fourOld", "three"), artistBestOf(albums).map { it.id })
    }
    @Test fun missingYearAndCountSortLast() {
        assertEquals(listOf("known", "noYear", "noCount"), artistBestOf(listOf(album("noCount", 4, year = 2026),
            album("noYear", 4, 1), album("known", 4, 1, 2020))).map { it.id })
    }
}
