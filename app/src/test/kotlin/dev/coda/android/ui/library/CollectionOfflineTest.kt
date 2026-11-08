package dev.coda.android.ui.library

import androidx.lifecycle.ViewModelStore
import dev.coda.android.session.*
import dev.coda.core.library.SnapshotStore
import dev.coda.core.testing.FakeSubsonicServer
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Albums, Playlists and playlist detail keep their saved content during an outage. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CollectionOfflineTest {
    private class Store : CredentialStorage {
        override suspend fun read(): Credentials? = null
        override suspend fun write(value: Credentials) {}
        override suspend fun clear() {}
    }
    private fun snapshots() = SnapshotStore(dev.coda.core.testing.MemoryPageStore())
    private suspend fun until(condition: suspend () -> Boolean) = withTimeout(5000) { while (!condition()) delay(10) }
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
    private fun own(model: CollectionViewModel) = ViewModelStore().apply { put("m${model.hashCode()}", model) }

    @Test(timeout = 20000) fun albumsRevisitedDuringOutageKeepTheirList() = online { server, sessions, session ->
        val store = snapshots()
        val first = CollectionViewModel(session, sessions, store)
        first.albums()
        until { !first.state.value.loading }
        val ids = first.state.value.albums.map { it.id }
        assertTrue(ids.isNotEmpty())
        // Publication precedes snapshot I/O; wait for the saved copy this test reopens.
        until { store.albums()?.map { it.id } == ids }
        server.goOffline()
        val second = CollectionViewModel(session, sessions, store)
        second.albums()
        until { !second.state.value.loading }
        assertTrue("Failure is flagged so the stale line shows", second.state.value.failed)
        assertEquals(ids, second.state.value.albums.map { it.id })
        own(first).clear(); own(second).clear()
    }

    @Test(timeout = 20000) fun playlistsAndDetailRevisitedDuringOutageKeepTheirContent() = online { server, sessions, session ->
        val store = snapshots()
        val first = CollectionViewModel(session, sessions, store)
        first.playlists()
        until { !first.state.value.loading }
        val detail = CollectionViewModel(session, sessions, store)
        detail.playlist("playlist-1")
        until { !detail.state.value.loading }
        val tracks = detail.state.value.playlist!!.songs.map { it.id }
        assertEquals(2, tracks.size)
        server.goOffline()
        val list = CollectionViewModel(session, sessions, store)
        list.playlists()
        until { !list.state.value.loading }
        assertTrue(list.state.value.failed)
        assertEquals("Synthetic mix", list.state.value.playlists.single().name)
        val again = CollectionViewModel(session, sessions, store)
        again.playlist("playlist-1")
        until { !again.state.value.loading }
        assertTrue(again.state.value.failed)
        assertEquals("Synthetic mix", again.state.value.playlist!!.playlist.name)
        assertEquals(tracks, again.state.value.playlist!!.songs.map { it.id })
        listOf(first, detail, list, again).forEach { own(it).clear() }
    }

    @Test(timeout = 20000) fun withoutASavedCopyAnOutageStillShowsTheErrorState() = online { server, sessions, session ->
        server.goOffline()
        val model = CollectionViewModel(session, sessions, snapshots())
        model.albums()
        until { !model.state.value.loading }
        assertTrue(model.state.value.failed)
        assertTrue(model.state.value.albums.isEmpty())
        own(model).clear()
    }

    @Test(timeout = 20000) fun expiredSessionStopsLoadingAndKeepsTheSavedCopy() = online { server, sessions, session ->
        val store = snapshots()
        val first = CollectionViewModel(session, sessions, store)
        first.playlists()
        until { !first.state.value.loading }
        server.rejectedEndpoints = setOf("getPlaylists")
        val second = CollectionViewModel(session, sessions, store)
        second.playlists()
        until { !second.state.value.loading }
        assertTrue(second.state.value.failed)
        assertEquals(1, second.state.value.playlists.size)
        own(first).clear(); own(second).clear()
    }
}
