package io.github.iamtoolino.coda.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodaMediaIdsTest {
    @Test
    fun idsRoundTripWithoutChangingServerId() {
        val id = "album-id-123"
        assertEquals(id, CodaMediaIds.albumId(CodaMediaIds.album(id)))
        assertEquals(id, CodaMediaIds.artistId(CodaMediaIds.artist(id)))
        assertEquals(id, CodaMediaIds.playlistId(CodaMediaIds.playlist(id)))
        assertEquals(id, CodaMediaIds.songId(CodaMediaIds.song(id)))
        assertEquals("A", CodaMediaIds.artistBucketLabel(CodaMediaIds.artistBucket("A")))
    }

    @Test
    fun wrongKindsAndBlankIdsAreRejected() {
        assertNull(CodaMediaIds.songId(CodaMediaIds.album("album")))
        assertNull(CodaMediaIds.songId("coda:song:"))
    }

    @Test
    fun audioCacheKeysAreIsolatedByAccountAndVariant() {
        val firstAccount = streamCacheKey("account-a", "song-1", "raw")

        assertEquals("stream:account-a:song-1:raw", firstAccount)
        assertNotEquals(firstAccount, streamCacheKey("account-b", "song-1", "raw"))
        assertNotEquals(firstAccount, streamCacheKey("account-a", "song-1", "opus"))
    }
}
