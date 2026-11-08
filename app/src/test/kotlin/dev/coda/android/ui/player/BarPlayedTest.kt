package dev.coda.android.ui.player

import org.junit.Assert.*
import org.junit.Test

/** A waveform bar flips to played as the playhead crosses its centre. */
class BarPlayedTest {
    @Test fun noBarIsPlayedAtTheStart() {
        assertTrue((0 until 44).none { barPlayed(it, 0f) })
    }

    @Test fun aBarFlipsAtItsCentre() {
        for (i in listOf(0, 21, 43)) {
            val centre = (i + 0.5f) / 44f
            assertFalse("bar $i before its centre", barPlayed(i, Math.nextDown(centre)))
            assertTrue("bar $i at its centre", barPlayed(i, centre))
            // Its left edge alone (the old rule) no longer counts.
            if (i > 0) assertFalse("bar $i at its left edge", barPlayed(i, i / 44f))
        }
        assertTrue((0 until 44).all { barPlayed(it, 1f) })
    }
}
