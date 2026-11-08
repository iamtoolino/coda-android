package dev.coda.android.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The dark see-through round button that floats over covers and banners (back, ⋮, collapse, queue).
 *
 * The fill and the IconButton's clipped ripple are the same fixed-size circle. Without the explicit `size`,
 * `IconButton` lays its 40 dp state layer inside the 48 dp minimum touch target, so a background applied from
 * outside showed a 4 dp unhighlighted ring around the pressed ripple.
 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = Color.Black.copy(alpha = .36f),
    size: Dp = 48.dp,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    // The caller's size is the button; the 48 dp touch target is left to Compose's pointer-level minimum touch size.
    // (IconButton's own 48 dp layout enforcement inside a smaller fixed size would offset the ripple from the fill.)
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        IconButton(onClick = onClick, modifier = modifier.size(size).background(fill, CircleShape), enabled = enabled, content = content)
    }
}
