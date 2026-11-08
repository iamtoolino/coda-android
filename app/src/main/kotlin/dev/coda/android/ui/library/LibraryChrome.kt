package dev.coda.android.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalDensity
import dev.coda.android.R
import dev.coda.android.ui.theme.AccentState
import dev.coda.android.ui.theme.Base
import kotlin.math.roundToInt

/** Spec §3.1 fixed colours used by the library screens. */
internal val LibText1 = Color(0xFFF1F1EE)
internal val LibText2 = LibText1.copy(alpha = .66f)
internal val LibText3 = LibText1.copy(alpha = .44f)
internal val LibOutline = Color.White.copy(alpha = .09f)
internal val Tabular = TextStyle(fontFeatureSettings = "tnum")

/**
 * Cap height of the font the library texts are drawn in (CodaTheme's bundled Google Sans Flex), as a fraction of the em, from the
 * glyph bounds of "H" (about 0.71). Falls back to 0.71 when the font cannot be loaded or the measurement is implausible.
 */
internal fun capHeightEm(context: android.content.Context): Float {
    val typeface = runCatching { androidx.core.content.res.ResourcesCompat.getFont(context, R.font.google_sans_flex) }.getOrNull() ?: return .71f
    val paint = android.graphics.Paint().apply { textSize = 100f; this.typeface = typeface }
    val bounds = android.graphics.Rect()
    paint.getTextBounds("H", 0, 1, bounds)
    return (-bounds.top / 100f).takeIf { it in .6f..0.8f } ?: .71f
}

/**
 * A count that shares the title's baseline is lifted by
 * (titleSize − countSize) × capHeight / 2, so its cap-height centre meets the title's. It scales with the font size, and only
 * the draw position moves (row height and every other position stay).
 */
internal fun Modifier.liftToTitleCapCentre(title: TextUnit, count: TextUnit): Modifier = composed {
    val density = LocalDensity.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val capEm = remember(context) { capHeightEm(context) }
    val lift = with(density) { (title.toPx() - count.toPx()) * capEm / 2 }
    graphicsLayer { translationY = -lift }
}

/** "Albums", "Artists": appBarTitle (22 / 650) plus the count (13 / 500 text3, 8 dp gap, tabular), on one baseline and then centred on the title's cap height. */
@Composable
internal fun LibraryTitle(title: String, count: String?, modifier: Modifier = Modifier) {
    Row(modifier.semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.Top) {
        Text(title, Modifier.alignByBaseline(), fontSize = 22.sp, fontWeight = FontWeight(650), letterSpacing = (-0.22).sp, color = LibText1, maxLines = 1)
        if (count != null) Text(count, Modifier.alignByBaseline().padding(start = 8.dp).liftToTitleCapCentre(22.sp, 13.sp), fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = LibText3, style = Tabular, maxLines = 1)
    }
}

/** Spec §0.2: label, count and hairline; Top Rated draws [stars] instead of the label. Label and count share a baseline, the count centred on the label's cap height. */
@Composable
internal fun SectionHeader(label: String, count: Int, accent: AccentState, modifier: Modifier = Modifier, stars: Int? = null,
    countText: String = "$count") {
    val description = if (stars != null) pluralStringResource(R.plurals.stars, stars, stars) + ", " +
        pluralStringResource(R.plurals.albums, count, count) else null
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp).padding(top = 10.dp)
        .semantics(mergeDescendants = true) { heading(); if (description != null) contentDescription = description },
        verticalAlignment = Alignment.CenterVertically) {
        if (stars != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(stars) { Icon(Icons.Default.Star, null, Modifier.size(13.dp), tint = accent.text) }
            }
            Text(countText, Modifier.padding(start = 8.dp), fontSize = 12.sp, color = LibText3, style = Tabular, maxLines = 1)
        } else Row {
            Text(label, Modifier.alignByBaseline(), fontSize = 15.sp, fontWeight = FontWeight(750), color = accent.text, style = Tabular, maxLines = 1)
            Text(countText, Modifier.alignByBaseline().padding(start = 8.dp).liftToTitleCapCentre(15.sp, 12.sp), fontSize = 12.sp, color = LibText3, style = Tabular, maxLines = 1)
        }
        Box(Modifier.padding(start = 6.dp).weight(1f).height(1.dp).background(LibOutline))
    }
}

/**
 * The library tabs' backdrop is Home's calm backdrop, drawn once by the shell. [layer] is that drawing, recorded where the shell
 * draws it (the same bounds as the tab); [backdropBand] replays it clipped to an overlay header, so a header that returns over
 * scrolled content is opaque on exactly the pixels the page has, with no seam.
 */
class TabBackdrop(val layer: GraphicsLayer)

internal fun Modifier.backdropBand(backdrop: TabBackdrop?, bottom: Density.() -> Float): Modifier = drawBehind {
    val edge = bottom()
    if (edge <= 0f) return@drawBehind
    clipRect(bottom = edge) {
        drawRect(Base)
        if (backdrop != null) drawLayer(backdrop.layer)
    }
}

/**
 * Pinned section labels never draw over content. The pinned header ([content], at [line] px from the container top,
 * moved up by [push] (given the header's measured height) when the next header arrives) sits on a band of the tab's fixed
 * backdrop from the screen top down to its own bottom (36 dp below the title bar), so rows and covers scrolling under it
 * are clipped and the band reads as part of the header. The band also takes the touches in its bounds: a tap never opens
 * a row or cover it hides, while a drag still scrolls [scroll] (through [connection], so the title bar follows).
 * Must be placed before the title bar's own band and the title bar.
 */
@Composable
internal fun PinnedHeaderBand(backdrop: TabBackdrop?, scroll: ScrollableState, connection: NestedScrollConnection, line: () -> Float,
    push: Density.(extent: Float) -> Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var height by remember { mutableIntStateOf(0) }
    val extent: Density.() -> Float = { if (height > 0) height.toFloat() else 40.dp.toPx() }
    val bottom: Density.() -> Float = { line() + push(extent()) + extent() }
    Box(Modifier.fillMaxSize().backdropBand(backdrop, bottom)) {
        Box(Modifier.fillMaxWidth()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, bottom().roundToInt().coerceAtLeast(0)))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .nestedScroll(connection).scrollable(scroll, Orientation.Vertical, reverseDirection = true))
        Box(Modifier.offset { IntOffset(0, line().roundToInt()) }.graphicsLayer { translationY = push(extent()) }
            .onSizeChanged { height = it.height }.then(modifier).testTag("pinnedSection")) { content() }
    }
}

/**
 * Enter-always app bar (spec §1/§2): moves with the content's actual scroll, so it scrolls away with the list and
 * returns on any upward scroll, and never moves when the list cannot scroll.
 */
@Stable
internal class EnterAlwaysHeader {
    var heightPx by mutableFloatStateOf(0f)
    var offsetPx by mutableFloatStateOf(0f)
    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            offsetPx = (offsetPx + consumed.y).coerceIn(-heightPx, 0f)
            return Offset.Zero
        }
    }
    fun hide() { offsetPx = -heightPx }
    fun show() { offsetPx = 0f }
    /** Draw offset of a bar that sits below a [statusPx] inset: hidden means fully off-screen, inset included. */
    fun drawOffset(statusPx: Float): Float = if (heightPx <= 0f) 0f else offsetPx * (heightPx + statusPx) / heightPx
    /** Bottom edge of the bar in px below the status bar. */
    val bottomPx: Float get() = heightPx + offsetPx
}

/** A visible lazy item in container coordinates (0 = the list's top edge, including the content padding). */
internal class VisibleItem(val index: Int, val top: Int, val size: Int, val key: Any?)

internal fun LazyListState.visibleItems(): List<VisibleItem> = layoutInfo.let { info ->
    info.visibleItemsInfo.map { VisibleItem(it.index, it.offset - info.viewportStartOffset, it.size, it.key) }
}

internal fun LazyGridState.visibleItems(): List<VisibleItem> = layoutInfo.let { info ->
    info.visibleItemsInfo.map { VisibleItem(it.index, it.offset.y - info.viewportStartOffset, it.size.height, it.key) }
}

/**
 * Spec §0.3: the section pinned at [line] (px from the container top). [headers] holds each section header's lazy item
 * index, ascending. -1 until the first header has scrolled under the line.
 */
internal fun pinnedSection(headers: IntArray, items: List<VisibleItem>, line: Float): Int {
    if (headers.isEmpty() || items.isEmpty()) return -1
    val probe = items.firstOrNull { it.top + it.size > line } ?: items.last()
    var low = 0; var high = headers.size - 1; var found = -1
    while (low <= high) {
        val mid = (low + high) / 2
        if (headers[mid] <= probe.index) { found = mid; low = mid + 1 } else high = mid - 1
    }
    // The probe can be the header itself while it is still below the line (the list's top): not pinned yet.
    if (found >= 0 && items.firstOrNull { it.index == headers[found] }?.let { it.top > line } == true) found--
    return found
}

/** Push-up: when the next header is less than [extent] below the line, the pinned one moves up with it. */
internal fun pinnedPush(headers: IntArray, items: List<VisibleItem>, section: Int, line: Float, extent: Float): Float {
    val next = headers.getOrNull(section + 1) ?: return 0f
    val item = items.firstOrNull { it.index == next } ?: return 0f
    return (item.top - line - extent).coerceAtMost(0f)
}
