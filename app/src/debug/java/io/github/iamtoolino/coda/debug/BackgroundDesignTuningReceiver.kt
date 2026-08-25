package io.github.iamtoolino.coda.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.iamtoolino.coda.ui.theme.BackgroundDesignTuning
import io.github.iamtoolino.coda.ui.theme.BackgroundDesignTuningStore
import io.github.iamtoolino.coda.ui.theme.BackgroundDesignVariant
import io.github.iamtoolino.coda.ui.theme.DebugTypeface
import io.github.iamtoolino.coda.ui.theme.MiniProgressPlacement
import io.github.iamtoolino.coda.ui.theme.RatingTintSource

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
                accentOpacity = intent.getFloatExtra(EXTRA_ACCENT_OPACITY, 0.42f),
                glowOpacity = intent.getFloatExtra(EXTRA_GLOW_OPACITY, 0.30f),
                vignetteOpacity = intent.getFloatExtra(EXTRA_VIGNETTE_OPACITY, 0.62f),
                blackFalloffOpacity = intent.getFloatExtra(EXTRA_BLACK_FALLOFF_OPACITY, 0.88f),
                primaryTextLuminance = intent.getFloatExtra(EXTRA_PRIMARY_TEXT, 0.96f),
                secondaryTextLuminance = intent.getFloatExtra(EXTRA_SECONDARY_TEXT, 0.73f),
                typeface = DebugTypeface.fromWireName(intent.getStringExtra(EXTRA_TYPEFACE))
                    ?: DebugTypeface.MANROPE,
                homeHeadingSizeSp = intent.getFloatExtra(EXTRA_HEADING_SIZE, 27f),
                headingWeight = intent.getIntExtra(EXTRA_HEADING_WEIGHT, 700),
                cardTitleSizeSp = intent.getFloatExtra(EXTRA_CARD_TITLE_SIZE, 16f),
                ratingSizeDp = intent.getFloatExtra(EXTRA_RATING_SIZE, 30f),
                ratingRadiusDp = intent.getFloatExtra(EXTRA_RATING_RADIUS, 9f),
                ratingBlackOpacity = intent.getFloatExtra(EXTRA_RATING_BLACK, 0f),
                ratingAccentOpacity = intent.getFloatExtra(EXTRA_RATING_ACCENT, 1f),
                ratingOutlineOpacity = intent.getFloatExtra(EXTRA_RATING_OUTLINE, 0f),
                ratingTintSource = RatingTintSource.fromWireName(
                    intent.getStringExtra(EXTRA_RATING_TINT_SOURCE),
                ) ?: RatingTintSource.PRESENTATION,
                miniPlayerOpacity = intent.getFloatExtra(EXTRA_MINI_OPACITY, 0.72f),
                miniPlayerAccentOpacity = intent.getFloatExtra(EXTRA_MINI_ACCENT, 0f),
                miniPlayerOutlineOpacity = intent.getFloatExtra(EXTRA_MINI_OUTLINE, 0f),
                miniPlayerHaloOpacity = intent.getFloatExtra(EXTRA_MINI_HALO, 0f),
                progressPlacement = MiniProgressPlacement.fromWireName(
                    intent.getStringExtra(EXTRA_PROGRESS_PLACEMENT),
                ) ?: MiniProgressPlacement.BOTTOM,
                progressThicknessDp = intent.getFloatExtra(EXTRA_PROGRESS_THICKNESS, 4f),
                progressHorizontalInsetDp = intent.getFloatExtra(EXTRA_PROGRESS_HORIZONTAL_INSET, 0f),
                progressVerticalInsetDp = intent.getFloatExtra(EXTRA_PROGRESS_VERTICAL_INSET, 0f),
            ),
        )
    }

    companion object {
        const val ACTION = "io.github.iamtoolino.coda.debug.BACKGROUND_DESIGN_TUNING"
        const val EXTRA_VARIANT = "variant"
        const val EXTRA_ACCENT_OPACITY = "accent_opacity"
        const val EXTRA_GLOW_OPACITY = "glow_opacity"
        const val EXTRA_VIGNETTE_OPACITY = "vignette_opacity"
        const val EXTRA_BLACK_FALLOFF_OPACITY = "black_falloff_opacity"
        const val EXTRA_PRIMARY_TEXT = "primary_text"
        const val EXTRA_SECONDARY_TEXT = "secondary_text"
        const val EXTRA_TYPEFACE = "typeface"
        const val EXTRA_HEADING_SIZE = "heading_size"
        const val EXTRA_HEADING_WEIGHT = "heading_weight"
        const val EXTRA_CARD_TITLE_SIZE = "card_title_size"
        const val EXTRA_RATING_SIZE = "rating_size"
        const val EXTRA_RATING_RADIUS = "rating_radius"
        const val EXTRA_RATING_BLACK = "rating_black"
        const val EXTRA_RATING_ACCENT = "rating_accent"
        const val EXTRA_RATING_OUTLINE = "rating_outline"
        const val EXTRA_RATING_TINT_SOURCE = "rating_tint_source"
        const val EXTRA_MINI_OPACITY = "mini_opacity"
        const val EXTRA_MINI_ACCENT = "mini_accent"
        const val EXTRA_MINI_OUTLINE = "mini_outline"
        const val EXTRA_MINI_HALO = "mini_halo"
        const val EXTRA_PROGRESS_PLACEMENT = "progress_placement"
        const val EXTRA_PROGRESS_THICKNESS = "progress_thickness"
        const val EXTRA_PROGRESS_HORIZONTAL_INSET = "progress_horizontal_inset"
        const val EXTRA_PROGRESS_VERTICAL_INSET = "progress_vertical_inset"
    }
}
