package io.github.iamtoolino.coda.ui

import io.github.iamtoolino.coda.data.Album
import io.github.iamtoolino.coda.data.Artist
import io.github.iamtoolino.coda.data.Playlist
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HomeLoadingTest {
    @Test
    fun `refresh and failure retain previously loaded section content`() {
        val loaded = HomeSectionState<List<String>>().loaded(listOf("existing"))

        val refreshing = loaded.loading()
        val failed = refreshing.failed(IOException("offline"))

        assertEquals(listOf("existing"), refreshing.value)
        assertEquals(listOf("existing"), failed.value)
        assertFalse(failed.isLoading)
        assertEquals("offline", failed.errorMessage)
    }

    @Test
    fun `network failures retry with bounded backoff`() = runBlocking {
        var attempts = 0
        val delays = mutableListOf<Long>()

        val value = retryNetworkRequest(
            retryDelaysMillis = listOf(500L, 1_500L),
            delayBlock = delays::add,
        ) {
            attempts++
            if (attempts < 3) throw IOException("temporary")
            "loaded"
        }

        assertEquals("loaded", value)
        assertEquals(3, attempts)
        assertEquals(listOf(500L, 1_500L), delays)
    }

    @Test
    fun `permanent failures do not retry`() = runBlocking {
        var attempts = 0
        val expected = IllegalStateException("invalid response")

        try {
            retryNetworkRequest(retryDelaysMillis = listOf(0L, 0L), delayBlock = {}) {
                attempts++
                throw expected
            }
            fail("Expected permanent failure")
        } catch (actual: IllegalStateException) {
            assertSame(expected, actual)
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `cancellation does not retry`() = runBlocking {
        var attempts = 0

        try {
            retryNetworkRequest(retryDelaysMillis = listOf(0L, 0L), delayBlock = {}) {
                attempts++
                throw CancellationException("stale session")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected.
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `one failed section does not block successful sections`() = runBlocking {
        val coordinator = HomeCoordinator(
            scope = CoroutineScope(coroutineContext),
            dataSource = object : HomeDataSource {
                override suspend fun newestAlbums() = listOf(
                    Album(id = "album", name = "Newest", artist = "Artist", artistId = "artist"),
                )

                override suspend fun artists() = listOf(Artist(id = "artist", name = "Artist"))

                override suspend fun recentReleases(): List<Album> =
                    throw IllegalStateException("section failed")

                override suspend fun recentlyPlayed() = listOf(
                    Album(id = "played", name = "Played"),
                )

                override suspend fun playlists() = listOf(Playlist(id = "playlist", name = "Mix"))
            },
        )

        coordinator.refreshAll().join()

        assertEquals("album", coordinator.newest.value?.single()?.id)
        assertEquals("artist", coordinator.artists.value?.single()?.id)
        assertEquals("played", coordinator.recentlyPlayed.value?.single()?.id)
        assertEquals("playlist", coordinator.playlists.value?.single()?.id)
        assertNull(coordinator.recentReleases.value)
        assertEquals("section failed", coordinator.recentReleases.errorMessage)
        assertFalse(coordinator.recentReleases.isLoading)
        assertTrue(coordinator.newest.errorMessage == null)
    }
}
