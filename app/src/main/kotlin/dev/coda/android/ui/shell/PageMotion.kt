package dev.coda.android.ui.shell

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import dev.coda.android.ui.theme.AccentState
import dev.coda.android.ui.theme.NeutralPageAccent
import dev.coda.android.ui.theme.mix
import dev.coda.android.ui.theme.rememberAccentState
import dev.coda.core.accent.AccentSelection

/**
 * Page motion (Pages "Fade-through", Album colour "Cached, colour before fade-in").
 *
 * The outgoing page fades out in 90 ms (standard-accelerate, done by the shell's AnimatedContent). The incoming page is
 * composed at once but stays invisible until its own colour is ready, at the earliest 90 ms and at the latest 150 ms after
 * the tap; then it fades in over 210 ms (standard) while scaling 0.96 → 1. A colour that is ready by then is set without a
 * tween (the page was invisible). Past the cap the page fades in with neutral roles and every accented element fades to
 * its colour over 450 ms when it arrives. Durations follow the system animator scale; at scale 0 the page shows at
 * "ready or 150 ms" with no fades. All times are milliseconds since the tap, on one clock ([SystemClock.uptimeMillis]):
 * the shell stamps the tap in the event handler ([NavigationClock]), and the reveal, the colour's readiness and every
 * frame are measured against that stamp, so composition and frame delay count against the 150 ms.
 */
internal object PageMotion {
    const val OUT_MS = 90L
    const val IN_MS = 210L
    const val CAP_MS = 150L
    const val COLOUR_MS = 450L
    const val START_SCALE = .96f
    val Accelerate = CubicBezierEasing(.3f, 0f, 1f, 1f)
    val Standard = CubicBezierEasing(.2f, 0f, 0f, 1f)

    /** One frame of an incoming page: its opacity and scale, and how far its roles are from neutral (0) to its own (1). */
    data class Frame(val alpha: Float, val scale: Float, val colour: Float, val revealedAt: Long?) {
        val visible get() = alpha > 0f
        val settled get() = alpha == 1f && colour == 1f
    }

    /**
     * The frame at [elapsed], given when the page's own colour became ready ([readyAt], null while it is not). Causal: it
     * never needs a future ready time. [entering] is false for a page that is already on screen (first launch, restore),
     * which shows at once and only fades a late colour in.
     */
    fun frame(elapsed: Long, readyAt: Long?, durationScale: Float, entering: Boolean = true): Frame {
        val instant = durationScale <= 0f
        fun scaled(ms: Long) = if (instant) 0L else (ms * durationScale).toLong()
        val earliest = if (entering) scaled(OUT_MS) else 0L
        val cap = if (!entering) 0L else if (instant) CAP_MS else maxOf(earliest, scaled(CAP_MS))
        val revealAt = when {
            readyAt != null && readyAt <= cap -> maxOf(earliest, readyAt)
            elapsed >= cap -> cap
            else -> null
        }
        if (revealAt == null || elapsed < revealAt) return Frame(0f, START_SCALE, if (readyAt != null) 1f else 0f, null)
        val shown = if (instant || !entering) 1f else Standard.transform(((elapsed - revealAt).toFloat() / scaled(IN_MS)).coerceIn(0f, 1f))
        val neutral = readyAt == null || readyAt > cap
        val colour = when {
            !neutral -> 1f
            readyAt == null -> 0f
            instant -> 1f
            else -> Standard.transform(((elapsed - readyAt).toFloat() / scaled(COLOUR_MS)).coerceIn(0f, 1f))
        }
        return Frame(shown, START_SCALE + (1f - START_SCALE) * shown, colour, revealAt)
    }

    /**
     * Whether this page needs another animation frame. A page whose colour has not arrived and that is fully faded in
     * (neutral, [Frame.colour] 0) is waiting for an event, not for time: it requests no frames (an album that never
     * resolves, such as an uncached one offline, must not keep the screen redrawing). The colour's arrival restarts it.
     */
    fun wantsFrame(frame: Frame, readyAt: Long?): Boolean = !frame.settled && !(frame.alpha == 1f && readyAt == null)

    /**
     * The colour an album page may show as its own: the page accent once it names that album, never another one. Until
     * then, the album that is playing may wear the playback colour (its own artwork, already derived).
     */
    fun albumOwn(pageIdentity: String?, pageSelection: AccentSelection, albumId: String,
        playbackIdentity: String? = null, playbackSelection: AccentSelection? = null): AccentSelection? =
        pageSelection.takeIf { pageIdentity == "album:$albumId" }
            ?: playbackSelection.takeIf { playbackIdentity == "playing:album:$albumId" }

    /** The roles a page draws: neutral mixed towards its own roles by [Frame.colour]. */
    fun roles(own: AccentState?, colour: Float): AccentState = when {
        own == null -> NeutralPageAccent
        colour >= 1f -> own
        else -> NeutralPageAccent.mix(own, colour)
    }
}

/**
 * What a page's reveal did, for device tests: ms after the tap when the colour was ready (0: it already was when the page
 * appeared) and when the fade-in was scheduled to start ([revealedMs], on the same clock), when the first visible frame was
 * actually drawn ([firstVisibleMs]), the uptime the page's clock started at ([tappedAt]: the tap's event handler), and how
 * often its opacity went down between two frames ([drops]: a page that blinks out when it is re-targeted while leaving).
 */
internal data class PageReveal(val readyMs: Long?, val revealedMs: Long?, val neutral: Boolean, val active: Boolean, val alpha: Float,
    val firstVisibleMs: Long? = null, val tappedAt: Long = 0, val drops: Int = 0)
internal val PageRevealKey = SemanticsPropertyKey<PageReveal>("PageReveal")

/** Test hook: every animation frame a [RevealedPage] has requested in this process. A page that waits for its colour adds none. */
internal val PageFrameRequests = java.util.concurrent.atomic.AtomicLong()

/**
 * The uptime of the latest navigation event. Every writer of the shell's navigation state stamps it in the event handler
 * ([stamp]), before composition, so the 150 ms deadline of a page opened by a tap starts at the tap.
 */
internal class NavigationClock {
    @Volatile var at: Long = android.os.SystemClock.uptimeMillis()
        private set
    fun mark() { at = android.os.SystemClock.uptimeMillis() }
}

/** [state] that stamps [clock] whenever a navigation changes its value. */
@Composable
internal fun <T> NavigationClock.stamp(state: MutableState<T>): MutableState<T> = remember(state, this) {
    object : MutableState<T> by state {
        override var value: T
            get() = state.value
            set(next) { if (next != state.value) mark(); state.value = next }
    }
}

/**
 * Who owns Back and focus: only the [active] page (the shell's target) does; a page that is fading out keeps its state but
 * must not take Back or focus. [shown]: the page is also visible (focus waits for the fade-in).
 */
internal data class PageOwnership(val active: Boolean, val shown: Boolean)
internal val LocalPageOwnership = compositionLocalOf { PageOwnership(active = true, shown = true) }

/** A page's own Back step: enabled only while its page is the active one (an outgoing page must not keep Back). */
@Composable
internal fun PageBackHandler(enabled: Boolean = true, onBack: () -> Unit) =
    androidx.activity.compose.BackHandler(enabled && LocalPageOwnership.current.active, onBack)

/**
 * One page inside the shell's transition. [own] is the page's own accent (null while it is not ready); [entering] is
 * false for the page shown without a transition; [active] turns false once the page is leaving, which freezes its roles
 * and opacity (the outgoing page keeps its colour while the shell fades it out). [tappedAt] is the uptime of the tap,
 * taken in the event handler ([NavigationClock]).
 */
@Composable
internal fun RevealedPage(own: AccentSelection?, entering: Boolean, active: Boolean, tappedAt: Long,
    content: @Composable (AccentState) -> Unit) {
    val durationScale = remember { android.animation.ValueAnimator.getDurationScale() }
    // The first tap that showed this page: a page re-targeted while it is still leaving continues its own timeline.
    val navAt = remember { tappedAt }
    val sinceTap = { android.os.SystemClock.uptimeMillis() - navAt }
    val held = remember { HeldReveal() }
    // A leaving page keeps the colour it had: the source may already name the next page.
    if (active && own != null) {
        // A colour that is there when the page first composes was ready at the tap (hit); otherwise the page saw it arrive now.
        if (held.readyMs == null) held.readyMs = if (held.composed) sinceTap() else 0L
        held.own = own
    }
    held.composed = true
    val shownOwn = held.own
    val readyAt = held.readyMs
    var frame by remember { mutableStateOf(PageMotion.frame(0, readyAt, durationScale, entering)) }
    // Restarts when the colour's readiness changes: a page waiting for its colour requests no frames meanwhile.
    LaunchedEffect(active, readyAt) {
        if (!active) return@LaunchedEffect
        while (PageMotion.wantsFrame(frame, readyAt)) withFrameMillis {
            PageFrameRequests.incrementAndGet()
            val next = PageMotion.frame(sinceTap(), readyAt, durationScale, entering)
            if (next.visible && held.firstVisibleMs == null) held.firstVisibleMs = sinceTap()
            if (next.alpha < frame.alpha - .02f) held.drops++
            frame = next
        }
    }
    // Before its colour has been seen the page takes it without a tween; later changes (refreshed artwork) cross-fade.
    val ownSeen = frame.visible && frame.colour >= 1f
    val ownRoles = shownOwn?.let { rememberAccentState(it, animate = ownSeen) }
    val roles = PageMotion.roles(ownRoles, frame.colour)
    if (active) held.roles = roles
    val revealedAt = frame.revealedAt
    val reveal = PageReveal(readyAt, revealedAt, readyAt.let { it == null || revealedAt != null && it > revealedAt }, active, frame.alpha, held.firstVisibleMs, navAt, held.drops)
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()
        .graphicsLayer { val f = frame; alpha = f.alpha; scaleX = f.scale; scaleY = f.scale }
        .semantics { this[PageRevealKey] = reveal }) {
        CompositionLocalProvider(LocalPageOwnership provides PageOwnership(active, shown = active && frame.visible)) {
            content(if (active) roles else held.roles ?: roles)
        }
    }
}

/** Plain (not observed) memory of a page across its transition. */
private class HeldReveal {
    var own: AccentSelection? = null
    var readyMs: Long? = null
    var firstVisibleMs: Long? = null
    var drops = 0
    var composed = false
    var roles: AccentState? = null
}
