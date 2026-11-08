package dev.coda.core.sync

import dev.coda.core.playback.AlbumSequenceContext
import dev.coda.core.playback.CompletedPlayback
import dev.coda.core.playback.EngineEvent
import dev.coda.core.playback.FakeAudioEngine
import dev.coda.core.playback.PlaybackController
import dev.coda.core.playback.PlaybackEvent
import dev.coda.core.playback.PlaybackEventDispatcher
import dev.coda.core.playback.QueueEntry
import dev.coda.core.subsonic.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Ports of the Swift albumResume* tests (NavidromeTests, AlbumResumePlaybackTests) for the writing side. */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumResumeWriteTest {
    private val first = Song("track-1", "One", album = "Album", artist = "Artist", albumId = "album-1", track = 1)
    private val second = Song("track-2", "Two", album = "Album", artist = "Artist", albumId = "album-1", track = 2)
    private val page = AlbumPage(Album("album-1", "Album", artist = "Artist"), listOf(first, second))
    private fun ctx(prev: Boolean, next: Boolean, explicit: Boolean = false, album: String? = "album-1") =
        AlbumSequenceContext(album, prev, next, explicit)

    @Test fun commentIsPortableRecognizableAndSorted() {
        val comment = albumResumeComment("resume-track", AlbumResumeWriter("Coda on su", "macOS", "2.1.1"))!!
        assertEquals("""{"protocol":"album-resume-bookmark","protocolVersion":1,"resumeSongId":"resume-track",""" +
            """"writer":{"appVersion":"2.1.1","client":"Coda on su","platform":"macOS"}}""", comment)
        assertTrue(comment.toByteArray().size <= 255)
        assertEquals("""{"protocol":"album-resume-bookmark","protocolVersion":1,"resumeSongId":"x"}""",
            albumResumeComment(" x ", null))
        assertNull(albumResumeComment("  ", null))
        // Reader side recognises it.
        val items = albumResumeItems(listOf(Bookmark(comment = comment, changed = "2026-01-01",
            entry = first)))
        assertEquals("resume-track", items.single().resumeSongId)
    }

    @Test fun oversizedWriterIsDroppedAndSlashesEscapedLikeSwift() {
        val long = albumResumeComment("id", AlbumResumeWriter("c".repeat(300), "Android", "1"))!!
        assertFalse(long.contains("writer"))
        assertEquals("""{"protocol":"album-resume-bookmark","protocolVersion":1,"resumeSongId":"a\/b"}""",
            albumResumeComment("a/b", null))
        assertNull(albumResumeComment("x".repeat(300), null))
    }

    @Test fun completionUsesStableFirstTrackAnchor() {
        assertEquals(AlbumResumeCompletionAction.Upsert("track-1", "track-2"),
            albumResumeCompletionAction(CompletedPlayback("track-1", ctx(false, true)), page))
        assertEquals(AlbumResumeCompletionAction.Delete("track-1"),
            albumResumeCompletionAction(CompletedPlayback("track-2", ctx(true, false)), page))
        // Explicit continuation can finish a singleton.
        assertEquals(AlbumResumeCompletionAction.Delete("track-1"),
            albumResumeCompletionAction(CompletedPlayback("track-2", ctx(false, false, explicit = true)), page))
    }

    @Test fun isolatedOrForeignCompletionsDoNothing() {
        for (id in listOf("track-1", "track-2"))
            assertEquals(AlbumResumeCompletionAction.None,
                albumResumeCompletionAction(CompletedPlayback(id, ctx(false, false)), page))
        assertEquals(AlbumResumeCompletionAction.None, albumResumeCompletionAction(CompletedPlayback("track-2", null), page))
        assertEquals(AlbumResumeCompletionAction.None,
            albumResumeCompletionAction(CompletedPlayback("track-2", ctx(true, false, album = "B")), page))
        assertEquals(AlbumResumeCompletionAction.None,
            albumResumeCompletionAction(CompletedPlayback("missing", ctx(true, true)), page))
        // Continuation hint for an unknown album gives no membership.
        for (album in listOf(null, "")) assertNull(albumResumeRecordingAlbumId(CompletedPlayback("track-2", ctx(false, false, true, album))))
    }

    private class FakeApi(var song: Song, var page: AlbumPage) : AlbumResumeApi {
        val calls = mutableListOf<String>()
        var failCreate = false
        var bookmarks: List<Bookmark> = emptyList()
        var failBookmarks = false
        override suspend fun getBookmarks(): List<Bookmark> { calls += "bookmarks"; onGetBookmarks?.invoke(); if (unauthorized) throw SubsonicError.Unauthorized(); if (failBookmarks) throw SubsonicError.Http(503); return bookmarks }
        val songs = mutableMapOf<String, Song>()
        var onGetSong: (suspend () -> Unit)? = null
        var onGetBookmarks: (suspend () -> Unit)? = null
        var onDelete: (suspend (String) -> Unit)? = null
        var unauthorized = false
        override suspend fun getSong(id: String): Song { calls += "song:$id"; onGetSong?.invoke(); return songs[id] ?: song }
        override suspend fun getAlbum(id: String): AlbumPage { calls += "album:$id"; return page }
        override suspend fun createBookmark(songId: String, positionMilliseconds: Long, comment: String) {
            calls += "create:$songId:$positionMilliseconds:$comment"
            if (failCreate) throw SubsonicError.Http(503)
        }
        override suspend fun deleteBookmark(songId: String) { calls += "delete:$songId"; onDelete?.invoke(songId) }
    }

    /** Grouping runs inline under the test scheduler; the production default (Dispatchers.Default) has its own test. */
    private fun TestScope.newCoordinator() = AlbumResumeCoordinator(backgroundScope, computation = UnconfinedTestDispatcher(testScheduler))

    private fun completed(id: String, c: AlbumSequenceContext?) = PlaybackEvent.Completed(CompletedPlayback(id, c))
    private val writer = AlbumResumeWriter("Coda on Android", "Android", "1")

    @Test fun coordinatorWritesInOrderAndDeletesAfterLastTrack() = runTest {
        val api = FakeApi(first, page)
        val coordinator = newCoordinator()
        coordinator.setSession(AlbumResumeSession(api, writer))
        api.songs["track-1"] = first; api.songs["track-2"] = second
        coordinator.onEvent(completed("track-1", ctx(false, true)))
        coordinator.onEvent(completed("track-2", ctx(true, false)))
        runCurrent()
        val expectedComment = albumResumeComment("track-2", writer)
        assertEquals(listOf("song:track-1", "album:album-1", "create:track-1:0:$expectedComment",
            "song:track-2", "album:album-1", "delete:track-1"), api.calls)
    }

    @Test fun ineligibleOrNonAlbumSongsNeverWrite() = runTest {
        val api = FakeApi(first, page)
        val coordinator = newCoordinator()
        coordinator.setSession(AlbumResumeSession(api, writer))
        // Podcast: looked up, then rejected before any album request or write.
        api.songs["pod"] = first.copy(id = "pod", type = "podcast")
        coordinator.onEvent(completed("pod", ctx(false, true))); runCurrent()
        assertEquals(listOf("song:pod"), api.calls)
        // Not music media, no album id: same.
        api.songs["video"] = first.copy(id = "video", mediaType = "video")
        api.songs["loose"] = first.copy(id = "loose", albumId = null)
        coordinator.onEvent(completed("video", ctx(false, true)))
        coordinator.onEvent(completed("loose", ctx(false, true))); runCurrent()
        assertEquals(listOf("song:pod", "song:video", "song:loose"), api.calls)
        // Isolated / unknown context: no call at all.
        coordinator.onEvent(completed("track-1", ctx(false, false)))
        coordinator.onEvent(completed("track-1", null))
        coordinator.onEvent(PlaybackEvent.OccurrenceEnded); runCurrent()
        assertEquals(3, api.calls.size)
        // The song's album differs from the occurrence's album.
        api.songs["other"] = first.copy(id = "other", albumId = "other-album")
        coordinator.onEvent(completed("other", ctx(false, true))); runCurrent()
        assertEquals("song:other", api.calls.last()); assertEquals(4, api.calls.size)
        assertTrue(coordinator.items.value.isEmpty())
    }

    @Test fun failuresAreSkippedAndLaterWritesStillRun() = runTest {
        val api = FakeApi(first, page).apply { failCreate = true }
        val coordinator = newCoordinator()
        coordinator.setSession(AlbumResumeSession(api, writer))
        coordinator.onEvent(completed("track-1", ctx(false, true)))
        runCurrent()
        api.failCreate = false
        coordinator.onEvent(completed("track-1", ctx(false, true)))
        runCurrent()
        assertEquals(2, api.calls.count { it.startsWith("create:") })
    }

    @Test fun noSessionOrChangedSessionWritesNothing() = runTest {
        val old = FakeApi(first, page)
        val coordinator = newCoordinator()
        coordinator.onEvent(completed("track-1", ctx(false, true)))
        coordinator.setSession(AlbumResumeSession(old, writer))
        coordinator.onEvent(completed("track-1", ctx(false, true)))
        coordinator.setSession(null)           // before the queued job runs
        runCurrent()
        assertTrue(old.calls.isEmpty())
    }

    private fun marker(n: Int, changed: String?, album: String = "album-$n", comment: String? = albumResumeComment("next-$n", null)) =
        Bookmark(comment = comment, changed = changed, entry = Song("anchor-$n", "T", album = "Album $n", albumId = album))

    @Test fun retentionDeletesOnlyCompatibleMarkersBeyondNewest20() {
        val owned = (0 until 23).map { marker(it, "2026-01-%02d".format(it + 1)) }
        val foreign = listOf(marker(100, "2025-01-01", comment = "podcast position"),
            marker(101, "2025-01-02", comment = """{"protocol":"album-resume-bookmark","protocolVersion":2,"resumeSongId":"x"}"""),
            marker(102, "2025-01-03", comment = null))
        val deletes = albumResumeRetentionDeletes(foreign + owned)
        assertEquals(listOf("anchor-2", "anchor-1", "anchor-0"), deletes)   // the three oldest, oldest last
        assertTrue(albumResumeRetentionDeletes(owned.take(20)).isEmpty())
        assertEquals(20, albumResumeItems(owned + foreign).size)
    }

    @Test fun retentionUsesRawStringOrderingAndGroupsDuplicates() {
        // created is the fallback for a missing changed; "" sorts last; no date parsing.
        val a = marker(1, null).copy(created = "2026-03")
        val b = marker(2, "")
        val c = marker(3, "2026-02")
        val dupOld = marker(4, "2026-01", album = "album-3")                      // same album as c, older
        assertEquals(listOf("album-1", "album-3", "album-2"), albumResumeItems(listOf(b, c, dupOld, a)).map { it.album.id })
        // keep = 2: album-2 (anchor-2) is beyond; dupOld stays with its newer sibling's album.
        assertEquals(listOf("anchor-2"), albumResumeRetentionDeletes(listOf(b, c, dupOld, a), keep = 2))
    }

    @Test fun refreshPublishesNewest20ThenPrunesWithFreshOrderingOnly() = runTest {
        val api = FakeApi(first, page)
        api.bookmarks = (0 until 22).map { marker(it, "2026-01-%02d".format(it + 1)) } + marker(50, "2020", comment = "mine")
        val coordinator = newCoordinator()
        coordinator.setSession(AlbumResumeSession(api, writer, "t1"))
        assertFalse(coordinator.refresh("other-token"))
        assertTrue(coordinator.refresh("t1"))
        assertEquals(20, coordinator.items.value.size)
        assertEquals("album-21", coordinator.items.value.first().album.id)
        assertEquals(listOf("bookmarks", "delete:anchor-1", "delete:anchor-0"), api.calls)
    }

    @Test fun refreshWaitsForEarlierWritesAndFailureKeepsShelf() = runTest {
        val api = FakeApi(first, page)
        val coordinator = AlbumResumeCoordinator(backgroundScope, { java.time.Instant.parse("2026-10-25T10:00:00.123Z") }, UnconfinedTestDispatcher(testScheduler))
        coordinator.setSession(AlbumResumeSession(api, writer, "t1"))
        coordinator.onEvent(completed("track-1", ctx(false, true)))
        val refreshed = async { coordinator.refresh("t1") }
        runCurrent()
        assertTrue(refreshed.await())
        assertEquals(listOf("song:track-1", "album:album-1"), api.calls.take(2))
        assertEquals("bookmarks", api.calls.last())
        // Local insert after a successful write, replaced by the authoritative answer.
        api.bookmarks = emptyList()
        assertTrue(coordinator.refresh("t1")); assertTrue(coordinator.items.value.isEmpty())
        coordinator.onEvent(completed("track-1", ctx(false, true))); runCurrent()
        val local = coordinator.items.value.single()
        assertEquals(Triple("album-1", "track-1", "track-2"), Triple(local.album.id, local.anchorSongId, local.resumeSongId))
        assertEquals("2026-10-25T10:00:00Z", local.changed)
        api.failBookmarks = true
        assertFalse(coordinator.refresh("t1"))
        assertEquals(1, coordinator.items.value.size)
        api.songs["track-2"] = second; coordinator.onEvent(completed("track-2", ctx(true, false))); runCurrent()
        assertTrue(coordinator.items.value.isEmpty())
    }

    @Test fun sessionChangeDuringRetentionDeletesStopsDeleting() = runTest {
        val api = FakeApi(first, page)
        api.bookmarks = (0 until 24).map { marker(it, "2026-01-%02d".format(it + 1)) }
        val coordinator = newCoordinator()
        coordinator.setSession(AlbumResumeSession(api, writer, "t1"))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        api.onDelete = { gate.await() }
        val refreshed = async { coordinator.refresh("t1") }
        runCurrent()
        assertEquals(listOf("bookmarks", "delete:anchor-3"), api.calls)
        coordinator.setSession(AlbumResumeSession(FakeApi(first, page), writer, "t2"))
        gate.complete(Unit); runCurrent()
        assertFalse(refreshed.await())
        assertEquals(listOf("bookmarks", "delete:anchor-3"), api.calls)   // no further deletes for the old account
        assertTrue(coordinator.items.value.isEmpty())
    }

    @Test fun sessionChangeBetweenFetchAndFirstDeleteDeletesNothing() = runTest {
        val api = FakeApi(first, page)
        api.bookmarks = (0 until 22).map { marker(it, "2026-01-%02d".format(it + 1)) }
        val coordinator = newCoordinator()
        coordinator.setSession(AlbumResumeSession(api, writer, "t1"))
        api.onGetBookmarks = { coordinator.setSession(AlbumResumeSession(FakeApi(first, page), writer, "t2")) }
        assertFalse(coordinator.refresh("t1"))
        assertEquals(listOf("bookmarks"), api.calls)
        assertTrue(coordinator.items.value.isEmpty())
    }

    @Test fun sessionChangeCancelsInFlightWorkSoTheNewSessionIsNotBlocked() = runTest {
        val old = FakeApi(first, page)
        old.onGetSong = { kotlinx.coroutines.awaitCancellation() }
        val coordinator = newCoordinator()
        coordinator.setSession(AlbumResumeSession(old, writer, "t1"))
        coordinator.onEvent(completed("track-1", ctx(false, true))); runCurrent()
        val fresh = FakeApi(first, page).apply { bookmarks = listOf(marker(1, "2026-01-01")) }
        coordinator.setSession(AlbumResumeSession(fresh, writer, "t2"))
        assertTrue(coordinator.refresh("t2"))
        assertEquals(1, coordinator.items.value.size)
        assertEquals(listOf("song:track-1"), old.calls)
    }

    @Test fun overlappingRefreshesShareOneFetch() = runTest {
        val api = FakeApi(first, page).apply { bookmarks = listOf(marker(1, "2026-01-01")) }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        api.onGetBookmarks = { gate.await() }
        val coordinator = newCoordinator()
        coordinator.setSession(AlbumResumeSession(api, writer, "t1"))
        val all = (1..4).map { async { coordinator.refresh("t1") } }
        runCurrent(); gate.complete(Unit); runCurrent()
        assertTrue(all.all { it.await() })
        assertEquals(1, api.calls.count { it == "bookmarks" })
        api.onGetBookmarks = null
        assertTrue(coordinator.refresh("t1"))                    // a later refresh fetches again
        assertEquals(2, api.calls.count { it == "bookmarks" })
    }

    @Test fun unauthorizedRefreshIsThrownAndLeavesTheShelf() = runTest {
        val api = FakeApi(first, page).apply { bookmarks = listOf(marker(1, "2026-01-01")) }
        val coordinator = newCoordinator()
        coordinator.setSession(AlbumResumeSession(api, writer, "t1"))
        assertTrue(coordinator.refresh("t1"))
        api.unauthorized = true
        assertTrue(runCatching { coordinator.refresh("t1") }.exceptionOrNull() is SubsonicError.Unauthorized)
        assertEquals(1, coordinator.items.value.size)
    }

    @Test fun reconnectOfTheSameSessionKeepsTheShelfButAnotherSessionClearsIt() = runTest {
        val api = FakeApi(first, page).apply { bookmarks = listOf(marker(1, "2026-01-01")) }
        val coordinator = newCoordinator()
        coordinator.setSession(AlbumResumeSession(api, writer, "t1"))
        coordinator.refresh("t1")
        coordinator.setSession(null)
        coordinator.setSession(AlbumResumeSession(api, writer, "t1"))
        assertEquals(1, coordinator.items.value.size)
        coordinator.setSession(AlbumResumeSession(api, writer, "t2"))
        assertTrue(coordinator.items.value.isEmpty())
    }

    // --- Second-opinion fixes (2026-10-25) ---

    @Test fun malformedMarkerFieldTypesAreNotOwnedAndNeverPruned() {
        val valid = (0 until 20).map { marker(it, "2026-02-%02d".format(it + 1)) }
        val malformed = listOf(
            """{"protocol":"album-resume-bookmark","protocolVersion":"1","resumeSongId":"x"}""",
            """{"protocol":"album-resume-bookmark","protocolVersion":1.0,"resumeSongId":"x"}""",
            """{"protocol":"album-resume-bookmark","protocolVersion":true,"resumeSongId":"x"}""",
            """{"protocol":"album-resume-bookmark","protocolVersion":null,"resumeSongId":"x"}""",
            """{"protocol":"album-resume-bookmark","protocolVersion":1,"resumeSongId":7}""",
            """{"protocol":"album-resume-bookmark","protocolVersion":1,"resumeSongId":null}""",
            """{"protocol":5,"protocolVersion":1,"resumeSongId":"x"}""",
            """{"protocolVersion":1,"resumeSongId":"x"}""",
            """["album-resume-bookmark"]""", """"album-resume-bookmark"""", "null", "{")
            .mapIndexed { i, comment -> marker(100 + i, "2025-01-%02d".format(i + 1), comment = comment) }
        assertTrue(albumResumeItems(malformed).isEmpty())
        // Older than the 20 valid ones, so a (wrongly) recognised one would be selected for deletion.
        assertEquals(emptyList<String>(), albumResumeRetentionDeletes(valid + malformed))
        assertEquals(20, albumResumeBuckets(valid + malformed).size)
        // Control: the same marker with the right types is owned (and, being the oldest, pruned).
        val ok = marker(99, "2025-01-01", comment = """{"protocol":"album-resume-bookmark","protocolVersion":1,"resumeSongId":"x","writer":5}""")
        assertEquals(listOf("anchor-99"), albumResumeRetentionDeletes(valid + ok))
    }

    /** Runs blocks inline, counts dispatches, and can park them to model a slow computation. */
    private class GatedDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        var dispatched = 0
        var hold = false
        val parked = mutableListOf<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            dispatched++
            if (hold) parked += block else block.run()
        }
    }

    @Test fun bucketsAreComputedOnceOnTheComputationDispatcher() = runTest {
        val gate = GatedDispatcher()
        val bookmarks = (0 until 25).map { marker(it, "2026-01-%02d".format(it + 1)) }
        val api = FakeApi(first, page).apply { this.bookmarks = bookmarks }
        val coordinator = AlbumResumeCoordinator(backgroundScope, computation = gate)
        coordinator.setSession(AlbumResumeSession(api, writer, "t1"))
        assertTrue(coordinator.refresh("t1"))
        assertEquals(1, gate.dispatched)
        assertEquals(20, coordinator.items.value.size)
        assertEquals((4 downTo 0).map { "delete:anchor-$it" }, api.calls.filter { it.startsWith("delete:") })
        // The production default really is a different thread pool from the caller's.
        val real = AlbumResumeCoordinator(backgroundScope)
        real.setSession(AlbumResumeSession(api, writer, "t1"))
        assertTrue(real.refresh("t1")); assertEquals(20, real.items.value.size)
    }

    @Test fun sessionChangeWhileBucketsAreComputingPublishesAndDeletesNothing() = runTest {
        val gate = GatedDispatcher()
        val api = FakeApi(first, page).apply { bookmarks = (0 until 25).map { marker(it, "2026-01-%02d".format(it + 1)) } }
        val coordinator = AlbumResumeCoordinator(backgroundScope, computation = gate)
        coordinator.setSession(AlbumResumeSession(api, writer, "t1"))
        gate.hold = true
        val refresh = async { coordinator.refresh("t1") }
        runCurrent()
        assertEquals(1, gate.parked.size)           // fetched, grouping not finished yet
        coordinator.setSession(AlbumResumeSession(FakeApi(first, page), writer, "t2"))
        gate.hold = false
        gate.parked.forEach { it.run() }; runCurrent()
        assertFalse(refresh.await())
        assertTrue(coordinator.items.value.isEmpty())
        assertTrue(api.calls.none { it.startsWith("delete:") })
    }

    @Test fun connectRefreshesWithoutAnyUiAndSharesTheOneFetchWithLaterCallers() = runTest {
        val api = FakeApi(first, page).apply { bookmarks = (0 until 22).map { marker(it, "2026-01-%02d".format(it + 1)) } }
        val coordinator = newCoordinator()
        coordinator.connect(AlbumResumeSession(api, writer, "t1"))
        // Home opens while the host's refresh is still pending: it joins it.
        val home = async { coordinator.refresh("t1") }
        runCurrent()
        assertTrue(home.await())
        assertEquals(1, api.calls.count { it == "bookmarks" })
        assertEquals(20, coordinator.items.value.size)
        assertEquals(listOf("delete:anchor-1", "delete:anchor-0"), api.calls.filter { it.startsWith("delete:") })
        // Signed out: nothing to refresh.
        coordinator.connect(null); runCurrent()
        assertEquals(1, api.calls.count { it == "bookmarks" })
    }

    @Test fun connectReportsUnauthorizedToTheHost() = runTest {
        val api = FakeApi(first, page).apply { unauthorized = true }
        val coordinator = newCoordinator()
        var rejected = 0
        coordinator.connect(AlbumResumeSession(api, writer, "t1"), onUnauthorized = { rejected++ })
        runCurrent()
        assertEquals(1, rejected)
        val flaky = FakeApi(first, page).apply { failBookmarks = true }
        coordinator.connect(AlbumResumeSession(flaky, writer, "t2"), onUnauthorized = { rejected++ })
        runCurrent()
        assertEquals(1, rejected)                  // offline is dropped quietly
    }

    @Test fun completionCarriesItsOriginatingSessionThroughAMultiSinkFanOut() = runTest {
        var current: Any? = "t1"
        val engine = FakeAudioEngine()
        val controller = PlaybackController(engine, sessionGeneration = { current })
        val old = FakeApi(first, page).apply { songs["track-1"] = first }
        val next = FakeApi(first, page).apply { songs["track-1"] = first }
        val coordinator = newCoordinator()
        coordinator.setSession(AlbumResumeSession(old, writer, "t1"))
        val delivered = mutableListOf<CompletedPlayback>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        // The fan-out is parked in an earlier sink while the account changes.
        PlaybackEventDispatcher(controller, backgroundScope, listOf(
            { e -> if (e is PlaybackEvent.Completed) { delivered += e.playback; gate.await() } },
            { e -> coordinator.onEvent(e) }))
        controller.replaceQueue(listOf(QueueEntry("track-1", albumId = "album-1"), QueueEntry("track-2", albumId = "album-1")))
        engine.emit(EngineEvent.Advanced(engine.next!!.occurrence))
        runCurrent()
        assertEquals("t1", delivered.single().sessionGeneration)
        current = "t2"
        coordinator.setSession(AlbumResumeSession(next, writer, "t2"))
        gate.complete(Unit); runCurrent()
        assertTrue(old.calls.isEmpty()); assertTrue(next.calls.isEmpty())   // neither session writes for the other's track
    }

    @Test fun completionsFromTheCurrentOrAnUnstampedControllerStillWrite() = runTest {
        val api = FakeApi(first, page).apply { songs["track-1"] = first }
        val coordinator = newCoordinator()
        coordinator.setSession(AlbumResumeSession(api, writer, "t1"))
        coordinator.onEvent(PlaybackEvent.Completed(CompletedPlayback("track-1", ctx(false, true), sessionGeneration = "t1")))
        coordinator.onEvent(completed("track-1", ctx(false, true)))                    // UnknownSession
        coordinator.onEvent(PlaybackEvent.Completed(CompletedPlayback("track-1", ctx(false, true), sessionGeneration = null)))
        coordinator.onEvent(PlaybackEvent.Completed(CompletedPlayback("track-1", ctx(false, true), sessionGeneration = "t0")))
        runCurrent()
        assertEquals(2, api.calls.count { it.startsWith("create:") })   // signed-out and old-session stamps are dropped
    }
}
