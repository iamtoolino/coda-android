package dev.coda.core.library

import dev.coda.core.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** An offline playlist's cover is a stored one, its own or its first song's with a stored album cover. */
class PlaylistCoverTest {
    private fun song(id: String, albumId: String?, noCover: Boolean = false) =
        Song(id, "Song $id", albumId = albumId, coverArt = "mf-$id", albumHasNoCover = noCover)
    private val songs = listOf(song("s-1", "al-1"), song("s-2", "al-2"), song("s-3", "al-3"), song("s-4", "al-2"))

    @Test fun theFirstSongInPlaylistOrderWithAStoredCoverWins() {
        assertEquals("al-2", PlaylistCover.offline(null, songs.asSequence()) { it in setOf("al-2", "al-3") })
        assertEquals("al-1", PlaylistCover.offline(null, songs.asSequence()) { it in setOf("al-1", "al-3") })
        assertEquals("al-3", PlaylistCover.offline(null, songs.asReversed().asSequence()) { it in setOf("al-1", "al-3") })
    }

    @Test fun songsWithoutAStoredCoverAreSkipped() {
        // al-1 not stored; s-2's album known to have no art is skipped even though its id is stored.
        val list = listOf(song("s-1", "al-1"), song("s-2", "al-2", noCover = true), song("s-3", "al-3"))
        assertEquals("al-3", PlaylistCover.offline(null, list.asSequence()) { it == "al-2" || it == "al-3" })
    }

    @Test fun theOwnCoverWinsOnlyWhenStored() {
        assertEquals("pl-1", PlaylistCover.offline("pl-1", songs.asSequence()) { it == "pl-1" || it == "al-1" })
        assertEquals("al-1", PlaylistCover.offline("pl-1", songs.asSequence()) { it == "al-1" })
    }

    @Test fun nothingStoredMeansThePlaceholder() {
        assertNull(PlaylistCover.offline("pl-1", songs.asSequence()) { false })
        assertNull(PlaylistCover.offline(null, emptySequence()) { true })
    }

    @Test fun theHeaderTakesTheStoredCoversInOrderEachOnce() {
        assertEquals(listOf("al-2", "al-3"), PlaylistCover.storedSongCovers(songs.asSequence()) { it != "al-1" }.toList())
    }

    @Test fun storedChecksStopAtTheFirstHit() {
        val asked = mutableListOf<String>()
        PlaylistCover.offline(null, songs.asSequence()) { asked += it; it == "al-2" }
        assertEquals(listOf("al-1", "al-2"), asked)
    }
}
