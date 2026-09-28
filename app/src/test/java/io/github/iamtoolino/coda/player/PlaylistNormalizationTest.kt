package io.github.iamtoolino.coda.player

import io.github.iamtoolino.coda.data.NORMALIZED_STREAM_FORMAT
import io.github.iamtoolino.coda.data.Playlist
import io.github.iamtoolino.coda.data.Song
import io.github.iamtoolino.coda.data.withPlaybackPolicy
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistNormalizationTest {
    @Test fun `explicit directive applies to each playlist occurrence only`() {
        val song = Song("same", "Track")
        val playlist = Json.decodeFromString<Playlist>(
            """{"id":"fixture","name":"Mix","comment":"Radio [coda:replaygain=track] notes"}""",
        ).copy(entry = listOf(song, song)).withPlaybackPolicy()
        assertTrue(playlist.entry.all { it.normalizedStream })
        assertFalse(song.normalizedStream)
        for (comment in listOf(null, "", "[coda:replaygain=album]", "replaygain=track")) {
            assertFalse(playlist.copy(comment = comment).withPlaybackPolicy().entry.any { it.normalizedStream })
        }
    }

    @Test fun `normalization overrides network and fully cached original`() {
        for (mobile in listOf(false, true)) {
            for (cached in listOf(false, true)) {
                assertEquals(NORMALIZED_STREAM_FORMAT, upcomingStreamVariant(mobile, cached, true))
            }
        }
    }

    @Test fun `mixed occurrences survive snapshot including legacy default`() {
        val song = Song("same", "Track")
        val snapshot = PlaybackSnapshot("fixture", listOf(song, song.copy(normalizedStream = true)), 1, 1234)
        assertEquals(snapshot, PlaybackSnapshotCodec.decode(PlaybackSnapshotCodec.encode(snapshot)))
        val legacy = requireNotNull(PlaybackSnapshotCodec.decode(
            """{"cacheNamespace":"fixture","songs":[{"id":"same","title":"Track"}],"currentIndex":0,"positionMs":0}""",
        ))
        assertFalse(legacy.songs.single().normalizedStream)
        assertTrue(streamCacheKey("fixture", song.id, "raw") !=
            streamCacheKey("fixture", song.id, NORMALIZED_STREAM_FORMAT))
    }
}
