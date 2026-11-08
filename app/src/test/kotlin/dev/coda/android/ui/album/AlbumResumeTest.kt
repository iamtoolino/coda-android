package dev.coda.android.ui.album

import org.junit.Assert.assertEquals
import org.junit.Test

/** One rule for Resume album on the album page card and in the album ⋮ sheet. */
class AlbumResumeTest {
    private val songs = listOf("s1", "s2", "s3", "s4", "s5")

    @Test fun resumableAlbumResumesAtItsTrack() {
        assertEquals(4, albumResumeIndex(songs, "s5", currentMediaId = null, here = null))
        assertEquals(1, albumResumeIndex(songs, "s2", currentMediaId = "song/other", here = null))
    }

    @Test fun noResumePointOrUnknownTrackIsNotResumable() {
        assertEquals(-1, albumResumeIndex(songs, null, null, null))
        assertEquals(-1, albumResumeIndex(songs, "gone", null, null))
        assertEquals(-1, albumResumeIndex(emptyList(), "s5", null, null))
    }

    @Test fun theAlbumNowPlayingIsNotResumable() {
        assertEquals(-1, albumResumeIndex(songs, "s5", currentMediaId = "song/s2", here = null))
    }

    @Test fun offlineOnlyWhenTheResumeTrackIsOnThePhone() {
        assertEquals(-1, albumResumeIndex(songs, "s5", null, here = { it == "s1" }))
        assertEquals(4, albumResumeIndex(songs, "s5", null, here = { it == "s5" }))
    }
}
