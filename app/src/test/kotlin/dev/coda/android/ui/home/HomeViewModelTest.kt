package dev.coda.android.ui.home

import dev.coda.core.subsonic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private fun bookmark(index: Int) = Bookmark(
        comment = """{"protocol":"album-resume-bookmark","protocolVersion":1,"resumeSongId":"next-$index"}""",
        changed = "%05d".format(index),
        entry = Song(id = "anchor-$index", title = "Synthetic", albumId = "album-$index", album = "Synthetic"),
    )

    private fun check(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block() } finally { Dispatchers.resetMain() }
    }

    @Test fun ordinaryShelvesPublishWhileBookmarksAreStalled() = check {
        val bookmarks = CompletableDeferred<List<Bookmark>>()
        val model = HomeViewModel({ true }, {}, { HomeState(albums = listOf(Album("one", "Synthetic"))) },
            { bookmarks.await() }, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertFalse(model.state.value.loading)
        assertEquals("one", model.state.value.albums.single().id)
        bookmarks.complete(listOf(bookmark(1)))
        runCurrent()
        assertEquals("album-1", model.state.value.continueListening.single().album.id)
        assertEquals("one", model.state.value.albums.single().id)
        advanceUntilIdle()
    }

    @Test fun foregroundCoalescesAndUpdatesRemoteProgressThenRemovesCompletedAlbum() = check {
        var remote = listOf(bookmark(1))
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null
        val model = HomeViewModel({ true }, {}, { HomeState() }, {
            calls++; gate?.await(); remote
        }, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals("album-1", model.state.value.continueListening.single().album.id)
        remote = listOf(bookmark(2)) // Mac advanced while backgrounded.
        gate = CompletableDeferred()
        repeat(5) { model.foreground() }
        advanceTimeBy(249); runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1); runCurrent()
        repeat(5) { model.foreground() }
        assertEquals(2, calls)
        gate!!.complete(Unit); advanceUntilIdle()
        assertEquals("album-2", model.state.value.continueListening.single().album.id)
        remote = emptyList(); model.foreground(); advanceUntilIdle()
        assertTrue(model.state.value.continueListening.isEmpty())
        assertEquals(3, calls)
    }

    @Test fun sessionChangeRejectsDelayedOptionalAndOrdinaryResults() = check {
        var current = true
        val finish = CompletableDeferred<Unit>()
        val model = HomeViewModel({ current }, { fail("Stale session unauthorized") },
            { finish.await(); HomeState(albums = listOf(Album("old", "Synthetic"))) },
            { finish.await(); listOf(bookmark(1)) }, StandardTestDispatcher(testScheduler))
        runCurrent(); current = false; finish.complete(Unit); advanceUntilIdle()
        assertTrue(model.state.value.albums.isEmpty())
        assertTrue(model.state.value.continueListening.isEmpty())
        assertNull(model.state.value.handoff)
        model.foreground(); advanceUntilIdle()
    }

    @Test fun manualRefreshCancelsOldResults() = check {
        var calls = 0
        val old = CompletableDeferred<List<Bookmark>>()
        val model = HomeViewModel({ true }, {}, { HomeState() }, {
            if (++calls == 1) old.await() else listOf(bookmark(2))
        }, StandardTestDispatcher(testScheduler))
        runCurrent()
        model.refresh(); advanceUntilIdle()
        old.complete(listOf(bookmark(1))); advanceUntilIdle()
        assertEquals("album-2", model.state.value.continueListening.single().album.id)
    }

    @Test fun sessionChangeDuringConversionRejectsPublication() = check {
        var current = true
        val conversion = StandardTestDispatcher(testScheduler)
        val entries = object : AbstractList<Bookmark>() {
            override val size = 1
            override fun get(index: Int): Bookmark {
                current = false // Session changes after the network reply, while conversion runs.
                return bookmark(1)
            }
        }
        val model = HomeViewModel({ current }, {}, { HomeState() }, { entries }, conversion)
        advanceUntilIdle()
        assertFalse(current)
        assertTrue(model.state.value.continueListening.isEmpty())
    }

    // Continue Listening tiles carry progress and remaining time.
    @Test fun continueListeningTilesGetProgressFromTheAlbumTrackList() = check {
        val songs = (1..4).map { Song("s$it", "T$it", track = it, duration = 600) }
        val requested = mutableListOf<String>()
        val model = HomeViewModel({ true }, {}, { HomeState() }, { listOf(bookmark(1), bookmark(2)) }, StandardTestDispatcher(testScheduler),
            loadAlbum = { id -> requested += id; if (id == "album-1") AlbumPage(Album(id, "Synthetic"), songs.map { it.copy(id = if (it.id == "s3") "next-1" else it.id) }) else error("offline") })
        advanceUntilIdle()
        assertEquals(2, model.state.value.continueListening.size)
        assertEquals(setOf("album-1", "album-2"), requested.toSet())
        val progress = model.state.value.resumeProgress.getValue("album-1")
        assertEquals("3 of 4 · 20 min left", progress.subtitle)
        assertEquals(.5f, progress.fraction, 1e-4f)
        assertNull("A tile whose album could not be loaded keeps its plain presentation", model.state.value.resumeProgress["album-2"])
    }

    /** Progress belongs to the bookmark it was resolved for; a moved bookmark that cannot be resolved shows none. */
    @Test fun aChangedMarkerAfterASuccessfulResolutionDropsTheOldProgress() = check {
        val songs = (1..4).map { Song("s$it", "T$it", track = it, duration = 600) }
        fun marked(resumeSongId: String, changed: String) = bookmark(1).copy(changed = changed,
            comment = """{"protocol":"album-resume-bookmark","protocolVersion":1,"resumeSongId":"$resumeSongId"}""")
        var remote = listOf(marked("s3", "00001"))
        var albumFails = false
        val model = HomeViewModel({ true }, {}, { HomeState() }, { remote }, StandardTestDispatcher(testScheduler),
            loadAlbum = { id -> if (albumFails) error("offline") else AlbumPage(Album(id, "Synthetic"), songs) })
        advanceUntilIdle()
        assertEquals("3 of 4 · 20 min left", model.state.value.resumeProgress.getValue("album-1").subtitle)
        // Unchanged marker: the progress stays.
        model.refresh(); advanceUntilIdle()
        assertEquals("3 of 4 · 20 min left", model.state.value.resumeProgress.getValue("album-1").subtitle)
        // The bookmark moved to a song the album's track list does not have.
        remote = listOf(marked("gone", "00002")); model.refresh(); advanceUntilIdle()
        assertNull("no progress from the older bookmark", model.state.value.resumeProgress["album-1"])
        // It moves again, and the album cannot be loaded.
        remote = listOf(marked("s3", "00003")); model.refresh(); advanceUntilIdle()
        assertNotNull(model.state.value.resumeProgress["album-1"])
        remote = listOf(marked("s2", "00004")); albumFails = true; model.refresh(); advanceUntilIdle()
        assertNull("a failed resolution keeps nothing of the older marker", model.state.value.resumeProgress["album-1"])
    }

    /** An unchanged marker keeps its progress when a later refresh cannot load the album. */
    @Test fun anUnchangedMarkerKeepsItsProgressThroughAFailedRefresh() = check {
        val songs = (1..4).map { Song("s$it", "T$it", track = it, duration = 600) }
        val remote = listOf(bookmark(1).copy(comment = """{"protocol":"album-resume-bookmark","protocolVersion":1,"resumeSongId":"s3"}"""))
        var albumFails = false
        val model = HomeViewModel({ true }, {}, { HomeState() }, { remote }, StandardTestDispatcher(testScheduler),
            loadAlbum = { id -> if (albumFails) error("offline") else AlbumPage(Album(id, "Synthetic"), songs) })
        advanceUntilIdle()
        assertEquals("3 of 4 · 20 min left", model.state.value.resumeProgress.getValue("album-1").subtitle)
        albumFails = true
        model.refresh(); advanceUntilIdle()
        assertEquals("3 of 4 · 20 min left", model.state.value.resumeProgress["album-1"]?.subtitle)
    }

    @Test fun optionalFailuresLeaveOrdinaryShelvesAvailable() = check {
        val model = HomeViewModel({ true }, {}, { HomeState(albums = listOf(Album("one", "Synthetic"))) },
            { error("Unsupported optional endpoint") })
        advanceUntilIdle()
        assertFalse(model.state.value.loading)
        assertFalse(model.state.value.failed)
        assertEquals("one", model.state.value.albums.single().id)
    }

    @Test fun largeUnpagedSnapshotConvertsOffMainBeforeSelectingNewestTwenty() = check {
        val mainThread = Thread.currentThread()
        var conversionThread: Thread? = null
        // Observe access in albumResumeItems itself, rather than merely asserting an injected dispatcher.
        val entries = object : AbstractList<Bookmark>() {
            override val size = 10_000
            override fun get(index: Int): Bookmark {
                conversionThread = Thread.currentThread()
                assertNotSame(mainThread, conversionThread)
                return bookmark(index)
            }
        }
        val finished = CompletableDeferred<Unit>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                Dispatchers.Default.dispatch(context, Runnable { try { block.run() } finally { finished.complete(Unit) } })
            }
        }
        val model = HomeViewModel({ true }, {}, { HomeState() }, { entries }, dispatcher)
        runCurrent(); finished.await(); advanceUntilIdle()
        assertNotNull(conversionThread)
        assertEquals((9999 downTo 9980).map { "album-$it" }, model.state.value.continueListening.map { it.album.id })
    }

    // The coordinator-backed Continue Listening path.
    private class FakeResumeApi : dev.coda.core.sync.AlbumResumeApi {
        var bookmarks: List<Bookmark> = emptyList()
        var failure: Throwable? = null
        var fetches = 0
        override suspend fun getBookmarks(): List<Bookmark> { fetches++; failure?.let { throw it }; return bookmarks }
        override suspend fun getSong(id: String): Song = error("unused")
        override suspend fun getAlbum(id: String): AlbumPage = error("unused")
        override suspend fun createBookmark(songId: String, positionMilliseconds: Long, comment: String) = error("unused")
        override suspend fun deleteBookmark(songId: String) {}
    }

    private fun TestScope.resumeModel(api: FakeResumeApi, shelves: suspend () -> HomeState = { HomeState() },
        unauthorized: suspend () -> Unit = {}, loadAlbum: suspend (String) -> AlbumPage? = { null }):
        Pair<HomeViewModel, dev.coda.core.sync.AlbumResumeCoordinator> {
        val coordinator = dev.coda.core.sync.AlbumResumeCoordinator(CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()), computation = StandardTestDispatcher(testScheduler))
        coordinator.setSession(dev.coda.core.sync.AlbumResumeSession(api, null, "t1"))
        val model = HomeViewModel({ true }, unauthorized, shelves, { error("legacy path unused") },
            StandardTestDispatcher(testScheduler), loadAlbum = loadAlbum, resume = coordinator, resumeToken = "t1")
        return model to coordinator
    }

    @Test fun resumeBranchShowsNewest20AndFailedRefreshKeepsTheShelf() = check {
        val api = FakeResumeApi().apply { bookmarks = (0 until 22).map { bookmark(it) } }
        val (model, _) = resumeModel(api)
        advanceUntilIdle()
        assertEquals(20, model.state.value.continueListening.size)
        assertEquals("album-21", model.state.value.continueListening.first().album.id)
        api.failure = SubsonicError.Http(503)
        model.foreground(); advanceUntilIdle()
        assertEquals(20, model.state.value.continueListening.size)
    }

    @Test fun resumeBranchLocalWriteEditsTheShelfAndForegroundCoalesces() = check {
        val api = FakeResumeApi().apply { bookmarks = listOf(bookmark(1)) }
        val (model, _) = resumeModel(api)
        advanceUntilIdle()
        repeat(5) { model.foreground() }
        advanceUntilIdle()
        assertEquals(2, api.fetches)
        api.bookmarks = listOf(bookmark(2)); model.foreground(); advanceUntilIdle()
        assertEquals("album-2", model.state.value.continueListening.single().album.id)
    }

    @Test fun resumeBranchReloadsAStaleShelvesFailureAfterASuccessfulRefresh() = check {
        var shelfCalls = 0
        val api = FakeResumeApi().apply { bookmarks = listOf(bookmark(1)) }
        val (model, _) = resumeModel(api, shelves = { if (++shelfCalls == 1) throw SubsonicError.Http(503) else HomeState(albums = listOf(Album("one", "Synthetic"))) })
        advanceUntilIdle()
        assertEquals(2, shelfCalls)
        assertFalse(model.state.value.failed)
        assertEquals("one", model.state.value.albums.single().id)
    }

    @Test fun resumeBranchRoutesUnauthorizedToSignOut() = check {
        var signedOut = 0
        val api = FakeResumeApi().apply { failure = SubsonicError.Unauthorized() }
        resumeModel(api, unauthorized = { signedOut++ })
        advanceUntilIdle()
        assertEquals(1, signedOut)
    }

    @Test fun resumeBranchProgressSurvivesAManualRefreshWithTheSameItems() = check {
        val songs = (1..4).map { Song("s$it", "T$it", track = it, duration = 600) }
        val gate = CompletableDeferred<Unit>()
        var loads = 0
        val api = FakeResumeApi().apply { bookmarks = listOf(bookmark(1)) }
        val (model, _) = resumeModel(api, loadAlbum = { id ->
            loads++; gate.await()
            AlbumPage(Album(id, "Synthetic"), songs.map { if (it.id == "s3") it.copy(id = "next-1") else it })
        })
        runCurrent()
        assertTrue(model.state.value.resumeProgress.isEmpty())
        model.refresh(); runCurrent()          // same items again: no new emission
        gate.complete(Unit); advanceUntilIdle()
        assertNotNull(model.state.value.resumeProgress["album-1"])
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeSnapshotTest {
    private fun check(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block() } finally { Dispatchers.resetMain() }
    }
    private val saved = dev.coda.core.library.HomeShelves(albums = listOf(Album("saved", "Saved Album")))

    // B05: the session needs a login (not current), yet Home keeps showing its saved shelves.
    @Test fun expiredSessionStillShowsSavedShelvesInsteadOfLoadingForever() = check {
        val model = HomeViewModel({ false }, {}, { error("no network") }, { emptyList() }, StandardTestDispatcher(testScheduler),
            loadSnapshot = { saved })
        advanceUntilIdle()
        assertEquals("saved", model.state.value.albums.single().id)
        assertFalse(model.state.value.loading)
        assertFalse(model.state.value.failed)
    }

    @Test fun savedShelvesStayWhenRefreshFailsAndLiveShelvesAreSaved() = check {
        var fail = true
        var written: dev.coda.core.library.HomeShelves? = null
        val model = HomeViewModel({ true }, {}, { if (fail) throw SubsonicError.Network(java.io.IOException("x")) else HomeState(albums = listOf(Album("live", "Live"))) },
            { emptyList() }, StandardTestDispatcher(testScheduler), loadSnapshot = { saved }, saveSnapshot = { written = it })
        advanceUntilIdle()
        assertEquals("saved", model.state.value.albums.single().id)
        assertTrue(model.state.value.failed)
        fail = false; model.refresh(); advanceUntilIdle()
        assertEquals("live", model.state.value.albums.single().id)
        assertEquals("live", written!!.albums.single().id)
    }

    @Test fun expiredSessionWithoutSnapshotShowsError() = check {
        val model = HomeViewModel({ false }, {}, { HomeState() }, { emptyList() }, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertTrue(model.state.value.failed)
        assertFalse(model.state.value.loading)
    }

}
