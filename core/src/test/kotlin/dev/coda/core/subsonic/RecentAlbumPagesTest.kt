package dev.coda.core.subsonic

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentAlbumPagesTest {
    private fun page(id: String) = AlbumPage(Album(id, id), listOf(Song("$id-1", "one", albumId = id)))

    @Test fun samePageAnswersAgainUntilItExpires() = runTest {
        var now = 0L
        val calls = mutableListOf<String>()
        val pages = RecentAlbumPages({ calls += it; page(it) }, ttlMs = 1_000, nowMs = { now })
        val first = pages.get("a")
        assertSame(first, pages.get("a"))
        pages.get("b")
        assertEquals(listOf("a", "b"), calls)
        now = 1_000
        pages.get("a")
        assertEquals(listOf("a", "b", "a"), calls)
    }

    @Test fun concurrentRequestsShareOneCall() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val pages = RecentAlbumPages({ calls++; gate.await(); page(it) })
        val one = async { pages.get("a") }
        val two = async { pages.get("a") }
        yield()
        gate.complete(Unit)
        assertSame(one.await(), two.await())
        assertEquals(1, calls)
    }

    @Test fun failuresAreNotKeptAndACancelledOwnerLetsTheWaiterFetch() = runTest {
        var calls = 0
        val failing = RecentAlbumPages({ calls++; throw SubsonicError.Server(70, "not found") })
        repeat(2) { assertTrue(runCatching { failing.get("a") }.exceptionOrNull() is SubsonicError.Server) }
        assertEquals(2, calls)

        val gate = CompletableDeferred<Unit>()
        var fetched = 0
        val pages = RecentAlbumPages({ fetched++; if (fetched == 1) gate.await(); page(it) })
        val owner = async { pages.get("a") }
        yield()
        val waiter = async { pages.get("a") }
        yield()
        owner.cancel()
        assertEquals("a", waiter.await().album.id)
        assertEquals(2, fetched)
    }

    @Test fun keepsOnlyTheMostRecentAlbums() = runTest {
        val calls = mutableListOf<String>()
        val pages = RecentAlbumPages({ calls += it; page(it) }, capacity = 2)
        pages.get("a"); pages.get("b"); pages.get("a"); pages.get("c") // b is the least recently used
        pages.get("a"); pages.get("b")
        assertEquals(listOf("a", "b", "c", "b"), calls)
    }
}
