package dev.coda.android.ui.library

import dev.coda.core.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Test

/** The playlist page uses the Playlists tab's formatter, so both say "2 h", not "2 h 0 min". */
class PlaylistDurationTest {
    private fun songs(vararg seconds: Int) = seconds.mapIndexed { i, s -> Song("s$i", "Song $i", duration = s) }
    @Test fun pageAndListShareTheFormat() {
        assertEquals("1 h 32 min", playlistDuration(songs(3000, 2520)))
        assertEquals("2 h", playlistDuration(songs(3600, 3600)))
        assertEquals("45 min", playlistDuration(songs(2700)))
        assertEquals("1 min", playlistDuration(songs(20)))
        assertEquals("0 min", playlistDuration(emptyList()))
    }
}
