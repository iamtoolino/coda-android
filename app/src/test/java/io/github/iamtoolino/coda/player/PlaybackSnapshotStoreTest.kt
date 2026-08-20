package io.github.iamtoolino.coda.player

import io.github.iamtoolino.coda.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSnapshotStoreTest {
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
                    coverArt = "cover-1",
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
    fun `snapshot codec rejects malformed data`() {
        assertNull(PlaybackSnapshotCodec.decode("not json"))
    }
}
