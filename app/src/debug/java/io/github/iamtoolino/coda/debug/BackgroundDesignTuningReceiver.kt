package io.github.iamtoolino.coda.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.iamtoolino.coda.ui.theme.BackgroundDesignTuning
import io.github.iamtoolino.coda.ui.theme.BackgroundDesignTuningStore
import io.github.iamtoolino.coda.ui.theme.BackgroundDesignVariant

class BackgroundDesignTuningReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val variant = BackgroundDesignVariant.fromWireName(
            intent.getStringExtra(EXTRA_VARIANT),
        ) ?: return
        BackgroundDesignTuningStore.publish(
            BackgroundDesignTuning(
                enabled = true,
                variant = variant,
                artworkOpacity = intent.getFloatExtra(EXTRA_ARTWORK_OPACITY, 0.15f),
                blurRadiusDp = intent.getFloatExtra(EXTRA_BLUR_RADIUS_DP, 72f),
                artworkScale = intent.getFloatExtra(EXTRA_ARTWORK_SCALE, 1.20f),
                artworkSaturation = intent.getFloatExtra(EXTRA_ARTWORK_SATURATION, 0.78f),
                accentOpacity = intent.getFloatExtra(EXTRA_ACCENT_OPACITY, 0.34f),
                glowOpacity = intent.getFloatExtra(EXTRA_GLOW_OPACITY, 0.26f),
                vignetteOpacity = intent.getFloatExtra(EXTRA_VIGNETTE_OPACITY, 0.48f),
                blackFalloffOpacity = intent.getFloatExtra(EXTRA_BLACK_FALLOFF_OPACITY, 0.72f),
                baseLuminance = intent.getFloatExtra(EXTRA_BASE_LUMINANCE, 0.035f),
            ),
        )
    }

    companion object {
        const val ACTION = "io.github.iamtoolino.coda.debug.BACKGROUND_DESIGN_TUNING"
        const val EXTRA_VARIANT = "variant"
        const val EXTRA_ARTWORK_OPACITY = "artwork_opacity"
        const val EXTRA_BLUR_RADIUS_DP = "blur_radius_dp"
        const val EXTRA_ARTWORK_SCALE = "artwork_scale"
        const val EXTRA_ARTWORK_SATURATION = "artwork_saturation"
        const val EXTRA_ACCENT_OPACITY = "accent_opacity"
        const val EXTRA_GLOW_OPACITY = "glow_opacity"
        const val EXTRA_VIGNETTE_OPACITY = "vignette_opacity"
        const val EXTRA_BLACK_FALLOFF_OPACITY = "black_falloff_opacity"
        const val EXTRA_BASE_LUMINANCE = "base_luminance"
    }
}
