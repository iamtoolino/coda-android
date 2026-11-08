package dev.coda.android.ui.album

import org.junit.Assert.assertEquals
import org.junit.Test

/** The album title keeps its size when it fits, shrinks in 1 sp steps down to the minimum, then ellipsizes at the minimum. */
class AlbumTitleFitTest {
    // A fake monospace font: every character is 0.6 em wide.
    private fun width(chars: Int): (Float) -> Float = { sp -> chars * sp * 0.6f }

    @Test fun fittingTitleKeepsTheFullSize() {
        assertEquals(26f, fitTitleSp(400f, widthPx = width(20)), 0f)       // 20 * 15.6 = 312
        assertEquals(26f, fitTitleSp(312f, widthPx = width(20)), 0.001f)   // exactly fits
    }

    @Test fun overlongTitleShrinksInOneSpStepsToTheLargestSizeThatFits() {
        // 24 chars: 26 sp = 374.4, 25 = 360, 24 = 345.6, 23 = 331.2, 22 = 316.8
        assertEquals(25f, fitTitleSp(361f, widthPx = width(24)), 0f)
        assertEquals(24f, fitTitleSp(350f, widthPx = width(24)), 0f)
        assertEquals(23f, fitTitleSp(335f, widthPx = width(24)), 0f)
        assertEquals(22f, fitTitleSp(317f, widthPx = width(24)), 0f)
    }

    @Test fun neverSmallerThanTheMinimumAndTheCallerEllipsizesThere() {
        assertEquals(ALBUM_TITLE_MIN_SP, fitTitleSp(100f, widthPx = width(60)), 0f)
        assertEquals(20f, fitTitleSp(100f, minSp = 20f, widthPx = width(60)), 0f)
    }

    @Test fun everyTriedSizeIsOnTheStepGrid() {
        val tried = mutableListOf<Float>()
        fitTitleSp(1f) { sp -> tried += sp; 1000f }
        assertEquals(listOf(26f, 25f, 24f, 23f, 22f, 21f), tried)
        assertEquals(20f, ALBUM_TITLE_MIN_SP, 0f)
    }

    @Test fun artistHitAreaReachesUpIntoTheTitleRowOnly() {
        val up = artistHitUp(androidx.compose.ui.unit.Dp(21f), androidx.compose.ui.unit.Dp(34f))
        assertEquals(25.5f, up.value, 0.001f)                       // 48 - 21 - 1.5
        assertEquals(34f, artistHitUp(androidx.compose.ui.unit.Dp(2f), androidx.compose.ui.unit.Dp(34f)).value, 0f) // never above the block
        assertEquals(0f, artistHitUp(androidx.compose.ui.unit.Dp(60f), androidx.compose.ui.unit.Dp(34f)).value, 0f)
    }
}
