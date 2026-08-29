package io.github.iamtoolino.coda.ui.theme

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.toBitmap
import io.github.iamtoolino.coda.AppGraph
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private val CodaTypography = Typography()

private data class ArtworkColors(
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val surfaceVariant: Color,
)

internal val LocalArtworkFieldBackgroundActive = staticCompositionLocalOf { false }

private val BrandColors = artworkColors(CodaAccentExtractor.GENERIC_FALLBACK)
private const val ThemeTransitionDurationMillis = 850
private val ThemeTransitionEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private const val PrimaryInkLuminance = 0.77f
private const val SecondaryInkLuminance = 0.44f
private const val NeutralInkBrightnessScale = 1.14f
private const val ArtworkBroadGlowOpacity = 0.42f
private const val ArtworkFocusedGlowOpacity = 0.30f
private const val ArtworkVignetteOpacity = 0.62f
private const val ArtworkBlackFalloffOpacity = 0.88f

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

    @Synchronized
    fun clear() = values.clear()
}

internal fun clearArtworkColorCache() = ArtworkColorCache.clear()

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
            CodaThemeRequest.Brand -> {
                committedColors = BrandColors
            }
            is CodaThemeRequest.Artwork -> {
                val cacheKey = "${AppGraph.cacheNamespace}:" +
                    "accent-v${CodaAccentExtractor.ALGORITHM_VERSION}:${request.memoryCacheKey}"
                val cached = ArtworkColorCache[cacheKey]
                if (cached != null) {
                    if (commitGate.isCurrent(token)) {
                        committedColors = cached
                    }
                    return@LaunchedEffect
                }
                val colors = try {
                    val requestBuilder = ImageRequest.Builder(context)
                        .data(request.artworkUrl)
                        .size(32, 32)
                        .allowHardware(false)
                        .bitmapConfig(Bitmap.Config.ARGB_8888)
                        .memoryCacheKey("accent-software:$cacheKey")
                    val imageRequest = if (request.diskCacheKey == null) {
                        requestBuilder.diskCachePolicy(CachePolicy.DISABLED).build()
                    } else {
                        requestBuilder.diskCacheKey(request.diskCacheKey).build()
                    }
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
    val transition = updateTransition(
        targetState = colors,
        label = "artwork theme",
    )
    val accent by transition.animateColor(
        transitionSpec = {
            tween(ThemeTransitionDurationMillis, easing = ThemeTransitionEasing)
        },
        label = "artwork accent",
    ) { it.accent }
    val onAccent by transition.animateColor(
        transitionSpec = {
            tween(ThemeTransitionDurationMillis, easing = ThemeTransitionEasing)
        },
        label = "on artwork accent",
    ) { it.onAccent }
    val accentContainer by transition.animateColor(
        transitionSpec = {
            tween(ThemeTransitionDurationMillis, easing = ThemeTransitionEasing)
        },
        label = "artwork container",
    ) { it.accentContainer }
    val onAccentContainer by transition.animateColor(
        transitionSpec = {
            tween(ThemeTransitionDurationMillis, easing = ThemeTransitionEasing)
        },
        label = "on artwork container",
    ) { it.onAccentContainer }
    val backgroundTop by transition.animateColor(
        transitionSpec = {
            tween(ThemeTransitionDurationMillis, easing = ThemeTransitionEasing)
        },
        label = "artwork background",
    ) { it.backgroundTop }
    val backgroundBottom by transition.animateColor(
        transitionSpec = {
            tween(ThemeTransitionDurationMillis, easing = ThemeTransitionEasing)
        },
        label = "artwork surface",
    ) { it.backgroundBottom }
    val surfaceVariant by transition.animateColor(
        transitionSpec = {
            tween(ThemeTransitionDurationMillis, easing = ThemeTransitionEasing)
        },
        label = "artwork surface variant",
    ) { it.surfaceVariant }
    val primaryText = warmInk(PrimaryInkLuminance * NeutralInkBrightnessScale)
    val secondaryText = warmInk(SecondaryInkLuminance * NeutralInkBrightnessScale)
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
        onBackground = primaryText,
        surface = backgroundBottom,
        surfaceVariant = surfaceVariant,
        onSurface = primaryText,
        onSurfaceVariant = secondaryText,
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

private fun warmInk(luminance: Float): Color = Color(
    red = luminance,
    green = luminance * 0.985f,
    blue = luminance * 0.97f,
)

@Composable
fun AdaptiveBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (LocalArtworkFieldBackgroundActive.current) {
        Box(modifier = modifier.fillMaxSize()) { content() }
        return
    }
    ArtworkFieldBackground(modifier, content)
}

@Composable
private fun ArtworkFieldBackground(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    CompositionLocalProvider(LocalArtworkFieldBackgroundActive provides true) {
        Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val longestSide = maxOf(size.width, size.height)
                        val shortestSide = minOf(size.width, size.height)
                        val broadGlow = Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = ArtworkBroadGlowOpacity),
                                Color.Transparent,
                            ),
                            center = Offset(size.width * 0.34f, size.height * 0.38f),
                            radius = longestSide * 0.72f,
                        )
                        val coreGlow = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to accent.copy(alpha = ArtworkFocusedGlowOpacity),
                                0.36f to accent.copy(alpha = 0.08f),
                                1f to Color.Transparent,
                            ),
                            center = Offset(size.width * 0.34f, size.height * 0.27f),
                            radius = longestSide * 0.48f,
                        )
                        val vignette = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                (
                                    shortestSide * 0.24f / (longestSide * 0.78f)
                                ).coerceIn(0f, 1f) to Color.Transparent,
                                1f to Color.Black.copy(alpha = ArtworkVignetteOpacity),
                            ),
                            center = Offset(size.width * 0.50f, size.height * 0.43f),
                            radius = longestSide * 0.78f,
                        )
                        val blackFalloff = Brush.linearGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.18f),
                                Color(0xFF060809).copy(alpha = 0.48f),
                                Color.Black.copy(alpha = ArtworkBlackFalloffOpacity),
                            ),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height),
                        )
                        onDrawBehind {
                            drawRect(broadGlow)
                            drawRect(coreGlow)
                            drawRect(vignette)
                            drawRect(blackFalloff)
                        }
                    },
            )
            content()
        }
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
