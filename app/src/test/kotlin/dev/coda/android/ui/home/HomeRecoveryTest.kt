package dev.coda.android.ui.home

import dev.coda.core.subsonic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** No stale Retry on Home once the server answers again. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeRecoveryTest {
    private fun check(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block() } finally { Dispatchers.resetMain() }
    }
    private fun bookmark() = Bookmark(
        comment = """{"protocol":"album-resume-bookmark","protocolVersion":1,"resumeSongId":"next-1"}""",
        changed = "00001", entry = Song(id = "anchor-1", title = "Synthetic", albumId = "album-1", album = "Synthetic"))

    @Test fun resumeArrivingAfterAShelvesFailureReloadsTheShelvesAndClearsRetry() = check {
        var shelvesCalls = 0
        val model = HomeViewModel({ true }, {}, {
            if (++shelvesCalls == 1) throw SubsonicError.Network(java.io.IOException())
            HomeState(albums = listOf(Album("one", "Synthetic")), loading = false)
        }, { delay(100); listOf(bookmark()) }, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals("album-1", model.state.value.continueListening.single().album.id)
        assertFalse("Retry must not stay next to fresh content", model.state.value.failed)
        assertEquals(2, shelvesCalls)
        assertEquals("one", model.state.value.albums.single().id)
    }

    @Test fun networkBackReloadsFailedHome() = check {
        var online = false
        val recovered = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val model = HomeViewModel({ true }, {}, {
            if (!online) throw SubsonicError.Network(java.io.IOException())
            HomeState(albums = listOf(Album("one", "Synthetic")), loading = false)
        }, { if (online) emptyList() else throw SubsonicError.Network(java.io.IOException()) },
            StandardTestDispatcher(testScheduler), recovered = recovered)
        advanceUntilIdle()
        assertTrue(model.state.value.failed)
        online = true
        recovered.tryEmit(Unit); advanceUntilIdle()
        assertFalse(model.state.value.failed)
        assertEquals("one", model.state.value.albums.single().id)
    }

    @Test fun shelfHeadingsMapToTheirSortAndPlaylists() {
        assertEquals(AlbumListType.NEWEST, HomeList.forTitle("Recently Added Albums").type)
        assertEquals(AlbumListType.RECENT_RELEASES, HomeList.forTitle("Recent Releases").type)
        assertEquals(AlbumListType.RECENTLY_PLAYED, HomeList.forTitle("Recently Played").type)
        assertNull(HomeList.entries.firstOrNull { it.title == "Continue Listening" })  // No page behind it
        assertEquals(HomeList.PLAYLISTS, HomeList.forTitle("Playlists"))
    }
}
