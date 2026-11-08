package dev.coda.android.ui.common

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The rating badge's size table (design lead brief) and the 1.3x font-scale cap. */
class BadgeSizeTest {
    @Test fun sizesFollowTheBrief() {
        assertEquals(listOf(22.dp, 18.dp, 14.dp), BadgeSize.entries.map { it.height })
        assertEquals(listOf(12f, 10.5f, 9f), BadgeSize.entries.map { it.textSp })
        assertEquals(listOf(10.dp, 9.dp, 7.dp), BadgeSize.entries.map { it.star })
        assertEquals(listOf(7.dp, 5.dp, 3.dp), BadgeSize.entries.map { it.inset })
        assertEquals(7.dp, BadgeSize.Small.radius)
    }

    @Test fun textGrowsWithTheFontUpTo130PercentThenStays() {
        for (size in BadgeSize.entries) {
            assertEquals(size.textSp, size.cappedTextSp(1f) * 1f, 1e-4f)
            assertEquals(size.textSp * 1.15f, size.cappedTextSp(1.15f) * 1.15f, 1e-4f)
            for (scale in listOf(1.3f, 1.5f, 2f)) assertEquals("$size at $scale", size.textSp * 1.3f, size.cappedTextSp(scale) * scale, 1e-4f)
        }
    }
}
