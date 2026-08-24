package io.github.iamtoolino.coda.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class BackgroundDesignVariant(val wireName: String) {
    CURRENT("current"),
    OLED_GLOW("oled_glow"),
    MACOS_FIELD("macos_field"),
    DEEP_ARTWORK("deep_artwork"),
    ;

    companion object {
        fun fromWireName(value: String?): BackgroundDesignVariant? = entries.firstOrNull {
            it.wireName == value
        }
    }
}

internal data class BackgroundDesignTuning(
    val enabled: Boolean = false,
    val variant: BackgroundDesignVariant = BackgroundDesignVariant.CURRENT,
    val artworkOpacity: Float = 0.15f,
    val blurRadiusDp: Float = 72f,
    val artworkScale: Float = 1.20f,
    val artworkSaturation: Float = 0.78f,
    val accentOpacity: Float = 0.34f,
    val glowOpacity: Float = 0.26f,
    val vignetteOpacity: Float = 0.48f,
    val blackFalloffOpacity: Float = 0.72f,
    val baseLuminance: Float = 0.035f,
) {
    fun sanitized(): BackgroundDesignTuning = copy(
        artworkOpacity = artworkOpacity.coerceIn(0f, 0.40f),
        blurRadiusDp = blurRadiusDp.coerceIn(0f, 120f),
        artworkScale = artworkScale.coerceIn(1f, 1.50f),
        artworkSaturation = artworkSaturation.coerceIn(0f, 1.50f),
        accentOpacity = accentOpacity.coerceIn(0f, 0.65f),
        glowOpacity = glowOpacity.coerceIn(0f, 0.65f),
        vignetteOpacity = vignetteOpacity.coerceIn(0f, 0.90f),
        blackFalloffOpacity = blackFalloffOpacity.coerceIn(0f, 1f),
        baseLuminance = baseLuminance.coerceIn(0f, 0.10f),
    )
}

internal object BackgroundDesignTuningStore {
    var current by mutableStateOf(BackgroundDesignTuning())
        private set

    fun publish(tuning: BackgroundDesignTuning) {
        current = tuning.sanitized()
    }

    fun reset() {
        current = BackgroundDesignTuning()
    }
}
