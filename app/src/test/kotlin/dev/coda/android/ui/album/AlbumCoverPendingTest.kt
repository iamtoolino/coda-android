package dev.coda.android.ui.album

import dev.coda.core.subsonic.Album
import dev.coda.core.subsonic.AlbumPage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The header shows the logo only for an album known to have no cover, never while the cover id is just not known yet. */
class AlbumCoverPendingTest {
    private val loaded = AlbumPage(Album("a", "Album"), emptyList())

    @Test fun stubWhileLoadingIsPending() {
        assertTrue(albumCoverPending(null, AlbumState()))
        assertTrue("a refresh keeps the tile", albumCoverPending(null, AlbumState(loading = true, failed = false)))
    }

    @Test fun knownCoverIsNeverPending() {
        assertFalse(albumCoverPending("cover-1", AlbumState()))
        assertFalse(albumCoverPending("cover-1", AlbumState(loaded, false)))
    }

    @Test fun loadedAlbumWithoutCoverShowsTheLogo() {
        assertFalse(albumCoverPending(null, AlbumState(loaded, false)))
        assertFalse("a saved copy that is still refreshing", albumCoverPending(null, AlbumState(loaded, true)))
    }

    @Test fun failedLoadShowsTheLogo() {
        assertFalse(albumCoverPending(null, AlbumState(null, false, true)))
    }
}
