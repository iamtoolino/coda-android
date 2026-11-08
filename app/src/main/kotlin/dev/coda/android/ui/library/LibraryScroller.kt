package dev.coda.android.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.coda.android.ui.theme.AccentState
import dev.coda.core.library.LibrarySection
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val ThumbHit = 48.dp        // touch target, end-aligned; the visible thumb is 20 × 48 dp, 4 dp from the edge
private val ThumbHeight = 48.dp
private const val HideAfterMs = 1500L

/** Spec §0.4: haptic tick per section, with the fallback for devices without segment ticks. */
@Composable
internal fun rememberTick(): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) { { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) } }
}

/**
 * What the fast scroller drives: a lazy list or grid. [stop] ends a fling, [jump] puts an item at the top of the content
 * area (instant, so the list follows the finger) and [end] scrolls to the true end (the layout clamps the last item).
 */
internal class ScrollTarget(private val state: ScrollableState, val geometry: () -> ScrollGeometry, val atStart: () -> Boolean,
    val atEnd: () -> Boolean, private val total: () -> Int, private val scrollTo: suspend (Int) -> Unit) {
    val scrolling: Boolean get() = state.isScrollInProgress
    suspend fun stop() = state.stopScroll()
    suspend fun jump(item: Int) = scrollTo(item)
    suspend fun end() = scrollTo((total() - 1).coerceAtLeast(0))
}

internal fun LazyListState.scrollTarget() = ScrollTarget(this, ::geometry, { !canScrollBackward }, { !canScrollForward },
    { layoutInfo.totalItemsCount }) { scrollToItem(it) }

internal fun LazyGridState.scrollTarget() = ScrollTarget(this, ::geometry, { !canScrollBackward }, { !canScrollForward },
    { layoutInfo.totalItemsCount }) { scrollToItem(it) }

/** One thumb drag: the layout estimate is taken once, after the fling stopped, so the mapping cannot shift mid-gesture. */
private class Grab { var geometry: ScrollGeometry? = null; var start = 0f }

/**
 * Fast scroller, the one end-edge scroller of every long library list (replaces the letter rail): a 20 × 48 dp
 * thumb at the scroll position, hidden at rest, faded in while the list scrolls and out [HideAfterMs] after it stops.
 * Grabbing the thumb stops a fling; dragging it scrolls [target] proportionally (100 % is the true end) and shows a bubble
 * naming the section the list landed in ([landed]: the pinned section, -1 above the first one), so the bubble matches the
 * pinned label after release; at the end of the list (it cannot scroll further) the bubble names the last section
 *. No tick letters on the track. Hidden from TalkBack: the section headers are headings
 * instead. The track runs from [top] (px from the screen top, read during layout) to [bottom], held while a finger is
 * down. The thumb's touch area is excluded from the system back gesture.
 */
@Composable
internal fun FastScroller(sections: List<LibrarySection>, target: ScrollTarget, landed: () -> Int, header: EnterAlwaysHeader,
    top: Density.() -> Float, bottom: Dp, accent: AccentState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val tick = rememberTick()
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val moving by remember(target) { derivedStateOf { target.scrolling } }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(dragging || moving) { if (dragging || moving) shown = true else { delay(HideAfterMs); shown = false } }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 150 else 200), label = "thumbAlpha")
    var held by remember { mutableStateOf<Float?>(null) }
    val latestTop by rememberUpdatedState(top)
    val bottomPx = with(density) { bottom.toPx() }
    val thumbPx = with(density) { ThumbHeight.toPx() }
    fun Density.topPx() = held ?: latestTop()
    // The gesture handler is keyed on [target] only, so it must read the track bottom through updated state, and
    // snapshot it once at touch-down (like the held top) so the mapping cannot change mid-drag.
    val latestBottomPx by rememberUpdatedState(bottomPx)
    fun Density.travel(bottom: Float = bottomPx) = (bottom - topPx() - thumbPx).coerceAtLeast(0f)
    fun fraction() = if (dragging) dragFraction else target.geometry().fraction(target.atStart(), target.atEnd())
    val latestLanded by rememberUpdatedState(landed)
    val latestSections by rememberUpdatedState(sections)
    val section by remember(target) { derivedStateOf {
        if (!dragging) -1 else if (target.atEnd() && !target.atStart()) latestSections.lastIndex else latestLanded().coerceAtLeast(0)
    } }
    LaunchedEffect(section) { if (section >= 0) tick() }
    // The label of the section under the thumb; the last one stays while the bubble fades out.
    val current = sections.getOrNull(section)?.let { s -> s.stars?.let { "★".repeat(it) } ?: s.label }
    var last by remember { mutableStateOf("") }
    if (current != null) SideEffect { last = current }
    val label = current ?: last
    fun seek(fraction: Float, geometry: ScrollGeometry) = scope.launch {
        header.hide()
        if (fraction >= 1f) target.end() else target.jump(geometry.itemAt(fraction))
        if (target.atStart()) header.show()
    }
    Box(modifier.fillMaxSize()) {
        // The track carries no input (touches pass to the list): tests read the section under the thumb from it.
        Box(Modifier.align(Alignment.TopEnd).offset { IntOffset(0, topPx().roundToInt()) }
            .layout { measurable, _ ->
                val placeable = measurable.measure(Constraints.fixed(ThumbHit.roundToPx(), (bottomPx - topPx()).coerceAtLeast(thumbPx).roundToInt()))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .clearAndSetSemantics { testTag = "thumbScroller"; hideFromAccessibility(); stateDescription = if (dragging) label else "" })
        // Only a visible thumb takes touches, so a hidden one never steals a tap on a row's end.
        if (shown || alpha > 0f) Box(Modifier.align(Alignment.TopEnd)
            .offset { IntOffset(0, (topPx() + fraction() * travel()).roundToInt()) }
            .size(ThumbHit, ThumbHeight).graphicsLayer { this.alpha = alpha }
            .systemGestureExclusion()
            .pointerInput(target) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    held = latestTop(); val gestureBottom = latestBottomPx; dragFraction = fraction(); dragging = true
                    // Stop a fling first, then read the settled position once for the whole gesture.
                    val grab = Grab()
                    scope.launch {
                        target.stop()
                        target.geometry().let { grab.start = it.fraction(target.atStart(), target.atEnd()); grab.geometry = it }
                        dragFraction = grab.start
                    }
                    var moved = 0f
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            // Deltas, not positions: the thumb moves under the finger.
                            moved += change.positionChange().y
                            change.consume()
                            val geometry = grab.geometry ?: continue
                            val travel = travel(gestureBottom)
                            dragFraction = if (travel > 0f) (grab.start + moved / travel).coerceIn(0f, 1f) else 0f
                            seek(dragFraction, geometry)
                        }
                    } finally { dragging = false; held = null }
                }
            }
            .clearAndSetSemantics { testTag = "scrollThumb"; hideFromAccessibility() }) {
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp).size(20.dp, ThumbHeight)
                .background(if (dragging) accent.text else accent.container, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.UnfoldMore, null, Modifier.size(16.dp), tint = if (dragging) Color(0xFF0B0C0E) else accent.text)
            }
        }
        // The bubble, centred on the thumb, its end 84 dp from the edge: a teardrop for a letter, a pill for longer labels.
        AnimatedVisibility(dragging && label.isNotEmpty(), Modifier.align(Alignment.TopEnd)
            .offset { IntOffset(-84.dp.roundToPx(), (topPx() + fraction() * travel() + (ThumbHeight / 2 - 32.dp).toPx()).roundToInt()) },
            enter = fadeIn(tween(100)) + scaleIn(tween(100), initialScale = .8f), exit = fadeOut(tween(150))) {
            Box(Modifier.height(64.dp).testTag("thumbBubble"), contentAlignment = Alignment.CenterEnd) {
                if (label.length == 1) LetterBubble(label, accent) else LabelBubble(label, accent)
            }
        }
    }
}

/** Spec §0.4 Bubble: a 64 dp teardrop whose point faces the thumb (the end edge). */
@Composable
private fun LetterBubble(letter: String, accent: AccentState) {
    val shape = RoundedCornerShape(32.dp, 32.dp, 8.dp, 32.dp)
    // The pointed bottom-end corner turns towards the end edge: counter-clockwise in LTR, clockwise in RTL.
    val turn = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 45f else -45f
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().rotate(turn)
            .shadow(24.dp, shape, ambientColor = Color.Black.copy(alpha = .5f), spotColor = Color.Black.copy(alpha = .5f))
            .background(accent.container, shape).border(1.dp, Color.White.copy(alpha = .08f), shape))
        Text(letter, fontSize = 30.sp, fontWeight = FontWeight(800), color = accent.text)
    }
}

/** A 40 dp pill for months, years, play-count tiers and stars. */
@Composable
private fun LabelBubble(label: String, accent: AccentState) {
    Box(Modifier.height(40.dp).shadow(24.dp, RoundedCornerShape(20.dp), ambientColor = Color.Black.copy(alpha = .5f), spotColor = Color.Black.copy(alpha = .5f))
        .background(accent.container, RoundedCornerShape(20.dp)).border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(20.dp))
        .padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight(750), color = accent.text, maxLines = 1)
    }
}

/**
 * The lazy layout seen by the scroller: visible items in container coordinates, the item count, the viewport height and
 * the content paddings. Positions are estimated from the visible items' average height per item (a grid row counts once
 * per tile), which is enough for a proportional thumb on lists of uniform rows.
 */
internal class ScrollGeometry(val items: List<VisibleItem>, val total: Int, val viewport: Int, val before: Int, val after: Int) {
    private val perItem: Float = items.takeIf { it.isNotEmpty() }?.let { visible ->
        val span = visible.maxOf { it.top + it.size } - visible.minOf { it.top }
        span.toFloat() / (visible.maxOf { it.index } - visible.minOf { it.index } + 1)
    }?.coerceAtLeast(1f) ?: 1f
    private val maxScroll: Float get() = before + total * perItem + after - viewport

    /** Scroll position as a fraction 0..1 of the scrollable extent; exact at both ends. */
    fun fraction(atStart: Boolean, atEnd: Boolean): Float {
        val first = items.minByOrNull { it.index } ?: return 0f
        if (atStart || total <= 0 || maxScroll <= 0f) return 0f
        if (atEnd) return 1f
        return ((before + first.index * perItem - first.top) / maxScroll).coerceIn(0f, 1f)
    }

    /** The item to scroll to (top of the content area) for [fraction]: the inverse of [fraction]. */
    fun itemAt(fraction: Float): Int =
        if (items.isEmpty() || total <= 0) 0 else (fraction.coerceIn(0f, 1f) * maxScroll.coerceAtLeast(0f) / perItem).toInt().coerceIn(0, total - 1)
}

internal fun LazyListState.geometry() = layoutInfo.let {
    ScrollGeometry(visibleItems(), it.totalItemsCount, it.viewportSize.height, it.beforeContentPadding, it.afterContentPadding)
}

internal fun LazyGridState.geometry() = layoutInfo.let {
    ScrollGeometry(visibleItems(), it.totalItemsCount, it.viewportSize.height, it.beforeContentPadding, it.afterContentPadding)
}

/** Section index containing data item [index]; -1 without sections. */
internal fun sectionIndexAt(sections: List<LibrarySection>, index: Int): Int {
    if (sections.isEmpty()) return -1
    var low = 0; var high = sections.lastIndex
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (sections[mid].start <= index) low = mid else high = mid - 1
    }
    return low
}
