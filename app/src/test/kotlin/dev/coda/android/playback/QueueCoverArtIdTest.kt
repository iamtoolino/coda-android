package dev.coda.android.playback

import dev.coda.core.subsonic.Album
import dev.coda.core.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The cover id a queue item carries never falls back to an id that fetches the server's "no artwork" placeholder. */
class QueueCoverArtIdTest {
    private val song = Song("s1", "Song", albumId = "al-1", coverArt = "mf-s1")

    @Test fun albumWithArtDecides() {
        // The album's cover key is its id, the same key the song alone gives (one cache entry per album).
        assertEquals("al-1", QueueMediaItems.coverArtId(song, Album("al-1", "A", coverArt = "cover-1")))
    }

    @Test fun knownAlbumWithoutArtGivesNoIdNotTheSongsOwn() {
        assertNull(QueueMediaItems.coverArtId(song, Album("al-1", "A")))
    }

    @Test fun withoutAnAlbumTheSongDecides() {
        assertEquals("al-1", QueueMediaItems.coverArtId(song, null))
        assertNull(QueueMediaItems.coverArtId(song.copy(albumHasNoCover = true), null))
    }
}
