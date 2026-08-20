@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package io.github.iamtoolino.coda.ui.theme

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.toBitmap
import io.github.iamtoolino.coda.AppGraph
import io.github.iamtoolino.coda.R
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private val Manrope = FontFamily(
    manropeFont(FontWeight.Normal),
    manropeFont(FontWeight.Medium),
    manropeFont(FontWeight.SemiBold),
    manropeFont(FontWeight.Bold),
)

private fun manropeFont(weight: FontWeight) = Font(
    resId = R.font.manrope_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

private fun TextStyle.manrope() = copy(fontFamily = Manrope)

private val CodaTypography = Typography().let { typography ->
    typography.copy(
        displayLarge = typography.displayLarge.manrope(),
        displayMedium = typography.displayMedium.manrope(),
        displaySmall = typography.displaySmall.manrope(),
        headlineLarge = typography.headlineLarge.manrope(),
        headlineMedium = typography.headlineMedium.manrope(),
        headlineSmall = typography.headlineSmall.manrope(),
        titleLarge = typography.titleLarge.manrope(),
        titleMedium = typography.titleMedium.manrope(),
        titleSmall = typography.titleSmall.manrope(),
        bodyLarge = typography.bodyLarge.manrope(),
        bodyMedium = typography.bodyMedium.manrope(),
        bodySmall = typography.bodySmall.manrope(),
        labelLarge = typography.labelLarge.manrope(),
        labelMedium = typography.labelMedium.manrope(),
        labelSmall = typography.labelSmall.manrope(),
    )
}

private data class ArtworkColors(
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val surfaceVariant: Color,
)

private val BrandColors = artworkColors(CodaAccentExtractor.GENERIC_FALLBACK)

private object ArtworkColorCache {
    private const val maxEntries = 80
    private val values = object : LinkedHashMap<String, ArtworkColors>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ArtworkColors>,
        ): Boolean = size > maxEntries
    }

    @Synchronized
    operator fun get(key: String): ArtworkColors? = values[key]

    @Synchronized
    operator fun set(key: String, colors: ArtworkColors) {
        values[key] = colors
    }
}

@Composable
fun CodaTheme(content: @Composable () -> Unit) {
    CodaMaterialTheme(BrandColors, content)
}

@Composable
internal fun RoutedCodaTheme(
    request: CodaThemeRequest,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val commitGate = remember { ThemeCommitGate() }
    var committedColors by remember { mutableStateOf(BrandColors) }

    LaunchedEffect(request) {
        val token = commitGate.begin()
        when (request) {
            CodaThemeRequest.Pending -> Unit
            CodaThemeRequest.InheritPlayback -> Unit
            CodaThemeRequest.Brand -> committedColors = BrandColors
            is CodaThemeRequest.Artwork -> {
                val cacheKey =
                    "${AppGraph.cacheNamespace}:accent-v${CodaAccentExtractor.ALGORITHM_VERSION}:${request.identity}"
                val cached = ArtworkColorCache[cacheKey]
                if (cached != null) {
                    if (commitGate.isCurrent(token)) committedColors = cached
                    return@LaunchedEffect
                }
                val colors = try {
                    val imageRequest = ImageRequest.Builder(context)
                        .data(request.artworkUrl)
                        .size(32, 32)
                        .allowHardware(false)
                        .bitmapConfig(Bitmap.Config.ARGB_8888)
                        .memoryCacheKey("accent-software:$cacheKey")
                        .diskCacheKey("accent:$cacheKey")
                        .build()
                    val result = context.imageLoader.execute(imageRequest) as? SuccessResult
                    currentCoroutineContext().ensureActive()
                    if (result == null) {
                        BrandColors
                    } else {
                        withContext(Dispatchers.Default) {
                            val bitmap = result.image.toBitmap(32, 32, Bitmap.Config.ARGB_8888)
                            artworkColors(CodaAccentExtractor.extractOrFallback(bitmap))
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    BrandColors
                }
                currentCoroutineContext().ensureActive()
                if (!commitGate.isCurrent(token)) return@LaunchedEffect
                if (colors !== BrandColors) ArtworkColorCache[cacheKey] = colors
                committedColors = colors
            }
        }
    }
    CodaMaterialTheme(committedColors, content)
}

@Composable
private fun CodaMaterialTheme(colors: ArtworkColors, content: @Composable () -> Unit) {
    val accent by animateColorAsState(colors.accent, label = "artwork accent")
    val onAccent by animateColorAsState(colors.onAccent, label = "on artwork accent")
    val accentContainer by animateColorAsState(colors.accentContainer, label = "artwork container")
    val onAccentContainer by animateColorAsState(
        colors.onAccentContainer,
        label = "on artwork container",
    )
    val backgroundTop by animateColorAsState(colors.backgroundTop, label = "artwork background")
    val backgroundBottom by animateColorAsState(colors.backgroundBottom, label = "artwork surface")
    val surfaceVariant by animateColorAsState(colors.surfaceVariant, label = "artwork surface variant")
    val colorScheme = darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentContainer,
        onPrimaryContainer = onAccentContainer,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = accentContainer,
        onSecondaryContainer = onAccentContainer,
        tertiary = accent,
        onTertiary = onAccent,
        tertiaryContainer = accentContainer,
        onTertiaryContainer = onAccentContainer,
        background = backgroundTop,
        onBackground = Color(0xFFF5F2F0),
        surface = backgroundBottom,
        surfaceVariant = surfaceVariant,
        onSurface = Color(0xFFF5F2F0),
        onSurfaceVariant = Color(0xFFBBB7B5),
        outline = Color(0xFF777270),
        outlineVariant = Color(0xFF45413F),
        surfaceTint = accent,
        inversePrimary = accent,
    )
    MaterialTheme(
        colorScheme = colorScheme,
        typography = CodaTypography,
        content = content,
    )
}

@Composable
fun AdaptiveBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.background,
                    0.60f to MaterialTheme.colorScheme.background,
                    1f to MaterialTheme.colorScheme.surface,
                ),
            ),
    ) {
        content()
    }
}

private fun artworkColors(extracted: CodaAccentColor): ArtworkColors {
    val accent = Color(
        red = extracted.red.toFloat(),
        green = extracted.green.toFloat(),
        blue = extracted.blue.toFloat(),
    )
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(accent.toArgb(), hsv)
    val hue = hsv[0]
    val seedSaturation = hsv[1]
    val isPurpleOrMagenta = hue in 255f..335f
    val saturationCap = if (isPurpleOrMagenta) 0.48f else 0.72f
    val containerSaturationSeed = seedSaturation.coerceIn(0.18f, saturationCap)
    val backgroundTop = Color(
        AndroidColor.HSVToColor(
            floatArrayOf(hue, (seedSaturation * 0.30f).coerceIn(0.08f, 0.24f), 0.13f),
        ),
    )
    val surfaceVariant = Color(
        AndroidColor.HSVToColor(
            floatArrayOf(hue, (seedSaturation * 0.32f).coerceIn(0.10f, 0.26f), 0.22f),
        ),
    )
    val accentContainer = Color(
        AndroidColor.HSVToColor(
            floatArrayOf(
                hue,
                (containerSaturationSeed * 0.72f).coerceIn(0.24f, 0.50f),
                0.30f,
            ),
        ),
    )
    return ArtworkColors(
        accent = accent,
        onAccent = if (relativeLuminance(accent) > 0.42f) Color(0xFF17130D) else Color.White,
        accentContainer = accentContainer,
        onAccentContainer = Color(0xFFF4EFE7),
        backgroundTop = backgroundTop,
        backgroundBottom = Color(0xFF09090B),
        surfaceVariant = surfaceVariant,
    )
}

private fun relativeLuminance(color: Color): Float {
    fun linear(channel: Float): Float = if (channel <= 0.04045f) {
        channel / 12.92f
    } else {
        Math.pow(((channel + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }
    return 0.2126f * linear(color.red) +
        0.7152f * linear(color.green) +
        0.0722f * linear(color.blue)
}
