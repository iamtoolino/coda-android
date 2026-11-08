package dev.coda.android.ui.library

import androidx.lifecycle.ViewModelStore
import dev.coda.android.session.*
import dev.coda.core.subsonic.*
import dev.coda.core.testing.FakeSubsonicServer
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRegressionTest {
    private class Store : CredentialStorage {
        override suspend fun read(): Credentials? = null
        override suspend fun write(value: Credentials) {}
        override suspend fun clear() {}
    }
    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(5000) {
        while (!condition()) delay(10)
    }
    @Test(timeout = 15000) fun pagePublishesBeforeSupplementarySearchAndClearingCancelsIt() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            FakeSubsonicServer().use { server ->
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                server.artistAlbums = listOf(Album("album-1", "Own", artistId = "artist-1"))
                server.searchSongs = listOf(Song("guest", "Guest", artistId = "artist-1", albumId = "album-2"))
                server.beforeSearch = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                val sessions = SessionRepository(Store(), "test") {}
                sessions.login(server.baseUrl, "synthetic", "synthetic-password")
                val session = (sessions.state.value as SessionState.Connected).session
                val model = LibraryViewModel(session, sessions)
                val store = ViewModelStore().apply { put("model", model) }
                try {
                    model.artist("artist-1")
                    assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                    assertNotNull(model.state.value.artist)
                    assertFalse(model.state.value.loading)
                    store.clear()
                    release.countDown()
                    delay(100)
                    assertTrue(model.state.value.projection.appearances.isEmpty())
                } finally { release.countDown(); store.clear() }
            }
        } finally { Dispatchers.resetMain() }
    }
    @Test(timeout = 15000) fun supplementaryFailureKeepsPageAndOldSessionCannotPublish() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            FakeSubsonicServer().use { server ->
                server.unsupportedSearch = true
                val sessions = SessionRepository(Store(), "test") {}
                sessions.login(server.baseUrl, "synthetic", "synthetic-password")
                val session = (sessions.state.value as SessionState.Connected).session
                val model = LibraryViewModel(session, sessions)
                val store = ViewModelStore().apply { put("model", model) }
                try {
                    model.artist("artist-1")
                    awaitCondition { model.state.value.artist != null }
                    assertFalse(model.state.value.failed)
                    sessions.signOut()
                    model.artist("artist-1")
                    delay(200)
                    assertNull(model.state.value.artist)
                } finally { store.clear() }
            }
        } finally { Dispatchers.resetMain() }
    }
    @Test(timeout = 15000) fun delayedAppearancesCannotPublishAfterSignOut() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            FakeSubsonicServer().use { server ->
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                server.artistAlbums = listOf(Album("album-1", "Own", artistId = "artist-1"))
                server.searchSongs = listOf(Song("guest", "Guest", artistId = "artist-1", albumId = "album-2"))
                server.beforeSearch = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                val sessions = SessionRepository(Store(), "test") {}
                sessions.login(server.baseUrl, "synthetic", "synthetic-password")
                val model = LibraryViewModel((sessions.state.value as SessionState.Connected).session, sessions)
                val store = ViewModelStore().apply { put("model", model) }
                try {
                    model.artist("artist-1")
                    assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                    assertNotNull(model.state.value.artist)
                    sessions.signOut()
                    release.countDown()
                    delay(200)
                    assertTrue(model.state.value.projection.appearances.isEmpty())
                } finally { release.countDown(); store.clear() }
            }
        } finally { Dispatchers.resetMain() }
    }
    @Test(timeout = 15000) fun offlineArtistFailsImmediatelyWithoutNetwork() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val sessions = SessionRepository(Store(), "test") {}
            sessions.login("http://127.0.0.1:1", "synthetic", "synthetic-password")
            val model = LibraryViewModel((sessions.state.value as SessionState.Connected).session, sessions) { false }
            val store = ViewModelStore().apply { put("model", model) }
            try {
                model.artist("artist-1")
                assertTrue(model.state.value.offline)
                assertTrue(model.state.value.failed)
                assertFalse(model.state.value.loading)
            } finally { store.clear() }
        } finally { Dispatchers.resetMain() }
    }
    @Test(timeout = 15000) fun discoversCreditBeyondFirstHundredSongs() = runBlocking {
        FakeSubsonicServer().use { server ->
            server.searchSongs = (0 until 100).map { Song("own-$it", "Own", artistId = "artist-1", albumId = "album-1") } +
                Song("guest", "Guest", artists = listOf(ArtistReference("artist-1", "Synthetic Artist")), albumId = "album-2")
            val client = SubsonicClient(SubsonicConfig(server.baseUrl, "synthetic", "synthetic-password"))
            val page = ArtistPage(Artist("artist-1", "Synthetic Artist"), listOf(Album("album-1", "Own")))
            assertEquals(listOf("album-2"), discoverAppearances(client, page).map { it.id })
        }
    }
    @Test(timeout = 15000) fun searchQueuesCanonicalAlbumAtSelectedIndexAndSingletonOnlyWithoutAlbum() = runBlocking {
        FakeSubsonicServer().use { server ->
            val client = SubsonicClient(SubsonicConfig(server.baseUrl, "synthetic", "synthetic-password"))
            javaClass.getResource("/artist/swift-search-playback.tsv")!!.readText().lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val (selected, index, ids) = line.split('\t')
                val queue = resolveSearchPlayback(client, Song(selected, "Selected", albumId = "album-1"))
                assertEquals(ids.split(','), queue.songs.map { it.id })
                assertEquals(index.toInt(), queue.startIndex)
            }
            for (id in listOf(null, "")) {
                val song = Song("single", "Single", albumId = id)
                assertEquals(listOf(song), resolveSearchPlayback(client, song).songs)
            }
            try { resolveSearchPlayback(client, Song("bad", "Bad", albumId = "missing")); fail("No singleton fallback on album failure") }
            catch (_: SubsonicError.NotFound) { }
        }
    }
}
