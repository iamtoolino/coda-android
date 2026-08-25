package io.github.iamtoolino.coda.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundDesignTuningTest {
    @Test
    fun `wire names resolve known variants only`() {
        assertEquals(
            BackgroundDesignVariant.QUIET_MATERIAL,
            BackgroundDesignVariant.fromWireName("quiet_material"),
        )
        assertEquals(null, BackgroundDesignVariant.fromWireName("unknown"))
        assertEquals(RatingTintSource.ALBUM, RatingTintSource.fromWireName("album"))
        assertEquals(DebugTypeface.SYSTEM, DebugTypeface.fromWireName("system"))
        assertEquals(MiniProgressPlacement.TOP, MiniProgressPlacement.fromWireName("top"))
    }

    @Test
    fun `tuning values are bounded`() {
        val tuning = BackgroundDesignTuning(
            accentOpacity = -1f,
            glowOpacity = 2f,
            vignetteOpacity = 2f,
            blackFalloffOpacity = -1f,
            primaryTextLuminance = 2f,
            secondaryTextLuminance = -1f,
            headingWeight = 999,
            ratingSizeDp = 2f,
            miniPlayerOpacity = 0f,
            progressThicknessDp = 9f,
        ).sanitized()

        assertEquals(0f, tuning.accentOpacity)
        assertEquals(0.65f, tuning.glowOpacity)
        assertEquals(0.90f, tuning.vignetteOpacity)
        assertEquals(0f, tuning.blackFalloffOpacity)
        assertEquals(1f, tuning.primaryTextLuminance)
        assertEquals(0.42f, tuning.secondaryTextLuminance)
        assertEquals(800, tuning.headingWeight)
        assertEquals(22f, tuning.ratingSizeDp)
        assertEquals(0.55f, tuning.miniPlayerOpacity)
        assertEquals(4f, tuning.progressThicknessDp)
    }
}
