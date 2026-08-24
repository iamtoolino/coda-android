package io.github.iamtoolino.coda.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundDesignTuningTest {
    @Test
    fun `wire names resolve known variants only`() {
        assertEquals(
            BackgroundDesignVariant.DEEP_ARTWORK,
            BackgroundDesignVariant.fromWireName("deep_artwork"),
        )
        assertEquals(null, BackgroundDesignVariant.fromWireName("unknown"))
    }

    @Test
    fun `tuning values are bounded`() {
        val tuning = BackgroundDesignTuning(
            artworkOpacity = 2f,
            blurRadiusDp = -1f,
            artworkScale = 4f,
            artworkSaturation = 3f,
            accentOpacity = -1f,
            glowOpacity = 2f,
            vignetteOpacity = 2f,
            blackFalloffOpacity = -1f,
            baseLuminance = 1f,
        ).sanitized()

        assertEquals(0.40f, tuning.artworkOpacity)
        assertEquals(0f, tuning.blurRadiusDp)
        assertEquals(1.50f, tuning.artworkScale)
        assertEquals(1.50f, tuning.artworkSaturation)
        assertEquals(0f, tuning.accentOpacity)
        assertEquals(0.65f, tuning.glowOpacity)
        assertEquals(0.90f, tuning.vignetteOpacity)
        assertEquals(0f, tuning.blackFalloffOpacity)
        assertEquals(0.10f, tuning.baseLuminance)
    }
}
