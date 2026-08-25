package io.github.iamtoolino.coda.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.toBitmap
import io.github.iamtoolino.coda.artwork.ArtworkSource
import io.github.iamtoolino.coda.ui.theme.CodaAccentExtractor
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object AlbumRatingTintCache {
    private const val maxEntries = 120
    private val values = object : LinkedHashMap<String, Color>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>): Boolean =
            size > maxEntries
    }

    @Synchronized
    operator fun get(key: String): Color? = values[key]

    @Synchronized
    operator fun set(key: String, color: Color) {
        values[key] = color
    }

    @Synchronized
    fun clear() = values.clear()
}

internal fun clearAlbumRatingTintCache() = AlbumRatingTintCache.clear()

@Composable
internal fun rememberAlbumRatingTint(
    source: ArtworkSource?,
    enabled: Boolean,
    fallback: Color,
): Color {
    val context = LocalContext.current
    val cacheKey = source?.let {
        "rating-accent-v${CodaAccentExtractor.ALGORITHM_VERSION}:${it.memoryCacheKey}"
    }
    var tint by remember(cacheKey, enabled) {
        mutableStateOf(cacheKey?.let(AlbumRatingTintCache::get) ?: fallback)
    }
    LaunchedEffect(cacheKey, enabled, fallback) {
        if (!enabled || source == null || cacheKey == null) {
            tint = fallback
            return@LaunchedEffect
        }
        AlbumRatingTintCache[cacheKey]?.let {
            tint = it
            return@LaunchedEffect
        }
        val builder = ImageRequest.Builder(context)
            .data(source.url)
            .size(32, 32)
            .allowHardware(false)
            .bitmapConfig(Bitmap.Config.ARGB_8888)
            .memoryCacheKey(cacheKey)
        val request = if (source.diskCacheKey == null) {
            builder.diskCachePolicy(CachePolicy.DISABLED).build()
        } else {
            builder.diskCacheKey(source.diskCacheKey).build()
        }
        val result = try {
            context.imageLoader.execute(request) as? SuccessResult
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        val extracted = result?.let {
            withContext(Dispatchers.Default) {
                CodaAccentExtractor.extractOrFallback(
                    it.image.toBitmap(32, 32, Bitmap.Config.ARGB_8888),
                )
            }
        } ?: return@LaunchedEffect
        val color = Color(
            red = extracted.red.toFloat(),
            green = extracted.green.toFloat(),
            blue = extracted.blue.toFloat(),
        )
        AlbumRatingTintCache[cacheKey] = color
        tint = color
    }
    return tint
}
