package io.github.iamtoolino.coda.ui

import io.github.iamtoolino.coda.data.Album
import io.github.iamtoolino.coda.data.SearchResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchCoordinatorTest {
    @Test
    fun `short query clears results without calling loader`() = runBlocking {
        var calls = 0
        val coordinator = SearchCoordinator(
            scope = CoroutineScope(coroutineContext),
            delayBlock = {},
        ) {
            calls++
            SearchResult(album = listOf(Album(id = "album", name = "Album")))
        }

        coordinator.updateQuery("valid")?.join()
        coordinator.updateQuery("v")

        assertEquals(1, calls)
        assertEquals("v", coordinator.state.loadedQuery)
        assertNull(coordinator.state.result)
        assertFalse(coordinator.state.isLoading)
    }

    @Test
    fun `typed query is debounced but explicit refresh is immediate and retains content`() =
        runBlocking {
            val delays = mutableListOf<Long>()
            val loaded = SearchResult(album = listOf(Album(id = "album", name = "Album")))
            var failure: Throwable? = null
            val coordinator = SearchCoordinator(
                scope = CoroutineScope(coroutineContext),
                delayBlock = delays::add,
            ) {
                failure?.let { throw it }
                loaded
            }

            coordinator.updateQuery("album")?.join()
            assertEquals(listOf(350L), delays)
            assertSame(loaded, coordinator.state.result)

            failure = IOException("offline")
            val refresh = requireNotNull(coordinator.refresh())
            assertTrue(coordinator.state.isLoading)
            assertSame(loaded, coordinator.state.result)
            refresh.join()

            assertEquals(listOf(350L), delays)
            assertSame(loaded, coordinator.state.result)
            assertEquals("offline", coordinator.state.errorMessage)
            assertFalse(coordinator.state.isLoading)
        }

    @Test
    fun `late obsolete query cannot replace newest result`() = runBlocking {
        val oldRequest = CompletableDeferred<SearchResult>()
        val fresh = SearchResult(album = listOf(Album(id = "fresh", name = "Fresh")))
        val coordinator = SearchCoordinator(
            scope = CoroutineScope(coroutineContext),
            delayBlock = {},
        ) { query ->
            when (query) {
                "old" -> try {
                    oldRequest.await()
                } catch (_: CancellationException) {
                    SearchResult(album = listOf(Album(id = "stale", name = "Stale")))
                }
                else -> fresh
            }
        }

        coordinator.updateQuery("old")
        yield()
        coordinator.updateQuery("new")?.join()
        yield()

        assertEquals("new", coordinator.state.loadedQuery)
        assertSame(fresh, coordinator.state.result)
    }

    @Test
    fun `session cancellation is not presented as a search failure`() = runBlocking {
        val coordinator = SearchCoordinator(
            scope = CoroutineScope(coroutineContext),
            delayBlock = {},
        ) { throw CancellationException("account changed") }

        val job = requireNotNull(coordinator.updateQuery("query"))
        job.join()

        assertTrue(job.isCancelled)
        assertNull(coordinator.state.errorMessage)
    }
}
