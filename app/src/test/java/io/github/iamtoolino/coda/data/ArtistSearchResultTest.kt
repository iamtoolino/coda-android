package io.github.iamtoolino.coda.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistSearchResultTest {
    @Test
    fun `search excludes participation-only artists but preserves missing counts`() {
        val artists = listOf(
            Artist(id = "album-artist", name = "Album Artist", albumCount = 8),
            Artist(id = "performer", name = "Album Artist, Performer", albumCount = 0),
            Artist(id = "legacy", name = "Legacy Server Artist"),
        )

        assertEquals(
            listOf("album-artist", "legacy"),
            artists.filter(Artist::isAlbumArtistSearchResult).map(Artist::id),
        )
    }
}
