package dev.coda.android.ui.library

import dev.coda.core.subsonic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Item 1 (paged album lists) and item 7 (reload when the network is back). */
@OptIn(ExperimentalCoroutinesApi::class)
class CollectionPagingTest {
    private fun library(count: Int) = (0 until count).map { Album("a%04d".format(it), "Album %04d".format(it)) }
    private fun check(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block() } finally { Dispatchers.resetMain() }
    }
    private class Server(val all: List<Album>) {
        val requests = mutableListOf<Triple<AlbumListType, Int, Int>>()
        var down = false
        suspend fun page(type: AlbumListType, offset: Int, size: Int): AlbumListPage {
            requests += Triple(type, offset, size)
            if (down) throw SubsonicError.Network(java.io.IOException())
            return AlbumListPage(all.drop(offset).take(size), null)
        }
    }
    private fun model(server: Server, recovered: kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.emptyFlow(), isCurrent: () -> Boolean = { true }) =
        CollectionViewModel(isCurrent, {}, server::page, { emptyList() }, { error("unused") }, null, 100, recovered)

    @Test fun firstVisitFetchesOnePageOfHundredNotTheWholeLibrary() = check {
        val server = Server(library(250))
        val model = model(server)
        model.albums(); advanceUntilIdle()
        assertEquals(listOf(Triple(AlbumListType.ALPHABETICAL, 0, 100)), server.requests)
        assertEquals(100, model.state.value.albums.size)
        assertFalse(model.state.value.complete)
        assertFalse(model.state.value.loading)
    }

    @Test fun scrollingPagesInTheRestInAlphabeticalOrderWithoutDuplicates() = check {
        val server = Server(library(250))
        val model = model(server)
        model.albums(); advanceUntilIdle()
        model.loadMore(); model.loadMore(); advanceUntilIdle() // a second call while one is running adds no request
        assertEquals(listOf(0, 100), server.requests.map { it.second })
        model.loadMore(); advanceUntilIdle()
        assertEquals(listOf(0, 100, 200), server.requests.map { it.second })
        assertTrue(model.state.value.complete)
        assertEquals(server.all.map { it.id }, model.state.value.albums.map { it.id })
        model.loadMore(); advanceUntilIdle()
        assertEquals("A complete list asks for nothing more", 3, server.requests.size)
    }

    @Test fun anExactMultipleEndsWithAnEmptyPage() = check {
        val server = Server(library(200))
        val model = model(server)
        model.albums(); advanceUntilIdle(); model.loadMore(); advanceUntilIdle()
        assertFalse(model.state.value.complete)
        model.loadMore(); advanceUntilIdle()
        assertTrue(model.state.value.complete)
        assertEquals(200, model.state.value.albums.size)
    }

    @Test fun eachHomeSortRequestsItsOwnListType() = check {
        for (type in listOf(AlbumListType.NEWEST, AlbumListType.RECENT_RELEASES, AlbumListType.RECENTLY_PLAYED)) {
            val server = Server(library(30))
            val model = model(server)
            model.albums(type); advanceUntilIdle()
            assertEquals(listOf(Triple(type, 0, 100)), server.requests)
            assertTrue(model.state.value.complete)
        }
    }

    @Test fun aFailedPageKeepsTheListAndRetriesWhenTheNetworkIsBack() = check {
        val server = Server(library(250))
        val recovered = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val model = model(server, recovered)
        model.albums(); advanceUntilIdle()
        server.down = true
        model.loadMore(); advanceUntilIdle()
        assertTrue(model.state.value.moreFailed)
        assertEquals(100, model.state.value.albums.size)
        server.down = false
        recovered.tryEmit(Unit); advanceUntilIdle()
        assertFalse("Retry line goes away without a tap", model.state.value.moreFailed)
        assertEquals(200, model.state.value.albums.size)
    }

    @Test fun aFailedFirstPageReloadsItselfWhenTheNetworkIsBack() = check {
        val server = Server(library(10)).also { it.down = true }
        val recovered = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val model = model(server, recovered)
        model.albums(); advanceUntilIdle()
        assertTrue(model.state.value.failed)
        server.down = false
        recovered.tryEmit(Unit); advanceUntilIdle()
        assertFalse(model.state.value.failed)
        assertEquals(10, model.state.value.albums.size)
    }
}
