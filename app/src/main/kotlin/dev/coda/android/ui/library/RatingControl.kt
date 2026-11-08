package dev.coda.android.ui.library

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import dev.coda.android.ui.common.ratingSemantics
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
internal fun currentRating(id: String, initial: Int?): Int =
    LocalLibraryActions.current?.ratings?.collectAsState()?.value?.overrides?.get(id) ?: initial ?: 0

@Composable
internal fun RatingControl(id: String, initial: Int, label: String, tint: Color,
    /** The double-tap's label ("Rate album"): it opens the editor. */
    rateLabel: String = "Rate album",
    starSize: Dp = 18.dp, compact: Boolean = false,
    requested: Boolean = false, onDismiss: () -> Unit = {},
    /** False where the caller presents a failed save elsewhere (the album header has no room: DS-12). */
    showError: Boolean = true,
    /** False where the caller's larger touch area carries the rating's semantics instead (the album header). */
    semantics: Boolean = true) {
    val model = LocalLibraryActions.current ?: return
    val state by model.ratings.collectAsState()
    val rating = state.overrides[id] ?: initial
    var edit by remember(id) { mutableStateOf(false) }
    var anchor by remember(id) { mutableStateOf(Rect.Zero) }
    val haptic = LocalHapticFeedback.current
    fun open() { edit = true; haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    LaunchedEffect(requested) { if (requested) open() }
    Box(Modifier.onGloballyPositioned { anchor = it.boundsInWindow() }) {
        Row(Modifier.heightIn(min = if (compact) 0.dp else 48.dp)
            .graphicsLayer { alpha = if (edit) 0f else 1f }
            // Before the clickable: the rating's semantics replace the clickable's (unlabelled) click.
            .then(if (!semantics) Modifier.clearAndSetSemantics { } else Modifier.ratingSemantics(label, rating, enabled = { !model.ratings.value.busy },
                rate = { model.rate(id, it) }, onClick = { if (!state.busy) open() }, clickLabel = rateLabel))
            .clickable(enabled = !state.busy) { open() }, verticalAlignment = Alignment.CenterVertically) {
            repeat(5) { Icon(if (it < rating) Icons.Default.Star else Icons.Default.StarBorder, null, Modifier.size(starSize), tint = tint) }
        }
    }
    if (showError) RatingError(id)
    if (edit) RatingEditor(anchor, starSize, tint, rating, state.busy,
        choose = { model.rate(id, it) }, dismiss = { edit = false; onDismiss() })
}

@Composable
private fun RatingError(id: String) {
    val model = LocalLibraryActions.current ?: return
    val state by model.ratings.collectAsState()
    if (state.failed && state.failedId == id) Text("Couldn't save rating. Try again.",
        color = MaterialTheme.colorScheme.error)
}

/** A single window owns the scrim and hit targets; the underlying page never relayouts. */
@Composable
private fun RatingEditor(anchor: Rect, compactSize: Dp, tint: Color, rating: Int, busy: Boolean,
    choose: (Int) -> Unit, dismiss: () -> Unit) {
    val reduceMotion = !ValueAnimator.areAnimatorsEnabled()
    val expansion = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(false) }
    var preview by remember { mutableIntStateOf(rating) }
    var activity by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val currentRating by rememberUpdatedState(rating)
    val currentBusy by rememberUpdatedState(busy)
    val currentChoose by rememberUpdatedState(choose)
    val spring = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 500f)
    LaunchedEffect(closing) {
        val target = if (closing) 0f else 1f
        if (reduceMotion) {
            // Only opacity changes in this path, even when the system disables spatial motion.
            withContext(object : MotionDurationScale { override val scaleFactor = 1f }) {
                expansion.animateTo(target, tween(150))
            }
        } else expansion.animateTo(target, spring)
        if (closing) dismiss()
    }
    LaunchedEffect(activity, picked, dragging) {
        if (!dragging) { delay(if (picked) 400 else 3000); closing = true }
    }
    fun preview(value: Int) {
        activity++
        if (preview != value) { preview = value; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
    fun commit(value: Int, toggle: Boolean = true) {
        if (currentBusy || closing || picked) return
        preview = if (toggle && value == currentRating) 0 else value
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        currentChoose(preview)
        picked = true
    }
    Popup(popupPositionProvider = remember { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
    } }, onDismissRequest = { closing = true }, properties = PopupProperties(focusable = true, clippingEnabled = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().testTag("ratingOverlay")) {
            val density = LocalDensity.current
            val width = with(density) { 240.dp.toPx() }
            val height = with(density) { 64.dp.toPx() }
            val left = (anchor.center.x - width / 2).coerceIn(0f, (constraints.maxWidth - width).coerceAtLeast(0f))
            val top = (anchor.center.y - height / 2).coerceIn(0f, (constraints.maxHeight - height).coerceAtLeast(0f))
            val p = expansion.value
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .18f * p.coerceIn(0f, 1f)))
                .testTag("ratingOutside").clickable { closing = true })
            if (reduceMotion) Row(Modifier.offset {
                IntOffset(anchor.left.roundToInt(), (anchor.center.y - with(density) { compactSize.toPx() } / 2).roundToInt())
            }.graphicsLayer { alpha = 1f - p.coerceIn(0f, 1f) }) {
                repeat(5) { Icon(if (it < rating) Icons.Default.Star else Icons.Default.StarBorder, null,
                    Modifier.size(compactSize), tint = tint) }
            }
            Row(Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }.size(240.dp, 64.dp)
                .graphicsLayer { alpha = if (reduceMotion) p else 1f }
                .background(tint.copy(alpha = .16f).compositeOver(MaterialTheme.colorScheme.surface).copy(alpha = .96f * p.coerceIn(0f, 1f)), RoundedCornerShape(32.dp))
                .pointerInput(picked, closing, busy) {
                    if (picked || closing || busy) return@pointerInput
                    detectDragGestures(onDragStart = { offset -> dragging = true; preview((offset.x / (size.width / 5f)).toInt().coerceIn(0, 4) + 1) },
                        onDragCancel = { dragging = false; if (!picked) preview = currentRating },
                        onDragEnd = { dragging = false; commit(preview, toggle = false) },
                        onDrag = { change, _ -> change.consume(); preview((change.position.x / (size.width / 5f)).toInt().coerceIn(0, 4) + 1) })
                }, verticalAlignment = Alignment.CenterVertically) {
                repeat(5) { index ->
                    val pop = remember { Animatable(1f) }
                    val selected = index < if (picked || dragging) preview else rating
                    LaunchedEffect(selected, picked, dragging) {
                        if (selected && (picked || dragging) && !reduceMotion) {
                            delay(index * 25L); pop.animateTo(1.15f, tween(70)); pop.animateTo(1f, spring)
                        }
                    }
                    Box(Modifier.size(48.dp).testTag("ratingStar-${index + 1}")
                        .clickable(enabled = !busy && !closing && !picked) { commit(index + 1) }
                        .semantics { this.selected = selected; contentDescription = "${index + 1} ${if (index == 0) "star" else "stars"}" }, contentAlignment = Alignment.Center) {
                        Icon(if (selected) Icons.Default.Star else Icons.Default.StarBorder, null,
                            Modifier.size(40.dp).graphicsLayer {
                                if (!reduceMotion) {
                                    val compactPx = with(density) { compactSize.toPx() }
                                    translationX = (anchor.left + compactPx * (index + .5f) - (left + with(density) { 48.dp.toPx() } * (index + .5f))) * (1 - p)
                                    translationY = (anchor.center.y - (top + height / 2)) * (1 - p)
                                    scaleX = (compactSize.value / 40 + (1 - compactSize.value / 40) * p) * pop.value
                                    scaleY = scaleX
                                }
                            }, tint = tint)
                    }
                }
            }
        }
    }
}
