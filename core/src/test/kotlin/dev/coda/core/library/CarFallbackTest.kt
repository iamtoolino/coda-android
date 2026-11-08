package dev.coda.core.library

import dev.coda.core.connectivity.Reachability
import dev.coda.core.subsonic.Album
import dev.coda.core.subsonic.AlbumListPage
import dev.coda.core.subsonic.AlbumListType
import dev.coda.core.subsonic.SubsonicError
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** A car browse request is served live while the server answers, else from the phone. */
class CarFallbackTest {
    private fun state(link: Reachability.Link) = Reachability.State(link, known = true)
    private val online = state(Reachability.Link.ONLINE)

    @Test fun theServerAnswersTheListIsLive() = runTest {
        var phoneAsked = false
        assertEquals("live", CarFallback.serve(online, live = { "live" }, phone = { phoneAsked = true; "phone" }))
        assertEquals(false, phoneAsked)
    }

    @Test fun aLiveNullAnswerStaysLive() = runTest {
        // A tree id without children (null) is the server's answer, not a failure.
        assertEquals(null, CarFallback.serve<String?>(online, live = { null }, phone = { "phone" }))
    }

    @Test fun theServerDoesNotAnswerTheListComesFromThePhone() = runTest {
        for (error in listOf(SubsonicError.Network(IOException()), SubsonicError.Http(503), SubsonicError.Decoding(),
                SubsonicError.Http(403), IOException()))
            assertEquals(error.javaClass.simpleName, "phone", CarFallback.serve(online, live = { throw error }, phone = { "phone" }))
    }

    @Test fun anAnswerThatIsNotAFailureOfTheServerIsKept() = runTest {
        // Wrong login and an item the server doesn't have are the server's answers: the car sees them as today.
        assertThrows(SubsonicError.Unauthorized::class.java) {
            kotlinx.coroutines.runBlocking { CarFallback.serve(online, live = { throw SubsonicError.Unauthorized(401) }, phone = { "phone" }) }
        }
        assertThrows(SubsonicError.NotFound::class.java) {
            kotlinx.coroutines.runBlocking { CarFallback.serve(online, live = { throw SubsonicError.NotFound() }, phone = { "phone" }) }
        }
    }

    /** A server whose album list pages take [pageMs] each (or hang), [pages] full pages then a short one. */
    private class SlowServer(val pageMs: Long?, val pages: Int = 2) : TreeSource {
        var calls = 0
        var failing = false
        override suspend fun getAlbumList2(size: Int, offset: Int, type: AlbumListType): AlbumListPage {
            calls++
            if (failing) throw SubsonicError.Http(503)
            if (pageMs == null) awaitCancellation() else delay(pageMs)
            val n = if (offset / size < pages) size else 10
            return AlbumListPage(List(n) { Album("al-${offset + it}", "Album ${offset + it}") }, null)
        }
        override suspend fun getArtists() = error("unused")
        override suspend fun getArtist(id: String) = error("unused")
        override suspend fun getAlbum(id: String) = error("unused")
        override suspend fun getPlaylists() = error("unused")
        override suspend fun getPlaylist(id: String) = error("unused")
        override suspend fun getBookmarks() = error("unused")
        override suspend fun search3(query: String, artistCount: Int, albumCount: Int, songCount: Int) = error("unused")
    }

    /** The voice start chain swallows a missing server queue and list; one unanswered call still sends it to the phone at once. */
    @Test fun aHangingServerSendsTheStartChainToThePhoneAfterOneBound() = runTest {
        val asked = mutableListOf<String>()
        suspend fun chain(phone: Boolean): dev.coda.core.sync.StartQueueResolver.Choice? {
            val calls = CarFallback.Calls()
            val sources = object : dev.coda.core.sync.StartQueueResolver.Sources {
                override suspend fun ownQueue(): dev.coda.core.sync.OwnQueue? = null
                override suspend fun serverQueue() = if (phone) null else calls.call<dev.coda.core.subsonic.PlayQueue?> { asked += "queue"; awaitCancellation() }
                override suspend fun albumList(type: AlbumListType, size: Int, offset: Int) =
                    if (phone) listOf(Album("phone-$type", "Phone")).takeIf { type == AlbumListType.NEWEST }.orEmpty()
                    else calls.call { asked += "list-$type"; awaitCancellation() }
            }
            return dev.coda.core.sync.StartQueueResolver(sources).resolve(dev.coda.core.sync.StartQueueResolver.Reason.VOICE)
        }
        val choice = CarFallback.serve(online, live = { chain(false) }, phone = { chain(true) }) as dev.coda.core.sync.StartQueueResolver.Choice.Albums
        assertEquals(listOf("phone-NEWEST"), choice.albums.map { it.id })
        assertEquals("one bound, then the phone", CarFallback.LIVE_TIMEOUT_MS, currentTime)
        assertEquals("only the first call reached the server", listOf("queue"), asked)
    }

    @Test fun startChainCallsKeepTheServersOwnAnswers() = runTest {
        val calls = CarFallback.Calls()
        assertThrows(SubsonicError.NotFound::class.java) { kotlinx.coroutines.runBlocking { calls.call { throw SubsonicError.NotFound() } } }
        assertEquals("an answered error is no latch", "next", calls.call { "next" })
        assertThrows(SubsonicError.Http::class.java) { kotlinx.coroutines.runBlocking { calls.call { throw SubsonicError.Http(503) } } }
        var asked = false
        assertThrows(SubsonicError.Http::class.java) { kotlinx.coroutines.runBlocking { calls.call { asked = true; "late" } } }
        assertEquals(false, asked)
    }

    @Test fun aServerThatHangsFallsBackWithinTheBound() = runTest {
        val server = SlowServer(pageMs = null)
        val tree = LibraryTree(CarFallback.bounded(server))
        assertEquals("phone", CarFallback.serve(online, live = { tree.children("albums", 0, 50); "live" }, phone = { "phone" }))
        assertEquals(CarFallback.LIVE_TIMEOUT_MS, currentTime)
        // Never longer than today's single call (the client's 30 s call timeout).
        assertTrue(CarFallback.LIVE_TIMEOUT_MS < 30_000)
    }

    @Test fun aSlowButAnsweringServerFillsThePagedAlbumIndex() = runTest {
        // The bound is per call. Three 4 s pages (12 s in all) stay live and the index is kept.
        val server = SlowServer(pageMs = 4_000)
        val tree = LibraryTree(CarFallback.bounded(server), index = LibraryIndex(nowMs = { currentTime }))
        val first = CarFallback.serve(online, live = { tree.children("albums", 0, 50) }, phone = { null })
        assertTrue(first!!.isNotEmpty())
        assertEquals(3, server.calls)
        assertEquals(12_000L, currentTime)
        // The next Albums request comes from the index, without asking the server again.
        assertEquals(first, CarFallback.serve(online, live = { tree.children("albums", 0, 50) }, phone = { null }))
        assertEquals(3, server.calls)
    }

    @Test fun offlineAutomaticOrChosenGoesStraightToThePhone() = runTest {
        for (link in listOf(Reachability.Link.OFFLINE, Reachability.Link.OFFLINE_CHOSEN)) {
            var liveAsked = false
            assertEquals("phone", CarFallback.serve(state(link), live = { liveAsked = true; "live" }, phone = { "phone" }))
            assertEquals(false, liveAsked)
        }
    }

    @Test fun unreachableStillAsksTheServerFirst() = runTest {
        // The offer state is not offline yet: a server that answers again gives live lists on the next request.
        assertEquals("live", CarFallback.serve(state(Reachability.Link.UNREACHABLE), live = { "live" }, phone = { "phone" }))
        assertEquals("phone", CarFallback.serve(state(Reachability.Link.UNREACHABLE),
            live = { throw SubsonicError.Network(IOException()) }, phone = { "phone" }))
    }

    @Test fun serveReportsWhichTreeAnswered() = runTest {
        val sources = mutableListOf<CarFallback.Source>()
        CarFallback.serve(online, live = { "live" }, phone = { "phone" }, onSource = sources::add)
        CarFallback.serve(online, live = { throw SubsonicError.Http(503) }, phone = { "phone" }, onSource = sources::add)
        CarFallback.serve(state(Reachability.Link.OFFLINE), live = { "live" }, phone = { "phone" }, onSource = sources::add)
        assertEquals(listOf(CarFallback.Source.LIVE, CarFallback.Source.PHONE, CarFallback.Source.PHONE), sources)
        // The server's own answer (not found) reports nothing: no tree answered.
        runCatching { CarFallback.serve(online, live = { throw SubsonicError.NotFound() }, phone = { "phone" }, onSource = sources::add) }
        assertEquals(3, sources.size)
    }

    @Test fun carIndexReportsOnlySwitches() {
        val index = CarIndex()
        assertEquals(false, index.served(CarFallback.Source.LIVE))
        assertEquals(false, index.served(CarFallback.Source.LIVE))
        assertEquals(true, index.served(CarFallback.Source.PHONE))
        assertEquals(false, index.served(CarFallback.Source.PHONE))
        assertEquals(true, index.served(CarFallback.Source.LIVE))
    }

    /** F2: Albums once live (filling the 60 s index), then the next request in the given situation. */
    private suspend fun kotlinx.coroutines.test.TestScope.albumsAgain(change: (CarIndex) -> Unit): Pair<String, Int> {
        val server = SlowServer(pageMs = 0)
        val index = CarIndex { LibraryIndex(nowMs = { currentTime }) }
        fun serve(state: Reachability.State) = suspend {
            CarFallback.serve(state, live = { LibraryTree(CarFallback.bounded(server), index = index.forSession("s")).children("albums", 0, 50); "live" },
                phone = { "phone" }, onSource = { index.served(it) })
        }
        assertEquals("live", serve(online)())
        val filled = server.calls
        change(index)
        server.failing = true
        val unreachable = state(Reachability.Link.UNREACHABLE)
        return serve(unreachable)() to server.calls - filled
    }

    @Test fun anUnreachableServerIsNotAnsweredFromTheAlbumIndex() = runTest {
        // Well inside the 60 s TTL: the drop clears the index, so the server is asked, fails, and the phone answers.
        val (answer, calls) = albumsAgain { it.reachabilityChanged(state(Reachability.Link.UNREACHABLE)) }
        assertEquals("phone", answer)
        assertEquals(1, calls)
    }

    @Test fun aSwitchToThePhoneClearsTheAlbumIndex() = runTest {
        // Reachability may not notice (Coda not attended): another list answered by the phone clears it too.
        val (answer, _) = albumsAgain { it.served(CarFallback.Source.PHONE) }
        assertEquals("phone", answer)
    }

    @Test fun withoutADropTheIndexStillAnswersWithinItsTtl() = runTest {
        // The short TTL hit online is kept (control for the two tests above).
        val (answer, calls) = albumsAgain { }
        assertEquals("live", answer)
        assertEquals(0, calls)
    }

    @Test fun aSwitchBackToLiveKeepsTheFreshLiveIndex() {
        // The reload's live answers fill the index; the PHONE->LIVE switch must not throw it away.
        val index = CarIndex()
        index.served(CarFallback.Source.PHONE)
        val live = index.forSession("s")
        assertEquals(true, index.served(CarFallback.Source.LIVE))
        assertTrue(live === index.forSession("s"))
        assertEquals(true, index.served(CarFallback.Source.PHONE))
        assertTrue(live !== index.forSession("s"))
    }

    @Test fun theOfflineFlagsOwnReloadIsNoSecondSwitch() {
        val index = CarIndex()
        index.served(CarFallback.Source.LIVE)
        val before = index.forSession("s")
        index.offlineChanged(phoneFirst = true)
        assertTrue(before !== index.forSession("s"))
        assertTrue(index.onPhone)
        assertEquals(false, index.served(CarFallback.Source.PHONE))
        // Back: the reload's first answer, whichever tree, is no switch; the next real flip is.
        index.offlineChanged(phoneFirst = false)
        assertEquals(false, index.served(CarFallback.Source.LIVE))
        assertEquals(true, index.served(CarFallback.Source.PHONE))
    }

    @Test fun aFlappingSourceReloadsAtMostOncePerGapPlusTheTrailingOne() = runTest {
        val switches = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 64)
        val reloads = mutableListOf<Pair<Long, Int>>()
        val job = launch { with(CarReloads) { switches.atMostEvery(CarReloads.SWITCH_GAP_MS) }.collect { reloads += currentTime to it } }
        switches.subscriptionCount.first { it > 0 }
        // A flaky link: a switch every 300 ms for 12 s.
        for (i in 0..39) { switches.emit(i); delay(300) }
        delay(30_000)
        job.cancel()
        // At once, then one per 10 s window; the last switch (39, at 11.7 s) still arrives at the end of the second window.
        assertEquals(listOf(0L to 0, 10_000L to 33, 20_000L to 39), reloads)
    }

    @Test fun aCatalogueRunReloadsTheCarOnlyWhenItsListsComeFromThePhoneAndChanged() {
        val changed = CatalogueCollector.Result(fetched = 2)
        // Online (the car is served live): no reload, whatever the pass did.
        assertEquals(false, CarReloads.catalogueReloads(onPhone = false, pass = changed, keptChanged = true))
        // From the phone: only a pass that changed something, or new kept sets.
        assertEquals(false, CarReloads.catalogueReloads(onPhone = true, pass = CatalogueCollector.Result(failed = 3, pending = 1), keptChanged = false))
        assertEquals(true, CarReloads.catalogueReloads(onPhone = true, pass = changed, keptChanged = false))
        assertEquals(true, CarReloads.catalogueReloads(onPhone = true, pass = CatalogueCollector.Result(removedAlbums = 1), keptChanged = false))
        assertEquals(true, CarReloads.catalogueReloads(onPhone = true, pass = null, keptChanged = true))
    }
}
