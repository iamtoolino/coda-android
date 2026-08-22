package io.github.iamtoolino.coda.ui

import io.github.iamtoolino.coda.data.Album
import io.github.iamtoolino.coda.data.AlbumListType
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumPagingCoordinatorTest {
    @Test
    fun `first page publishes without waiting for the rest of the library`() = runBlocking {
        val requests = mutableListOf<Pair<Int, Int>>()
        val coordinator = coordinator(this, pageSize = 3) { _, offset, size ->
            requests += offset to size
            albums(offset until offset + size)
        }

        coordinator.load().join()

        assertEquals(listOf(0 to 3), requests)
        assertEquals(listOf("0", "1", "2"), coordinator.state.albums?.map(Album::id))
        assertFalse(coordinator.state.endReached)

        coordinator.loadNext()?.join()
        assertEquals(listOf(0 to 3, 3 to 3), requests)
        assertEquals(6, coordinator.state.albums?.size)
    }

    @Test
    fun `partial page deduplicates albums and marks the end`() = runBlocking {
        val coordinator = coordinator(this, pageSize = 3) { _, offset, _ ->
            if (offset == 0) albums(0..2) else albums(listOf(2, 3))
        }

        coordinator.load().join()
        coordinator.loadNext()?.join()

        assertEquals(listOf("0", "1", "2", "3"), coordinator.state.albums?.map(Album::id))
        assertTrue(coordinator.state.endReached)
    }

    @Test
    fun `sort change rejects a late append result`() = runBlocking {
        val appendResult = CompletableDeferred<List<Album>>()
        val coordinator = coordinator(this, pageSize = 2) { type, offset, _ ->
            if (offset > 0) {
                try {
                    appendResult.await()
                } catch (_: CancellationException) {
                    withContext(NonCancellable) { appendResult.await() }
                }
            } else {
                if (type == AlbumListType.ALPHABETICAL) albums(0..1) else albums(10..11)
            }
        }

        coordinator.load().join()
        coordinator.loadNext()
        yield()
        coordinator.load(AlbumListType.NEWEST).join()
        appendResult.complete(albums(2..3))
        yield()

        assertEquals(AlbumListType.NEWEST, coordinator.state.type)
        assertEquals(listOf("10", "11"), coordinator.state.albums?.map(Album::id))
        assertFalse(coordinator.state.isAppending)
    }

    @Test
    fun `repeated full page becomes a retryable append error`() = runBlocking {
        val coordinator = coordinator(this, pageSize = 2) { _, _, _ -> albums(0..1) }

        coordinator.load().join()
        coordinator.loadNext()?.join()

        assertEquals(listOf("0", "1"), coordinator.state.albums?.map(Album::id))
        assertEquals(
            "Album pagination made no progress at offset 2",
            coordinator.state.appendErrorMessage,
        )
    }

    @Test
    fun `append failure retains content and requires an explicit retry`() = runBlocking {
        var failAppend = true
        val coordinator = coordinator(this, pageSize = 2) { _, offset, _ ->
            if (offset == 0) albums(0..1)
            else if (failAppend) throw IOException()
            else albums(listOf(2))
        }

        coordinator.load().join()
        coordinator.loadNext()?.join()

        assertEquals(listOf("0", "1"), coordinator.state.albums?.map(Album::id))
        assertEquals("Network request failed", coordinator.state.appendErrorMessage)
        assertFalse(
            shouldLoadNextAlbumPage(
                lastVisibleAlbumIndex = 1,
                loadedAlbumCount = 2,
                isLoading = false,
                endReached = false,
                hasAppendError = true,
            ),
        )

        failAppend = false
        coordinator.loadNext()?.join()
        assertEquals(listOf("0", "1", "2"), coordinator.state.albums?.map(Album::id))
        assertNull(coordinator.state.appendErrorMessage)
        assertTrue(coordinator.state.endReached)
    }

    @Test
    fun `prefetch begins near the loaded boundary`() {
        assertFalse(shouldLoadNextAlbumPage(59, 90, false, false, false))
        assertTrue(shouldLoadNextAlbumPage(60, 90, false, false, false))
        assertFalse(shouldLoadNextAlbumPage(89, 90, true, false, false))
        assertFalse(shouldLoadNextAlbumPage(89, 90, false, true, false))
    }

    private fun coordinator(
        scope: CoroutineScope,
        pageSize: Int,
        loader: suspend (AlbumListType, Int, Int) -> List<Album>,
    ) = AlbumPagingCoordinator(
        scope = scope,
        initialType = AlbumListType.ALPHABETICAL,
        pageSize = pageSize,
        loader = loader,
    )

    private fun albums(indices: Iterable<Int>): List<Album> = indices.map { index ->
        Album(id = index.toString(), name = "Album $index", artist = "Artist")
    }
}
