package io.github.iamtoolino.coda.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkPolicyTest {
    @Test
    fun `phone and car use the consolidated source sizes`() {
        assertEquals(320, ArtworkSizes.CAR_BROWSE_THUMBNAIL)
        assertEquals(420, ArtworkSizes.PLAYLIST_THUMBNAIL)
        assertEquals(420, ArtworkSizes.ALBUM_GRID)
        assertEquals(500, ArtworkSizes.ARTIST_THUMBNAIL)
        assertEquals(600, ArtworkSizes.ALBUM_CARD)
        assertEquals(1_200, ArtworkSizes.HERO)
    }

    @Test
    fun `same navidrome source has one canonical encoded cache key`() {
        val first = navidromeArtworkSource("account", 4, "cover-1", 600, "url-a")!!
        val second = navidromeArtworkSource("account", 4, "cover-1", 600, "url-b")!!

        assertEquals(first.diskCacheKey, second.diskCacheKey)
        assertEquals(first.memoryCacheKey, second.memoryCacheKey)
    }

    @Test
    fun `manual refresh generation invalidates source keys`() {
        val old = navidromeArtworkSource("account", 4, "cover-1", 600, "url")!!
        val refreshed = navidromeArtworkSource("account", 5, "cover-1", 600, "url")!!

        assertNotEquals(old.diskCacheKey, refreshed.diskCacheKey)
        assertNotEquals(old.memoryCacheKey, refreshed.memoryCacheKey)
    }

    @Test
    fun `external artist bytes are stored once across display sizes`() {
        val row = externalArtistArtworkSource("account", 2, "artist-1", 500, "url")!!
        val hero = externalArtistArtworkSource("account", 2, "artist-1", 1_200, "url")!!

        assertEquals(row.diskCacheKey, hero.diskCacheKey)
        assertNotEquals(row.memoryCacheKey, hero.memoryCacheKey)
    }

    @Test
    fun `playback content uri does not duplicate the car artwork disk entry`() {
        val playback = localPlaybackArtworkSource("account", 1, "cover-1", "content://cover")!!

        assertNull(playback.diskCacheKey)
    }
}
