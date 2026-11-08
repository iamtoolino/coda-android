package dev.coda.android.ui.theme

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spec §3.1 `base`: window background and the page-dye end colour. */
val Base = Color(0xFF090B0D)

/** The page-dye middle stop sits at 520 dp of the 808 dp reference window. */
internal const val DyeMiddleStop = 520f / 808f

/** Spec §3.2 page dye colour at [y] of a backdrop [height] tall; the exact colour [drawPageBackdrop] paints there. */
fun AccentState.dyeAt(y: Float, height: Float): Color {
    val t = if (height <= 0f) 0f else (y / height).coerceIn(0f, 1f)
    return if (t <= DyeMiddleStop) srgbLerp(page, pageMiddle, t / DyeMiddleStop)
    else srgbLerp(pageMiddle, Base, (t - DyeMiddleStop) / (1f - DyeMiddleStop))
}

/**
 * Quiet page dye, ending by 640 dp. Only Now Playing adds a 14% glow. Drawn in the draw phase so the 450 ms accent animation never recomposes.
 */
fun DrawScope.drawPageBackdrop(accent: AccentState, dyeAlpha: Float = 1f, glowAlpha: Float = dyeAlpha) {
    if (dyeAlpha <= 0f && glowAlpha <= 0f) return
    clipRect {
        val end = if (accent.playerBackdrop) size.height else dev.coda.core.accent.OneVoice.PageFadeDp.dp.toPx()
        drawRect(Brush.verticalGradient(0f to accent.page, DyeMiddleStop to accent.pageMiddle, 1f to Base, endY = end), alpha = dyeAlpha)
        if (!accent.playerBackdrop) return@clipRect
        // Glow: accent 30 % at (0.2 w, 0), radius 0.9 w × 0.5 h, gone by 70 %.
        withTransform({ scale(1f, size.height * .5f / (size.width * .9f), Offset(size.width * .2f, 0f)) }) {
            drawRect(Brush.radialGradient(0f to accent.accent.copy(alpha = dev.coda.core.accent.OneVoice.PlayerGlow), .7f to Color.Transparent,
                center = Offset(size.width * .2f, 0f), radius = size.width * .9f), alpha = glowAlpha)
        }
    }
}

/** [drawPageBackdrop] behind this element; [alpha] is read in the draw phase (Home fades it in once it has an accent). */
fun Modifier.pageBackdrop(accent: AccentState, alpha: () -> Float = { 1f }): Modifier = drawBehind {
    drawRect(Base)
    drawPageBackdrop(accent, alpha())
}

/**
 * Smoothstep alpha stops for the sleeve's bottom fade. A linear ramp ends in a visible crease (a Mach band) where it
 * meets the flat page; easing both ends makes the cover dissolve into the backdrop instead.
 */
private val fadeStops = (0..8).map { it / 8f }.map { t -> t to t * t * (3 - 2 * t) }

/**
 * Bottom fade of a full-bleed sleeve (Album, Now Playing) into the page backdrop. [edge] returns the sleeve's bottom
 * edge and [height] the backdrop height, both in px of the backdrop; the fade ends on exactly [dyeAt] that edge, so the
 * cover meets the page without a seam, also while the album list scrolls (both are read during drawing).
 */
@Composable
fun BoxScope.SleeveFade(accent: AccentState, edge: Density.() -> Float, height: Density.() -> Float, fade: Dp = 72.dp) {
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(fade).drawBehind {
        val target = accent.dyeAt(edge(), if (accent.playerBackdrop) height() else dev.coda.core.accent.OneVoice.PageFadeDp.dp.toPx())
        drawRect(Brush.verticalGradient(*fadeStops.map { (t, a) -> t to target.copy(alpha = a) }.toTypedArray()))
    })
}
