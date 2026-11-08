package dev.coda.android.ui.theme

import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.coda.android.artwork.CoverArt
import dev.coda.android.artwork.CoverArtFetcher
import dev.coda.core.accent.OneVoice

/** A static low-resolution cover plus one corner light; no full-window colour wash. */
@Composable
internal fun HomeAtmosphere(fetcher: CoverArtFetcher, coverId: String?, accent: AccentState, alpha: Float) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Base).graphicsLayer { this.alpha = alpha }) {
        val height = maxHeight
        Crossfade(coverId, animationSpec = tween(450), label = "Home artwork") { id ->
            val bitmap by produceState<android.graphics.Bitmap?>(null, fetcher, id) {
                value = if (id == null) null else try { fetcher.bitmap(CoverArt(id, 64)) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            }
            bitmap?.let { image ->
                Image(image.asImageBitmap(), null, Modifier.requiredWidth(maxWidth * 1.3f).height(height * .62f)
                    .offset(x = -maxWidth * .15f, y = -height * .12f).graphicsLayer {
                        this.alpha = OneVoice.HomeArtwork
                        renderEffect = RenderEffect.createBlurEffect(44.dp.toPx(), 44.dp.toPx(), Shader.TileMode.CLAMP).asComposeRenderEffect()
                    }.drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(0f to Color.Transparent, .45f to Base.copy(alpha = .4f), 1f to Base))
                    }, contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(.6f) }))
            }
        }
        CornerGlow(accent.accent.copy(alpha = accent.homeGlow))
    }
}

/** Home's one corner light (also the sign-in screen's): [glow] at the top left, gone by a third of the height. */
@Composable
internal fun CornerGlow(glow: Color, modifier: Modifier = Modifier.fillMaxSize()) {
    androidx.compose.foundation.Canvas(modifier) {
        val center = Offset(size.width * .18f, 0f)
        withTransform({ scale(1f, size.height * .34f / (size.width * .85f), center) }) {
            drawRect(Brush.radialGradient(listOf(glow, Color.Transparent), center, size.width * .85f))
        }
        drawRect(Brush.verticalGradient(0f to Color.Transparent, .4f to Base.copy(alpha = .35f), .78f to Base))
    }
}
