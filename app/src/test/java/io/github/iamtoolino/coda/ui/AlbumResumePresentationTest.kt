package io.github.iamtoolino.coda.ui

import io.github.iamtoolino.coda.data.Album
import io.github.iamtoolino.coda.data.AlbumPage
import io.github.iamtoolino.coda.data.AlbumResumeItem
import io.github.iamtoolino.coda.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumResumePresentationTest {
    private val page = AlbumPage(
        Album("album", "Album", "Artist"),
        (1..4).map { Song("song$it", "Track $it", albumId = "album", discNumber = if (it < 3) 1 else 2) },
    )
    private fun marker(target: String) = AlbumResumeItem(page.album, "song1", target, "")

    @Test fun `first middle disc boundary and final targets use loaded canonical order`() {
        page.songs.forEachIndexed { index, song ->
            assertEquals(index, albumResumeIndex(page, listOf(marker(song.id)), null))
        }
    }

    @Test fun `current entry hides treatment regardless of playing paused or restored state`() {
        assertNull(albumResumeIndex(page, listOf(marker("song2")), "album"))
        // An album elsewhere in the queue must not suppress its marker.
        assertEquals(1, albumResumeIndex(page, listOf(marker("song2")), "another-album"))
    }

    @Test fun `missing page bookmark or target never falls back to first track`() {
        assertNull(albumResumeIndex(null, listOf(marker("song2")), null))
        assertNull(albumResumeIndex(page, emptyList(), null))
        assertNull(albumResumeIndex(page, listOf(marker("missing")), null))
        assertNull(albumResumeIndex(page.copy(songs = emptyList()), listOf(marker("song2")), null))
        assertNull(albumResumeIndex(page.copy(album = page.album.copy(id = "other")), listOf(marker("song2")), null))
    }
}
