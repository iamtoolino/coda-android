package dev.coda.android.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Account card's dimmed "http://" is still readable text (WCAG AA, 4.5:1) on the card. */
class SettingsContrastTest {
    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + .05f) / (lo + .05f)
    }

    @Test fun theDimmedSchemeReadsOnTheCard() {
        for (page in listOf(Color.Black, Color(0xFF121417))) {
            val card = SettingsCardColor.compositeOver(page)
            val ratio = contrast(SettingsTextDim.compositeOver(card), card)
            assertTrue("scheme contrast $ratio on ${page}", ratio >= 4.5f)
        }
    }
}
