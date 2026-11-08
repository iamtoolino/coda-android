package dev.coda.android.ui.library

import dev.coda.core.library.AlbumSectionKind
import dev.coda.core.library.LibrarySection
import dev.coda.core.subsonic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Albums tab paging and sections, pinned sections and the discography indices. */
@OptIn(ExperimentalCoroutinesApi::class)
class Ds9LibraryTest {
    private fun library(count: Int) = (0 until count).map { Album("a%04d".format(it), "${'A' + it / 100}x %04d".format(it)) }
    private fun check(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block() } finally { Dispatchers.resetMain() }
    }
    private class Server(val all: List<Album>, val cap: Int = Int.MAX_VALUE, val knowsTotal: Boolean = false, val ignoresOffset: Boolean = false) {
        val requests = mutableListOf<Triple<AlbumListType, Int, Int>>()
        var failAt: Int? = null
        var latency = 0L
        suspend fun page(type: AlbumListType, offset: Int, size: Int): AlbumListPage {
            requests += Triple(type, offset, size)
            if (latency > 0) delay(latency)
            if (failAt == offset) throw SubsonicError.Network(java.io.IOException())
            val from = if (ignoresOffset) 0 else offset
            return AlbumListPage(all.drop(from).take(minOf(size, cap)), if (knowsTotal) all.size else null)
        }
    }
    private var now = 1_000_000L
    private fun TestScope.model(server: Server, recovered: kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.emptyFlow()) =
        CollectionViewModel({ true }, {}, server::page, { emptyList() }, { error("unused") }, null, 100, recovered,
            StandardTestDispatcher(testScheduler), { now })

    @Test fun albumsTabPagesInTheWholeListWithSections() = check {
        val server = Server(library(1234))
        val model = model(server)
        model.albums(AlbumListType.ALPHABETICAL, AlbumSectionKind.TITLE); advanceUntilIdle()
        assertEquals(listOf(0 to 100, 100 to 500, 600 to 500, 1100 to 500), server.requests.map { it.second to it.third })
        val state = model.state.value
        assertEquals(1234, state.albums.size); assertTrue(state.complete)
        assertEquals(('A'..'M').map { "$it" }, state.sections.map { it.label })
        assertEquals(1200, state.sections.last().start)
    }

    @Test fun randomIsNotFilledAndReshuffleStartsOver() = check {
        val server = Server(library(400))
        val model = model(server)
        model.albums(AlbumListType.RANDOM, AlbumSectionKind.NONE); advanceUntilIdle()
        assertEquals(1, server.requests.size); assertTrue(model.state.value.sections.isEmpty())
        model.reshuffle(); advanceUntilIdle()
        assertEquals(listOf(0, 0), server.requests.map { it.second })
        assertEquals(100, model.state.value.albums.size)
    }

    @Test fun belowSixtyAlbumsThereAreNoSections() = check {
        val model = model(Server(library(59)))
        model.albums(AlbumListType.ALPHABETICAL, AlbumSectionKind.TITLE); advanceUntilIdle()
        assertTrue(model.state.value.complete); assertTrue(model.state.value.sections.isEmpty())
    }

    @Test fun aFailedFillPageKeepsTheListAndCarriesOnWhenTheNetworkIsBack() = check {
        val server = Server(library(900)).apply { failAt = 600 }
        val back = MutableSharedFlow<Unit>()
        val model = model(server, back)
        model.albums(AlbumListType.NEWEST, AlbumSectionKind.ADDED); advanceUntilIdle()
        assertEquals(600, model.state.value.albums.size)
        assertTrue(model.state.value.moreFailed); assertFalse(model.state.value.failed)
        server.failAt = null
        back.emit(Unit); advanceUntilIdle()
        assertEquals(900, model.state.value.albums.size); assertTrue(model.state.value.complete)
    }

    @Test fun revisitingInsideTheFreshnessWindowAsksTheServerForNothing() = check {
        val server = Server(library(700))
        val model = model(server)
        model.albums(AlbumListType.ALPHABETICAL, AlbumSectionKind.TITLE); advanceUntilIdle()
        // In spirit: the first request is one page of 100, and nothing larger than a 500 fill page ever follows.
        assertEquals(100, server.requests.first().third); assertTrue(server.requests.all { it.third <= 500 })
        val before = server.requests.size
        now += 4 * 60_000
        model.albums(AlbumListType.ALPHABETICAL, AlbumSectionKind.TITLE); advanceUntilIdle()
        assertEquals(before, server.requests.size); assertEquals(700, model.state.value.albums.size)
        now += 2 * 60_000
        model.albums(AlbumListType.ALPHABETICAL, AlbumSectionKind.TITLE); advanceUntilIdle()
        assertTrue("older than the window: reloads", server.requests.size > before)
    }

    @Test fun pullToRefreshReloadsAndKeepsTheShownListWhileItRuns() = check {
        val server = Server(library(700))
        val model = model(server)
        model.albums(AlbumListType.NEWEST, AlbumSectionKind.ADDED); advanceUntilIdle()
        server.failAt = 100
        model.albums(AlbumListType.NEWEST, AlbumSectionKind.ADDED, refresh = true); advanceUntilIdle()
        assertEquals(700, model.state.value.albums.size)
        assertTrue(model.state.value.moreFailed); assertFalse(model.state.value.failed)
    }

    @Test fun aSortChangeIsDerivedFromTheLoadedLibraryWithoutARequest() = check {
        val albums = library(300).mapIndexed { i, a -> a.copy(playCount = i % 3, created = "2025-%02d-01T00:00:00Z".format(1 + i % 12),
            userRating = if (i % 10 == 0) 5 else null) }
        val server = Server(albums)
        val model = model(server)
        model.albums(AlbumListType.ALPHABETICAL, AlbumSectionKind.TITLE); advanceUntilIdle()
        val before = server.requests.size
        model.albums(AlbumListType.NEWEST, AlbumSectionKind.ADDED); advanceUntilIdle()
        model.albums(AlbumListType.MOST_PLAYED, AlbumSectionKind.FREQUENT); advanceUntilIdle()
        assertEquals(before, server.requests.size)
        assertEquals(200, model.state.value.albums.size); assertTrue(model.state.value.albums.all { (it.playCount ?: 0) > 0 })
        assertTrue(model.state.value.complete)
        model.albums(AlbumListType.TOP_RATED, AlbumSectionKind.RATED); advanceUntilIdle()
        assertEquals(30, model.state.value.albums.size)
        model.albums(AlbumListType.NEWEST, AlbumSectionKind.ADDED); advanceUntilIdle()
        assertEquals("2025-12-01T00:00:00Z", model.state.value.albums.first().created)
        assertEquals(before, server.requests.size)
    }

    // The way back ends on a whole fresh server list, never on its first page or a cached derivation.
    @Test fun onlyAWholeFreshListCountsAsAnAnswerNotItsFirstPage() = check {
        val server = Server(library(1234)).apply { latency = 10 }
        val model = model(server)
        model.albums(AlbumListType.ALPHABETICAL, AlbumSectionKind.TITLE)
        advanceTimeBy(15); runCurrent()
        assertTrue(model.state.value.albums.size >= 100); assertFalse(model.state.value.complete)
        assertEquals("a first page is no answer", 0, model.state.value.answers)
        advanceUntilIdle()
        assertTrue(model.state.value.complete); assertEquals(1, model.state.value.answers)
    }

    @Test fun aCachedSortDerivationIsNoAnswer() = check {
        val model = model(Server(library(300)))
        model.albums(AlbumListType.ALPHABETICAL, AlbumSectionKind.TITLE); advanceUntilIdle()
        val before = model.state.value.answers
        model.albums(AlbumListType.NEWEST, AlbumSectionKind.ADDED); advanceUntilIdle()
        assertTrue(model.state.value.complete)
        assertEquals("derived from the loaded library", before, model.state.value.answers)
        model.albums(AlbumListType.NEWEST, AlbumSectionKind.ADDED, refresh = true); advanceUntilIdle()
        assertEquals(before + 1, model.state.value.answers)
    }

    @Test fun anUnfilledListIsAWholeAnswerWithItsFirstPage() = check {
        val model = model(Server(library(400)))
        model.albums(AlbumListType.RANDOM, AlbumSectionKind.NONE); advanceUntilIdle()
        assertEquals(1, model.state.value.answers)
    }

    @Test fun leavingTheTabStopsPagingAndComingBackCarriesOnWithoutRestarting() = check {
        val server = Server(library(1500)).apply { latency = 10 }
        val model = model(server)
        model.albums(AlbumListType.ALPHABETICAL, AlbumSectionKind.TITLE)
        advanceTimeBy(15); runCurrent()
        assertTrue(server.requests.size >= 2)
        model.pause(); advanceUntilIdle()
        val partial = server.requests.size
        assertFalse(model.state.value.complete); assertFalse(model.state.value.loadingMore)
        model.albums(AlbumListType.ALPHABETICAL, AlbumSectionKind.TITLE); advanceUntilIdle()
        assertTrue(model.state.value.complete); assertEquals(1500, model.state.value.albums.size)
        assertEquals("page 0 is not asked again", 1, server.requests.count { it.second == 0 })
        assertTrue(server.requests.size > partial)
    }

    @Test fun aServerThatCapsThePageSizeStillDeliversTheWholeList() = check {
        val server = Server(library(900), cap = 200, knowsTotal = true)
        val model = model(server)
        model.albums(AlbumListType.ALPHABETICAL, AlbumSectionKind.TITLE); advanceUntilIdle()
        assertEquals(900, model.state.value.albums.size); assertTrue(model.state.value.complete)
    }

    @Test fun aServerThatIgnoresTheOffsetEndsTheListInsteadOfLooping() = check {
        val server = Server(library(900), ignoresOffset = true)
        val model = model(server)
        model.albums(AlbumListType.ALPHABETICAL, AlbumSectionKind.TITLE); advanceUntilIdle()
        assertTrue(model.state.value.complete); assertTrue("stopped: ${server.requests.size} requests", server.requests.size <= 3)
        assertEquals(500, model.state.value.albums.size)
    }

    @Test fun titleSectionsFollowTheFoldedKeyWhateverOrderTheServerReturns() = check {
        // Server order ignores articles for some names only: "The ..." and "A ..." sit under T and A in it.
        val names = listOf("The Dark Side", "A Night Out", "Abbey Road", "Blue", "The Wall", "Zebra", "Éclair", "Cat", "Dune", "Eden")
        val albums = (0 until 80).map { Album("x$it", "${names[it % names.size]} $it") } +
            Album("s1", "The Weird One", sortName = "Alpha Weird")
        val server = Server(albums)
        val model = model(server)
        model.albums(AlbumListType.ALPHABETICAL, AlbumSectionKind.TITLE); advanceUntilIdle()
        val state = model.state.value
        val labels = state.sections.map { it.label }
        assertEquals("one header per letter, no repeats", labels.distinct(), labels)
        assertEquals(listOf("A", "B", "C", "D", "E", "N", "W", "Z"), labels)
        // "The Dark Side" sorts under D, "The Wall" under W, "A Night Out" under N, sortName "Alpha Weird" under A.
        val letter = { a: Album -> state.sections.first { a in state.albums.subList(it.start, it.start + it.count) }.label }
        assertEquals("D", letter(state.albums.first { it.name.startsWith("The Dark Side") }))
        assertEquals("N", letter(state.albums.first { it.name.startsWith("A Night Out") }))
        assertEquals("A", letter(state.albums.first { it.id == "s1" }))
        assertEquals("E", letter(state.albums.first { it.name.startsWith("Éclair") }))
    }

    @Test fun albumsTabLoadsTheWholeSyntheticLargeLibrary() = runBlocking {
        val library = dev.coda.core.testing.SyntheticLargeLibrary()
        dev.coda.core.testing.FakeSubsonicServer().use { server ->
            server.largeLibrary = library
            val client = SubsonicClient(SubsonicConfig(server.baseUrl, "synthetic", "synthetic-password"))
            for (type in listOf(AlbumListType.ALPHABETICAL, AlbumListType.NEWEST, AlbumListType.RECENTLY_PLAYED)) {
                val all = mutableListOf<Album>()
                var offset = 0
                while (true) {
                    val page = client.getAlbumList2(if (offset == 0) 100 else 500, offset, type).albums
                    all += page; offset += page.size
                    if (page.size < (if (offset == page.size) 100 else 500)) break
                }
                assertEquals("$type distinct", all.size, all.distinctBy { it.id }.size)
                if (type == AlbumListType.ALPHABETICAL) assertEquals(library.albums.size, all.size)
            }
        }
    }

    private fun items(vararg rows: Triple<Int, Int, Int>) = rows.map { (index, top, size) -> VisibleItem(index, top, size, null) }

    @Test fun pinnedSectionAndPushUp() {
        val headers = intArrayOf(2, 10, 20)
        // At the top: the first header is still below the line.
        assertEquals(-1, pinnedSection(headers, items(Triple(0, 0, 100), Triple(1, 100, 50), Triple(2, 150, 40)), 60f))
        // Header 0 has scrolled under the line.
        val scrolled = items(Triple(2, 50, 40), Triple(3, 90, 200), Triple(10, 290, 40))
        assertEquals(0, pinnedSection(headers, scrolled, 60f))
        assertEquals(0f, pinnedPush(headers, scrolled, 0, 60f, 40f))
        // The next header approaches: the pinned one moves up with it.
        val pushing = items(Triple(9, -100, 180), Triple(10, 80, 40), Triple(11, 120, 200))
        assertEquals(0, pinnedSection(headers, pushing, 60f))
        assertEquals(-20f, pinnedPush(headers, pushing, 0, 60f, 40f))
        // At the list's top the first header itself spans the line but has not reached it: nothing pinned.
        assertEquals(-1, pinnedSection(headers, items(Triple(2, 64, 40), Triple(3, 104, 200)), 60f))
        // Exactly at the line, the next section takes over.
        assertEquals(1, pinnedSection(headers, items(Triple(10, 60, 40), Triple(11, 100, 200)), 60f))
    }

    @Test fun sectionIndexAndInitials() {
        assertEquals("ET", initials("Ekström & the Long Name"))
        assertEquals("H", initials("halden"))
        assertEquals(3, sectionIndexAt(listOf(LibrarySection("#", 2, 0), LibrarySection("A", 1, 2), LibrarySection("B", 1, 3), LibrarySection("E", 2, 4)), 5))
    }
}
