package io.github.iamtoolino.coda.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class BackgroundDesignVariant(val wireName: String) {
    OLED_GLOW("oled_glow"),
    QUIET_MATERIAL("quiet_material"),
    OLED_INSTRUMENT("oled_instrument"),
    SLEEVE_EDITORIAL("sleeve_editorial"),
    ;

    companion object {
        fun fromWireName(value: String?): BackgroundDesignVariant? = entries.firstOrNull {
            it.wireName == value
        }
    }
}

internal enum class DebugTypeface(val wireName: String) {
    MANROPE("manrope"),
    SYSTEM("system"),
    ;

    companion object {
        fun fromWireName(value: String?): DebugTypeface? = entries.firstOrNull {
            it.wireName == value
        }
    }
}

internal enum class RatingTintSource(val wireName: String) {
    PRESENTATION("presentation"),
    ALBUM("album"),
    ;

    companion object {
        fun fromWireName(value: String?): RatingTintSource? = entries.firstOrNull {
            it.wireName == value
        }
    }
}

internal enum class MiniProgressPlacement(val wireName: String) {
    BOTTOM("bottom"),
    TOP("top"),
    ;

    companion object {
        fun fromWireName(value: String?): MiniProgressPlacement? = entries.firstOrNull {
            it.wireName == value
        }
    }
}

internal data class BackgroundDesignTuning(
    val enabled: Boolean = false,
    val variant: BackgroundDesignVariant = BackgroundDesignVariant.OLED_GLOW,
    val accentOpacity: Float = 0.42f,
    val glowOpacity: Float = 0.30f,
    val vignetteOpacity: Float = 0.62f,
    val blackFalloffOpacity: Float = 0.88f,
    val primaryTextLuminance: Float = 0.96f,
    val secondaryTextLuminance: Float = 0.73f,
    val typeface: DebugTypeface = DebugTypeface.MANROPE,
    val homeHeadingSizeSp: Float = 27f,
    val headingWeight: Int = 700,
    val cardTitleSizeSp: Float = 16f,
    val ratingSizeDp: Float = 30f,
    val ratingRadiusDp: Float = 9f,
    val ratingBlackOpacity: Float = 0f,
    val ratingAccentOpacity: Float = 1f,
    val ratingOutlineOpacity: Float = 0f,
    val ratingTintSource: RatingTintSource = RatingTintSource.PRESENTATION,
    val miniPlayerOpacity: Float = 0.72f,
    val miniPlayerAccentOpacity: Float = 0f,
    val miniPlayerOutlineOpacity: Float = 0f,
    val miniPlayerHaloOpacity: Float = 0f,
    val progressPlacement: MiniProgressPlacement = MiniProgressPlacement.BOTTOM,
    val progressThicknessDp: Float = 4f,
    val progressHorizontalInsetDp: Float = 0f,
    val progressVerticalInsetDp: Float = 0f,
) {
    fun sanitized(): BackgroundDesignTuning = copy(
        accentOpacity = accentOpacity.coerceIn(0f, 0.65f),
        glowOpacity = glowOpacity.coerceIn(0f, 0.65f),
        vignetteOpacity = vignetteOpacity.coerceIn(0f, 0.90f),
        blackFalloffOpacity = blackFalloffOpacity.coerceIn(0f, 1f),
        primaryTextLuminance = primaryTextLuminance.coerceIn(0.72f, 1f),
        secondaryTextLuminance = secondaryTextLuminance.coerceIn(0.42f, 0.86f),
        homeHeadingSizeSp = homeHeadingSizeSp.coerceIn(22f, 30f),
        headingWeight = headingWeight.coerceIn(400, 800),
        cardTitleSizeSp = cardTitleSizeSp.coerceIn(14f, 18f),
        ratingSizeDp = ratingSizeDp.coerceIn(22f, 30f),
        ratingRadiusDp = ratingRadiusDp.coerceIn(6f, 12f),
        ratingBlackOpacity = ratingBlackOpacity.coerceIn(0f, 0.85f),
        ratingAccentOpacity = ratingAccentOpacity.coerceIn(0f, 1f),
        ratingOutlineOpacity = ratingOutlineOpacity.coerceIn(0f, 0.30f),
        miniPlayerOpacity = miniPlayerOpacity.coerceIn(0.55f, 1f),
        miniPlayerAccentOpacity = miniPlayerAccentOpacity.coerceIn(0f, 0.20f),
        miniPlayerOutlineOpacity = miniPlayerOutlineOpacity.coerceIn(0f, 0.25f),
        miniPlayerHaloOpacity = miniPlayerHaloOpacity.coerceIn(0f, 0.18f),
        progressThicknessDp = progressThicknessDp.coerceIn(1.5f, 4f),
        progressHorizontalInsetDp = progressHorizontalInsetDp.coerceIn(0f, 24f),
        progressVerticalInsetDp = progressVerticalInsetDp.coerceIn(0f, 12f),
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
