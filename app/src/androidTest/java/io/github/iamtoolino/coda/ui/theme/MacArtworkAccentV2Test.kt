package io.github.iamtoolino.coda.ui.theme

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MacArtworkAccentV2Test {
    @Test
    fun chromaticMinoritySelectsSupportedBlueFamily() {
        val colors = IntArray(100) { index ->
            when (index) {
                in 0 until 60 -> Color.rgb(120, 120, 120)
                in 60 until 90 -> Color.rgb(30, 80, 220)
                else -> Color.rgb(220, 45, 35)
            }
        }

        val result = MacArtworkAccentV2.select(colors)

        assertTrue(Color.blue(result) > Color.red(result))
        assertTrue(Color.blue(result) > Color.green(result))
    }

    @Test
    fun tinyAccentDoesNotOvertakeNeutralArtwork() {
        val colors = IntArray(100) { index ->
            if (index < 2) Color.rgb(240, 25, 25) else Color.rgb(110, 110, 110)
        }

        val result = MacArtworkAccentV2.select(colors)
        val spread = maxOf(Color.red(result), Color.green(result), Color.blue(result)) -
            minOf(Color.red(result), Color.green(result), Color.blue(result))

        assertTrue(spread < 20)
    }

    @Test
    fun unreadableBlackFallsBackToBrand() {
        assertEquals(Color.rgb(43, 122, 130), MacArtworkAccentV2.select(IntArray(100) { Color.BLACK }))
    }
}
