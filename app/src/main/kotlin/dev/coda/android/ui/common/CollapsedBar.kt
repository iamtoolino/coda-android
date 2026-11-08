package dev.coda.android.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.coda.android.ui.theme.AccentState

/** The collapsed page bar (album, playlist): the status bar plus a 56 dp row, at least 80 dp. */
@Composable
fun collapsedBarHeight(): Dp = maxOf(80.dp, WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp)

/** The collapsed bar's opacity: 150 ms linear fade, instant with animations off. */
@Composable
fun collapsedBarAlpha(visible: Boolean, label: String): Float {
    val reducedMotion = !android.animation.ValueAnimator.areAnimatorsEnabled()
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (reducedMotion) 0 else 150, easing = LinearEasing), label = label)
    return alpha
}

/**
 * The collapsed bar's surface, shared by the album and playlist pages. One flat, opaque `accent.page` from the top of
 * the screen (under the transparent status bar, which so takes the bar's colour) to the bar's bottom: no gradient, nothing of
 * the layer behind shows through. Drawn by the page, so leaving the page restores the status bar. Swallows taps.
 */
@Composable
fun CollapsedBarSurface(accent: AccentState, alpha: Float, tag: String, modifier: Modifier = Modifier) {
    if (alpha > 0f) Box(modifier.fillMaxWidth().height(collapsedBarHeight())
        .graphicsLayer { this.alpha = alpha }.background(accent.page)
        .pointerInput(Unit) { detectTapGestures { } }.testTag(tag))
}
