package io.github.iamtoolino.coda.player

import io.github.iamtoolino.coda.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSnapshotStoreTest {
    @Test fun `whole queue cache mode and stream variants survive restoration`() {
        val snapshot = PlaybackSnapshot("fixture", listOf(Song("one", "One")), 0, 15L,
            cacheWholeQueue = true, cacheVariants = listOf("opus"))
        assertEquals(snapshot, PlaybackSnapshotCodec.decode(PlaybackSnapshotCodec.encode(snapshot)))
    }
    @Test fun `previous prototype opt in migrates to whole queue mode`() {
        val snapshot = requireNotNull(PlaybackSnapshotCodec.decode(
            """{"cacheNamespace":"fixture","songs":[],"currentIndex":0,"positionMs":0,
                "cacheThrough":10,"cacheVariants":["opus"]}""",
        ))
        assertEquals(true, snapshot.cacheWholeQueue)
        assertEquals(listOf("opus"), snapshot.cacheVariants)
    }
    @Test
    fun `snapshot codec preserves queue metadata and position`() {
        val snapshot = PlaybackSnapshot(
            cacheNamespace = "account-1",
            songs = listOf(
                Song(
                    id = "song-1",
                    title = "Blind",
                    album = "Korn",
                    artist = "Korn",
                    albumId = "album-1",
                    artistId = "artist-1",
                    coverArt = "track-cover",
                    canonicalAlbumCoverArt = "album-cover",
                    track = 1,
                    duration = 258,
                    suffix = "flac",
                    bitDepth = 24,
                    samplingRate = 96_000,
                ),
            ),
            currentIndex = 0,
            positionMs = 42_500,
        )

        assertEquals(snapshot, PlaybackSnapshotCodec.decode(PlaybackSnapshotCodec.encode(snapshot)))
    }

    @Test
    fun `legacy snapshot with embedded cover restores using album identity`() {
        val snapshot = requireNotNull(PlaybackSnapshotCodec.decode(
            """{"cacheNamespace":"fixture","songs":[{"id":"track","title":"Track",
                "albumId":"album","coverArt":"embedded"}],"currentIndex":0,"positionMs":1234}""",
        ))
        assertEquals("album", snapshot.songs.single().albumArtworkId)
        assertEquals(1234L, snapshot.positionMs)
        assertEquals(false, snapshot.cacheWholeQueue)
        assertEquals(emptyList<String>(), snapshot.cacheVariants)
    }

    @Test
    fun `snapshot codec rejects malformed data`() {
        assertNull(PlaybackSnapshotCodec.decode("not json"))
    }
}
