package dev.coda.android.ui.library

import androidx.lifecycle.ViewModelStore
import dev.coda.android.session.*
import dev.coda.android.ui.album.AlbumViewModel
import dev.coda.core.library.SnapshotStore
import dev.coda.core.subsonic.*
import dev.coda.core.testing.FakeSubsonicServer
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Saved copies survive an outage or an expired session, and search falls back to them. */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineSnapshotTest {
    private class Store : CredentialStorage {
        override suspend fun read(): Credentials? = null
        override suspend fun write(value: Credentials) {}
        override suspend fun clear() {}
    }
    private fun snapshots() = SnapshotStore(dev.coda.core.testing.MemoryPageStore())
    private suspend fun awaitCondition(condition: suspend () -> Boolean) = withTimeout(5000) { while (!condition()) delay(10) }
    private fun online(block: suspend (FakeSubsonicServer, SessionRepository, Session) -> Unit) = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            FakeSubsonicServer().use { server ->
                val sessions = SessionRepository(Store(), "test") {}
                sessions.login(server.baseUrl, "synthetic", "synthetic-password")
                block(server, sessions, (sessions.state.value as SessionState.Connected).session)
            }
        } finally { Dispatchers.resetMain() }
    }

    // B06: revisit an album during an outage.
    @Test(timeout = 20000) fun albumRevisitedDuringOutageKeepsItsTracks() = online { server, sessions, session ->
        val store = snapshots()
        val first = AlbumViewModel(session, "album-1", sessions, store)
        val owner = ViewModelStore().apply { put("a", first) }
        awaitCondition { first.state.value.page != null && !first.state.value.loading }
        val tracks = first.state.value.page!!.songs.map { it.id }
        assertTrue(tracks.isNotEmpty())
        owner.clear()
        server.goOffline()
        val second = AlbumViewModel(session, "album-1", sessions, store)
        awaitCondition { !second.state.value.loading }
        assertTrue("Failure is flagged", second.state.value.failed)
        assertEquals("Saved tracks stay", tracks, second.state.value.page!!.songs.map { it.id })
        ViewModelStore().apply { put("b", second) }.clear()
    }

    @Test(timeout = 20000) fun albumWithoutSnapshotStillReportsFailure() = online { server, sessions, session ->
        server.goOffline()
        val model = AlbumViewModel(session, "album-1", sessions, snapshots())
        awaitCondition { !model.state.value.loading }
        assertTrue(model.state.value.failed)
        assertNull(model.state.value.page)
        ViewModelStore().apply { put("c", model) }.clear()
    }

    // B06: revisit an artist during an outage.
    @Test(timeout = 20000) fun artistRevisitedDuringOutageKeepsDiscography() = online { server, sessions, session ->
        val store = snapshots()
        val first = LibraryViewModel(session, sessions, store)
        first.artist("artist-1")
        awaitCondition { first.state.value.artist != null && !first.state.value.loading }
        val count = first.state.value.artist!!.albums.size
        server.goOffline()
        val second = LibraryViewModel(session, sessions, store)
        second.artist("artist-1")
        awaitCondition { !second.state.value.loading }
        assertTrue(second.state.value.failed)
        assertEquals(count, second.state.value.artist!!.albums.size)
        assertEquals(count, second.state.value.projection.oldest.size)
        listOf(first, second).forEach { ViewModelStore().apply { put("m${it.hashCode()}", it) }.clear() }
    }

    // B06: the Artists tab also keeps its list.
    @Test(timeout = 20000) fun artistsListRevisitedDuringOutageIsKept() = online { server, sessions, session ->
        val store = snapshots()
        val first = LibraryViewModel(session, sessions, store)
        first.artists()
        awaitCondition { first.state.value.artists.isNotEmpty() && !first.state.value.loading }
        server.goOffline()
        val second = LibraryViewModel(session, sessions, store)
        second.artists()
        awaitCondition { second.state.value.failed }
        assertEquals(first.state.value.artists, second.state.value.artists)
        listOf(first, second).forEach { ViewModelStore().apply { put("m${it.hashCode()}", it) }.clear() }
    }

    // B08: offline search over items already on the phone.
    @Test(timeout = 20000) fun offlineSearchFindsVisitedItemsAndLabelsThemFromThisPhone() = online { server, sessions, session ->
        val store = snapshots()
        val visit = AlbumViewModel(session, "album-1", sessions, store)
        awaitCondition { visit.state.value.page != null && !visit.state.value.loading }
        val title = visit.state.value.page!!.songs.first().title
        server.goOffline()
        val model = LibraryViewModel(session, sessions, store)
        model.search(title.lowercase())
        awaitCondition { model.state.value.fromPhone }
        val state = model.state.value
        assertFalse(state.failed)
        assertTrue(state.results.songs.any { it.title == title })
        val albumName = visit.state.value.page!!.album.name
        model.search(albumName.uppercase())
        awaitCondition { model.state.value.results.albums.any { it.id == "album-1" } }
        // Nothing visited matches: still "from this phone", with no results rather than an error.
        model.search("zzz-unknown")
        awaitCondition { !model.state.value.pending }
        assertTrue(model.state.value.fromPhone)
        assertEquals(SearchResults(), model.state.value.results)
        // Back online, server results replace the local ones and the label goes away.
        server.goOnline()
        model.search("synthetic")
        awaitCondition { !model.state.value.pending && !model.state.value.fromPhone }
        assertFalse(model.state.value.failed)
        ViewModelStore().apply { put("s", model) }.clear()
        ViewModelStore().apply { put("v", visit) }.clear()
    }

    @Test(timeout = 20000) fun searchWithoutSnapshotsKeepsServerNeededError() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val sessions = SessionRepository(Store(), "test") {}
            sessions.login("http://127.0.0.1:1", "synthetic", "synthetic-password")
            val model = LibraryViewModel((sessions.state.value as SessionState.Connected).session, sessions) { false }
            model.search("anything")
            awaitCondition { model.state.value.failed }
            assertTrue(model.state.value.offline)
            assertFalse(model.state.value.fromPhone)
            ViewModelStore().apply { put("s", model) }.clear()
        } finally { Dispatchers.resetMain() }
    }

    /** A blank query is a live answer at once, so Search's way back ends. */
    @Test(timeout = 20000) fun aBlankSearchCountsAsAnAnswer() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val sessions = SessionRepository(Store(), "test") {}
            sessions.login("http://127.0.0.1:1", "synthetic", "synthetic-password")
            val model = LibraryViewModel((sessions.state.value as SessionState.Connected).session, sessions) { false }
            val before = model.state.value.answers
            model.hold("syn")
            assertEquals(before, model.state.value.answers)
            model.search("")
            assertEquals(before + 1, model.state.value.answers)
            ViewModelStore().apply { put("s", model) }.clear()
        } finally { Dispatchers.resetMain() }
    }

    // B05: a rejected session must not leave an album spinner running over the saved copy.
    @Test(timeout = 20000) fun unauthorizedAlbumRefreshStopsLoadingAndKeepsSavedCopy() = online { server, sessions, session ->
        val store = snapshots()
        val first = AlbumViewModel(session, "album-1", sessions, store)
        awaitCondition { first.state.value.page != null && !first.state.value.loading }
        server.rejectedEndpoints = setOf("getAlbum")
        val second = AlbumViewModel(session, "album-1", sessions, store)
        awaitCondition { !second.state.value.loading }
        assertNotNull(second.state.value.page)
        assertTrue(sessions.state.value is SessionState.NeedsLogin)
        ViewModelStore().apply { put("a", first); put("b", second) }.clear()
    }

    // B06 playback: a result found on this phone queues its saved album when the server is gone.
    @Test(timeout = 20000) fun searchPlaybackFallsBackToSavedAlbumOnlyForConnectivityErrors() = online { server, sessions, session ->
        val store = snapshots()
        store.putAlbum(session.client.getAlbum("album-1"))
        val song = store.album("album-1")!!.songs[1]
        server.goOffline()
        val queue = resolveSearchPlayback(session.client, song) { store.album(it) }
        assertEquals(1, queue.startIndex)
        try { resolveSearchPlayback(session.client, Song("x", "X", albumId = "missing")) { null }; fail() } catch (_: SubsonicError) { }
    }

    // Search playback keeps the album page it fetched, in the format opening the album saves.
    @Test(timeout = 20000) fun searchPlaybackSavesTheAlbumPageItFetched() = online { _, _, session ->
        val store = snapshots()
        assertNull(store.album("album-1"))
        val song = session.client.getAlbum("album-1").songs[1]
        val queue = resolveSearchPlayback(session.client, song, { store.putAlbum(it) }) { store.album(it) }
        assertEquals(1, queue.startIndex)
        assertEquals(queue.songs.map { it.id }, store.album("album-1")!!.songs.map { it.id })
    }
}
