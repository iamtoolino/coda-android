package dev.coda.android.ui.shell

import androidx.compose.animation.core.*
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import dev.coda.android.ui.theme.AccentState
import dev.coda.android.ui.theme.srgbLerp

/** The dock stack's height, plus the offline line's slot while it shows (animated, so readers recompose, not the tree). */
val LocalDockPadding = compositionLocalOf { 0.dp }

private val labels = listOf("Home", "Albums", "Artists", "Playlists", "Search")
private val icons = listOf(Icons.Default.Home, Icons.Default.Album, Icons.Default.Person, Icons.Default.QueueMusic, Icons.Default.Search)
private val dockMotion = tween<Float>(400, easing = CubicBezierEasing(.05f, .7f, .1f, 1f))

/** Directional accumulation resets on reversal: tiny scroll jitter cannot fold the navigation. */
class DockScrollPolicy {
    var collapsed = false
        private set
    private var distance = 0f
    fun expand() { collapsed = false; distance = 0f }
    fun scroll(dy: Float, allowed: Boolean) {
        if (!allowed) { expand(); return }
        if (dy == 0f) return
        if (distance * dy < 0) distance = 0f
        distance += dy
        if (distance <= -24f) { collapsed = true; distance = 0f }
        if (distance >= 8f) expand()
    }
}

@Stable
class DockState {
    private val policy = DockScrollPolicy()
    var collapsed by mutableStateOf(false)
        private set
    var focused by mutableStateOf(false)
    var modifier: Modifier = Modifier
        internal set
    fun expand() { policy.expand(); collapsed = false }
    fun scroll(dy: Float, allowed: Boolean) { policy.scroll(dy, allowed && !focused); collapsed = policy.collapsed }
}

@Composable
fun rememberDockState(tab: Int, hasItem: Boolean): DockState {
    val state = remember { DockState() }
    val density = LocalDensity.current
    val accessible = dev.coda.android.ui.common.rememberAccessibilityOn()
    val allowed = hasItem && !accessible
    LaunchedEffect(tab, allowed) { state.expand() }
    val connection = remember(state, density, allowed) { object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) {
                state.scroll(with(density) { consumed.y.toDp().value }, allowed)
                if (available.y > 0) state.expand() // The list has reached its top.
            }
            return Offset.Zero
        }
    } }
    state.modifier = Modifier.nestedScroll(connection)
    return state
}

/** How far the dock is folded (0 open, 1 folded: the 52 dp navigation row is gone); the offline line follows it down. */
@Composable
fun dockFold(state: DockState, hasItem: Boolean): State<Float> =
    animateFloatAsState(if (hasItem && state.collapsed) 1f else 0f, dockMotion, label = "Dock fold")

/** The height the folded dock gives up: its navigation row. */
val FoldedRowHeight = 52.dp

@Composable
fun expandedStack(hasItem: Boolean) =
    (if (hasItem) 112.dp else 56.dp) + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** The dock surface wears the playing album's accent (One Voice: 0.12, the courtesy guard 0.05); also while paused. */
internal fun dockTint(playback: AccentState, tint: Float = .12f) = srgbLerp(Color(0xFF15171A), playback.accent, tint)

/** The folded dock's tab chip is neutral (white 9 % circle, grey icon) so the progress line stays the only accent. */
internal val FoldedChipFill = Color.White.copy(alpha = .09f)
internal val FoldedChipIcon = Color(0xFFF1F1EE).copy(alpha = .66f)

/** Pressing the chip lifts its neutral face (white +6 %), so the chip stays accent-free like the capsule brightening. */
@Composable
private fun FoldedChipFace(tab: Int, press: DockPress) {
    Surface(shape = RoundedCornerShape(20.dp), color = FoldedChipFill, modifier = Modifier.size(40.dp).testTag("dockChipFace")) {
        Box(Modifier.drawBehind { drawRect(Color.White.copy(alpha = .06f * press.glow.value)) }, contentAlignment = Alignment.Center) {
            Icon(icons[tab], null, tint = FoldedChipIcon)
        }
    }
}

/**
 * The dock's press feedback instead of the M3 ripple, whose unclipped grey rectangle clashed with the capsule.
 * A press glows in 90 ms; release or cancel holds 60 ms and fades out in 150 ms, never before the fade-in finished, so a quick
 * tap still shows. The glow is driven only by press interactions (animator scale 0 snaps it, nothing can stay lit).
 */
@Stable
private class DockPress {
    val source = MutableInteractionSource()
    val glow = Animatable(0f)
    /** The press began on the already active tab: the capsule brightens instead of a disc appearing. */
    var onActive by mutableStateOf(false)
}

@Composable
private fun rememberDockPress(active: () -> Boolean = { false }): DockPress {
    val press = remember { DockPress() }
    val isActive by rememberUpdatedState(active)
    LaunchedEffect(press) {
        var fadeIn: Job? = null
        press.source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> { press.onActive = isActive(); fadeIn = launch { press.glow.animateTo(1f, tween(90)) } }
                is PressInteraction.Release, is PressInteraction.Cancel -> { val own = fadeIn; launch {
                    own?.join()
                    if (fadeIn === own) press.glow.animateTo(0f, tween(150, delayMillis = 60)) // a newer press owns the glow
                } }
            }
        }
    }
    return press
}

/** Keyboard/switch-access focus (TalkBack draws its own box): a 2 dp accent ring fading in 150 ms; touch never focuses. */
@Composable
private fun focusRing(press: DockPress): State<Float> {
    val focused by press.source.collectIsFocusedAsState()
    return animateFloatAsState(if (focused) 1f else 0f, tween(150), label = "Dock focus ring")
}

/** Draws the 40 dp press disc ([disc]) and the focus ring in the capsule's shape: [width] × 40 dp, radius 20, centred. */
private fun Modifier.dockFeedback(press: DockPress, ring: State<Float>, color: Color, disc: Boolean, width: Dp = 40.dp) = drawBehind {
    val h = 40.dp.toPx()
    val glow = press.glow.value
    if (disc && glow > 0f && !press.onActive) drawCircle(color.copy(alpha = .10f * glow), h / 2)
    val r = ring.value
    if (r > 0f) {
        val stroke = 2.dp.toPx()
        val w = width.toPx().coerceAtMost(size.width)
        drawRoundRect(color.copy(alpha = r), Offset((size.width - w + stroke) / 2, (size.height - h + stroke) / 2),
            Size(w - stroke, h - stroke), CornerRadius((h - stroke) / 2), style = Stroke(stroke))
    }
}

@Composable
fun BottomDock(tab: Int, select: (Int) -> Unit, state: DockState, hasItem: Boolean,
    page: AccentState, playback: AccentState, modifier: Modifier = Modifier, dockTint: Float = .12f, mini: @Composable () -> Unit) {
    val fold by dockFold(state, hasItem)
    val queued by animateFloatAsState(if (hasItem) 1f else 0f,
        tween(if (hasItem) 400 else 300, easing = CubicBezierEasing(.05f, .7f, .1f, 1f)), label = "Dock queue")
    val tint by animateFloatAsState(dockTint, tween(450), label = "Dock courtesy")
    val dock = dockTint(playback, tint)
    Column(modifier.fillMaxWidth().drawBehind {
        val height = 240.dp.toPx()
        drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xE008090A)), -height, size.height),
            topLeft = Offset(0f, -height), size = androidx.compose.ui.geometry.Size(size.width, size.height + height))
    }.navigationBarsPadding().padding(bottom = 4.dp)
        .testTag("dockB")) {
        Surface(Modifier.padding(horizontal = 8.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp),
            color = dock, shadowElevation = 10.dp, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .06f))) {
            Column {
                val chipPress = rememberDockPress()
                val chipRing = focusRing(chipPress)
                if (queued > 0f) Row(Modifier.height(56.dp * queued).clip(RoundedCornerShape(24.dp)), verticalAlignment = Alignment.CenterVertically) {
                    if (fold > 0f) Box(Modifier.width(48.dp * fold).height(56.dp).graphicsLayer { alpha = fold }
                        .clickable(chipPress.source, indication = null, onClickLabel = "Show navigation") { state.expand() }
                        .dockFeedback(chipPress, chipRing, FoldedChipIcon, disc = false).testTag("dockChip")
                        .semantics { contentDescription = "${labels[tab]}. Show navigation" }, contentAlignment = Alignment.Center) {
                        FoldedChipFace(tab, chipPress)
                    }
                    Box(Modifier.weight(1f).graphicsLayer { alpha = queued; translationY = (1f - queued) * -10.dp.toPx() }) { mini() }
                }
                Box(Modifier.height(FoldedRowHeight * (1f - fold)).clip(RoundedCornerShape(24.dp)).graphicsLayer {
                    alpha = (1f - fold * 2).coerceAtLeast(0f); translationY = fold * 14.dp.toPx()
                }.then(if (fold > .5f) Modifier.semantics { hideFromAccessibility() } else Modifier)) {
                    NavigationRow(tab, select, playback, state)
                }
            }
        }
    }
}

@Composable
private fun NavigationRow(tab: Int, select: (Int) -> Unit, accent: AccentState, state: DockState) {
    val largeFont = LocalDensity.current.fontScale > 1.3f
    BoxWithConstraints(Modifier.fillMaxWidth().height(52.dp).selectableGroup().onFocusChanged {
        state.focused = it.hasFocus; if (it.hasFocus) state.expand()
    }) {
        val inactive = if (maxWidth < 328.dp) 48.dp else 52.dp
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val labelWidth = with(LocalDensity.current) { measurer.measure(labels[tab], androidx.compose.ui.text.TextStyle(fontSize = 13.5.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)).size.width.toDp() }
        val desired = labelWidth + 53.dp
        val showLabel = !largeFont && maxWidth >= desired + inactive * 4
        val targetWidth = if (showLabel) desired else 48.dp
        val active by animateDpAsState(targetWidth, tween(300), label = "Pill width")
        val gap = ((maxWidth - inactive * 4 - targetWidth) / 6).coerceAtLeast(0.dp)
        val x by animateDpAsState(gap + (inactive + gap) * tab, tween(300, easing = CubicBezierEasing(.2f, 0f, 0f, 1f)), label = "Tab pill")
        val presses = List(labels.size) { index -> rememberDockPress { index == tab } }
        Box(Modifier.offset(x, 6.dp).width(active).height(40.dp).clip(RoundedCornerShape(20.dp)).drawBehind {
            val press = presses[tab] // pressing the active tab brightens the capsule, 16 % to 24 %
            drawRect(accent.text.copy(alpha = .16f + if (press.onActive) .08f * press.glow.value else 0f))
        })
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            labels.forEachIndexed { index, label ->
                val itemWidth by animateDpAsState(if (index == tab) targetWidth else inactive, tween(300), label = "Tab width")
                val press = presses[index]
                Row(Modifier.width(itemWidth).height(48.dp)
                    .selectable(index == tab, press.source, indication = null, role = Role.Tab, onClick = { state.expand(); select(index) })
                    .dockFeedback(press, focusRing(press), accent.text, disc = true, width = if (index == tab) itemWidth else 40.dp)
                    .semantics { contentDescription = label }.testTag("navLabel-$index"),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(icons[index], null, Modifier.size(24.dp), tint = if (index == tab) accent.text else Color(0xFFA0A09D))
                    AnimatedVisibility(index == tab && showLabel, enter = fadeIn(tween(150, delayMillis = 150)), exit = fadeOut(tween(100))) {
                        Text(label, Modifier.padding(start = 5.dp).clearAndSetSemantics {}, fontSize = 13.5.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
    }
}
