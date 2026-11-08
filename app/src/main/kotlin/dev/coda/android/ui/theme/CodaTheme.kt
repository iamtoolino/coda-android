package dev.coda.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import dev.coda.core.accent.DisplayedAccent


private val Text1 = Color(0xFFF1F1EE)

/**
 * The fixed, neutral part of the Material scheme. Every accent slot (primary, secondary, tertiary, their
 * containers, surface tint) comes from an [AccentState] through [withAccent], so no surface picks up brand gold or
 * Material's default lavender through a component default.
 */
internal fun codaColorScheme(accent: AccentState): ColorScheme = darkColorScheme(
    background = Base, onBackground = Text1,
    surface = Color(0xFF121417), onSurface = Text1, onSurfaceVariant = Color(0xFFA0A09D),
    surfaceVariant = Color(0xFF1A1C20), surfaceDim = Base, surfaceBright = Color(0xFF22252A),
    surfaceContainerLowest = Base, surfaceContainerLow = Color(0xFF131518), surfaceContainer = Color(0xFF121417),
    surfaceContainerHigh = Color(0xFF1A1C20), surfaceContainerHighest = Color(0xFF22252A),
    inverseSurface = Text1, inverseOnSurface = Base,
    outline = Color.White.copy(alpha = .22f), outlineVariant = Color.White.copy(alpha = .09f), scrim = Color.Black,
    error = Color(0xFFE5675E), onError = Color(0xFF2A0B08), errorContainer = Color(0xFF3A1714), onErrorContainer = Color(0xFFF2B8B3),
).withAccent(accent)

/** All accent slots of the scheme from one accent's roles: the text tone for content, the container for fills. */
internal fun ColorScheme.withAccent(accent: AccentState): ColorScheme {
    val onText = Color.Black.copy(alpha = .82f)
    return copy(
        primary = accent.text, onPrimary = onText, primaryContainer = accent.container, onPrimaryContainer = Text1,
        inversePrimary = accent.accent,
        secondary = accent.text, onSecondary = onText, secondaryContainer = accent.container, onSecondaryContainer = Text1,
        tertiary = accent.text, onTertiary = onText, tertiaryContainer = accent.container, onTertiaryContainer = Text1,
        surfaceTint = accent.surface,
        primaryFixed = accent.text, primaryFixedDim = accent.text, onPrimaryFixed = onText, onPrimaryFixedVariant = onText,
        secondaryFixed = accent.text, secondaryFixedDim = accent.text, onSecondaryFixed = onText, onSecondaryFixedVariant = onText,
        tertiaryFixed = accent.text, tertiaryFixedDim = accent.text, onTertiaryFixed = onText, onTertiaryFixedVariant = onText,
    )
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
fun CodaTheme(content: @Composable () -> Unit) {
    val family = androidx.compose.ui.text.font.FontFamily(
        *listOf(400, 500, 600, 650, 700, 800).map { weight ->
            androidx.compose.ui.text.font.Font(dev.coda.android.R.font.google_sans_flex,
                weight = androidx.compose.ui.text.font.FontWeight(weight),
                variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(
                    androidx.compose.ui.text.font.FontVariation.weight(weight)))
        }.toTypedArray())
    val base = androidx.compose.material3.Typography()
    // Without a session the scheme follows the resolver's brand theme (the fallback teal), not brand gold.
    MaterialTheme(colorScheme = codaColorScheme(AccentState.of(DisplayedAccent.brandSelection)), typography = androidx.compose.material3.Typography(
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family)), content = content)
}

/** Material defaults inside [content] follow [accent], one of the resolver's accents (page or playback). */
@Composable
fun AccentTheme(accent: AccentState, content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.withAccent(accent), typography = MaterialTheme.typography, content = content)

/** [AccentTheme] over a cross-fading accent ([rememberAccentStateOf]): only this theme's content follows the fade. */
@Composable
fun AccentTheme(accent: State<AccentState>, content: @Composable () -> Unit) = AccentTheme(accent.value, content)

/** Sign-in and re-login always wear the fixed cyan [LoginAccent], independent of artwork. */
@Composable
fun LoginTheme(content: @Composable () -> Unit) {
    val accent = LoginAccent
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.withAccent(accent), content = content)
}
