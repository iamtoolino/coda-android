package dev.coda.android.ui.player

import org.junit.Assert.*
import org.junit.Test

/** A programmatic track change slides the Now Playing sleeve only when the cover changes. */
class SleeveAnimationTest {
    @Test fun sameAlbumCoverDoesNotAnimate() = assertFalse(sleeveAnimates("al-1", "al-1"))
    @Test fun differentCoverAnimates() = assertTrue(sleeveAnimates("al-1", "al-2"))
    @Test fun twoPlaceholdersDoNotAnimate() = assertFalse(sleeveAnimates(null, null))
    @Test fun placeholderToArtworkAnimates() {
        assertTrue(sleeveAnimates(null, "al-1"))
        assertTrue(sleeveAnimates("al-1", null))
    }
}
