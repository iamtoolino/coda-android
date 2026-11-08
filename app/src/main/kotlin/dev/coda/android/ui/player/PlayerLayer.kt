package dev.coda.android.ui.player

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.media3.session.MediaController
import dev.coda.android.artwork.CoverArtFetcher
import dev.coda.android.ui.queue.QueueSheet
import dev.coda.android.ui.theme.AccentState
import dev.coda.android.ui.theme.drawPageBackdrop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** How long a restored, expanded player waits for a connected session's queue before collapsing. */
internal const val EmptyQueueGraceMs = 1500L

/**
 * The album page on screen publishes its sleeve and play button here, so Now Playing can open and close in place
 * over the playing album's own page (spec M3's endpoints) instead of growing out of the mini player (M1/M2).
 */
@Stable
internal class AlbumStage {
    /** One composed album page; rects are root px from the page's last layout. */
    class Page(val albumId: String) {
        var sleeve = Rect.Zero
        var play = Rect.Zero
        var scrolled: () -> Boolean = { true }
        /** Jumps the page back to the top (instantly: Now Playing covers it while this runs). */
        var scrollToTop: suspend () -> Unit = {}
    }
    var page by mutableStateOf<Page?>(null); private set
    fun register(page: Page) { this.page = page }
    fun unregister(page: Page) { if (this.page === page) this.page = null }
    /** Read in the page's draw phase: Now Playing's play button stands in for [page]'s own while it is in place. */
    var covering: () -> Boolean = { false }
    fun covers(page: Page) = this.page === page && covering()
    /** The page to open or close onto: the playing album's, with its sleeve fully at the top (not scrolled at all). */
    fun inPlaceFor(playingAlbumId: String?): Page? = page?.takeIf {
        playingAlbumId != null && it.albumId == playingAlbumId && !it.scrolled() &&
            it.sleeve != Rect.Zero && it.play != Rect.Zero && it.sleeve.top > -1f
    }
}

internal val LocalAlbumStage = staticCompositionLocalOf<AlbumStage?> { null }

/**
 * 1 while the page under Now Playing is at rest; in the in-place open it fades to 0 over the same first 30 % of the
 * progress as the page's other text (the page-colour fade below), and back in on close. Page text that sits over the sleeve
 * (the album title block), where that fade deliberately does not paint, reads it, so it never hovers over Now Playing's
 * cover. Read in draw/graphics lambdas only.
 */
internal val LocalPageTextAlpha = staticCompositionLocalOf<() -> Float> { { 1f } }

/** One in-place transition's endpoints on the album page, in root px, fixed when the transition starts. */
internal class InPlace(val page: AlbumStage.Page) {
    val sleeve = page.sleeve
    val play = page.play
    /** Now Playing's open sleeve rect (set by Now Playing). */
    var coverEnd = Rect.Zero
}

/** Test seam: told, for every transition that picks its endpoints at rest, whether it chose in place. */
@androidx.annotation.VisibleForTesting
internal var endpointProbe: ((inPlace: Boolean) -> Unit)? = null

/** What the library's dock needs from the player. */
class PlayerDock internal constructor(
    /** Slides the whole dock out of the way as the player opens (v0.1.5). */
    val modifier: Modifier,
)

/**
 * A player overlay; opening it leaves the library's screen and scroll state composed underneath.
 * Expansion progress is only read in draw/graphics lambdas and derived thresholds, never per frame in composition.
 */
@Composable
fun PlayerLayer(
    controller: MediaController?, revision: Int, fetcher: CoverArtFetcher, accent: AccentState, dockTint: Float = .12f,
    /** The library's navigation dock, below Now Playing: it slides away as the player opens. */
    dockLayer: @Composable (mini: @Composable () -> Unit, dock: PlayerDock) -> Unit = { _, _ -> },
    content: @Composable (mini: @Composable () -> Unit, dock: PlayerDock) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (expanded) 1f else 0f) }
    val visible by remember { derivedStateOf { progress.value > 0f } }
    // v0.1.5 replaces the mini row with a spacer past 20 %.
    val miniGone by remember { derivedStateOf { progress.value >= .2f } }
    val back = remember { Animatable(0f) }
    var backEdge by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val hasItem = controller?.currentMediaItem != null
    // In place over the playing album's page. The endpoints are chosen when a transition starts from rest and kept
    // until the next one, so a reversal, a track change or a scroll mid-flight never jumps; if the page leaves meanwhile
    // (navigation), the transition falls back to the normal one.
    val stage = remember { AlbumStage() }
    var placed by remember { mutableStateOf<InPlace?>(null) }
    val inPlace = placed?.takeIf { it.page === stage.page }
    val controllerNow by rememberUpdatedState(controller)
    val miniSwipe = remember { MiniSwipe() }
    var openings by remember { mutableIntStateOf(0) }
    // The album link's pending navigation (waiting out the opening and the album page's layout). Any other owner of the
    // player (a close, a gesture, Back, the queue) cancels it, so it never closes the player under them.
    var pendingAlbum by remember { mutableStateOf<Job?>(null) }
    fun cancelPendingAlbum() { pendingAlbum?.cancel(); pendingAlbum = null }
    fun chooseEndpoints() {
        if (progress.isRunning || (progress.value != 0f && progress.value != 1f)) return
        placed = stage.inPlaceFor(controllerNow?.mediaMetadata?.extras?.getString("albumId")?.takeIf { it.isNotEmpty() })?.let(::InPlace)
        endpointProbe?.invoke(placed != null)
    }
    stage.covering = { placed?.page?.let { it === stage.page } == true && progress.value > 0f }
    fun expand(value: Boolean, fromGesture: Boolean = false) {
        cancelPendingAlbum()
        chooseEndpoints()
        expanded = value
        scope.launch {
            progress.animateTo(if (value) 1f else 0f, if (fromGesture) spring(1f, 500f) else
                tween(if (value) 450 else 350, easing = if (value) CubicBezierEasing(.05f, .7f, .1f, 1f) else CubicBezierEasing(.2f, 0f, 0f, 1f)))
        }
    }
    /** Navigate the screen underneath first (the expanded player covers it), then collapse, so the old screen never flashes. */
    fun collapseThen(action: () -> Unit) {
        cancelPendingAlbum()
        // Chosen before navigating: a page that then leaves falls back to the normal close (see inPlace).
        chooseEndpoints()
        action()
        expanded = false
        scope.launch { progress.animateTo(0f, tween(350, easing = CubicBezierEasing(.2f, 0f, 0f, 1f))) }
    }
    /**
     * The album link is always the in-place morph (spec M3), whatever page is underneath. The destination is the playing
     * album and its cover is Now Playing's cover, so the morph always fits. The request outlives the 450 ms opening (a tap during
     * it waits for it to finish); the album's page is opened first while Now Playing still covers the screen, a scrolled page of
     * the album is put back to the top, and the close starts once that page has laid out its sleeve and play button (a few frames,
     * capped at 500 ms; then the normal close). The endpoints are chosen at that moment from the then-current playback, so a track
     * change to another album meanwhile gives the normal close onto the requested page. Any other owner of the player cancels it.
     */
    fun collapseToAlbum(albumId: String, open: (String) -> Unit) {
        val playing = controllerNow?.mediaMetadata?.extras?.getString("albumId")?.takeIf { it.isNotEmpty() }
        if (!expanded || albumId != playing) { collapseThen { open(albumId) }; return }
        if (pendingAlbum?.isActive == true) return // a repeated tap
        val job = scope.launch {
            snapshotFlow { progress.value == 1f && !progress.isRunning }.first { it }
            open(albumId)
            val start = withFrameNanos { it }
            var frames = 0
            while (true) {
                // The album page's own Back handler registers after the player's and would take Back from it: re-create the
                // player's, once the page has composed, so a Back during the wait is the player's gesture and cancels this request.
                if (++frames == 2) openings++
                val page = stage.page?.takeIf { it.albumId == albumId }
                if (page != null && page.scrolled()) page.scrollToTop()
                else if (stage.inPlaceFor(albumId) != null) break
                if (withFrameNanos { it } - start >= 500_000_000L) break
            }
            pendingAlbum = null
            chooseEndpoints()
            expanded = false
            scope.launch { progress.animateTo(0f, tween(350, easing = CubicBezierEasing(.2f, 0f, 0f, 1f))) }
        }
        pendingAlbum = job
        job.invokeOnCompletion { if (pendingAlbum === job) pendingAlbum = null }
    }
    // A restored expanded player survives the controller connecting (controller null). Once connected, losing the item
    // collapses at once; a session that connects without one gets a short grace for its queue restore, then collapses too.
    var hadItem by remember { mutableStateOf(false) }
    LaunchedEffect(controller, hasItem) {
        if (hasItem) { hadItem = true; return@LaunchedEffect }
        if (controller == null) return@LaunchedEffect
        if (!hadItem) kotlinx.coroutines.delay(EmptyQueueGraceMs)
        if (expanded || progress.value > 0f) { cancelPendingAlbum(); expanded = false; progress.snapTo(0f) }
        hadItem = false
    }
    // The player covers whatever screen is underneath, so its Back handler must win over theirs (a
    // filtered Artists list, Licences, ...). Handlers added later take precedence, so the handler is re-created whenever the player
    // opens (not when it closes, which must not cut the closing handler's own animation): it is then the newest one.
    LaunchedEffect(expanded) { if (expanded) openings++ }
    key(openings) {
        PredictiveBackHandler(enabled = expanded && !queueOpen) { events ->
            cancelPendingAlbum() // the gesture owns the player now
            try {
                events.collect { event ->
                    backEdge = event.swipeEdge
                    // The back preview scales the page (v0.1.5).
                    back.snapTo(event.progress)
                }
                expand(false)
                back.animateTo(0f, tween(350))
            } catch (cancelled: CancellationException) {
                scope.launch { back.animateTo(0f, tween(200, easing = CubicBezierEasing(0f, 0f, 0f, 1f))) }
                throw cancelled
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // The measured dock slides away and the player starts at the measured mini row.
        var classicDockHeight by remember { mutableStateOf(80.dp + systemBottom) }
        var classicMiniY by remember { mutableStateOf<androidx.compose.ui.unit.Dp?>(null) }
        val playerHeight = maxHeight
        val miniTop = classicMiniY ?: (maxHeight - classicDockHeight - 56.dp)
        val playerScroll = androidx.compose.foundation.rememberScrollState()
        LaunchedEffect(visible) { if (!visible) playerScroll.scrollTo(0) }
        val miniTopPx = with(density) { miniTop.toPx() }
        // The player reads each event before its children (Initial pass) and decides ownership as the touch slop is
        // crossed. A mostly-vertical drag is consumed there, so the sleeve pager never sees it, even when a fast swipe's
        // first move already exceeds its slop sideways. Clearly horizontal drags, and
        // vertical ones an overflowing details scroll can still take, are left to the children; a child that claimed
        // an event first (its consumption shows on that event after the pass) keeps the drag.
        //
        // A clearly horizontal drag on the collapsed mini player (outside the edge margins) is the track swipe. The
        // content follows the finger (MiniSwipe) and release past 56 dp skips: toward the start edge is next, as the sleeve
        // pager pages (right-to-left in LTR, mirrored in RTL). A new swipe may start while the last one settles (see MiniSwipe.begin).
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        fun settleMini(commit: Boolean, dx: Float) {
            val toNext = swipesToNext(dx, rtl)
            val pin = miniSwipe.pin()
            miniSwipe.letGo()
            scope.launch {
                miniSwipe.settle(commit, dx, rtl, reduceMotion = !android.animation.ValueAnimator.areAnimatorsEnabled(),
                    stillCurrent = { controllerNow?.let { c -> pin.stillCurrent(c.currentMediaItemIndex, c::occurrenceAt, toNext) } == true },
                    seek = { if (toNext) controllerNow?.seekToNextMediaItem() else controllerNow?.seekToPreviousMediaItem() },
                    reached = { controllerNow?.currentMediaItemIndex == it })
            }
        }
        val gesture = Modifier.pointerInput(miniTopPx, expanded, rtl) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // As with detectDragGestures' drag start: the edge margins are judged where the slop is crossed.
                fun skipAllowed(x: Float) = x >= 24.dp.toPx() && x <= size.width - 24.dp.toPx()
                val slop = viewConfiguration.touchSlop
                var dx = 0f; var dy = 0f; var direction = 0
                var ended = false
                try {
                var previous: androidx.compose.ui.input.pointer.PointerInputChange? = null
                while (true) {
                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) { ended = true; break }
                    if (direction == 0 && (previous?.isConsumed == true || change.isConsumed)) break
                    previous = change
                    val delta = change.positionChange()
                    dx += delta.x; dy += delta.y
                    if (direction == 0) {
                        val ax = kotlin.math.abs(dx); val ay = kotlin.math.abs(dy)
                        if (maxOf(ax, ay) <= slop) continue
                        direction = when {
                            ay > ax * 1.25f -> when {
                                // The details scroll keeps a drag it can still scroll (large fonts).
                                expanded && dy > 0 && playerScroll.canScrollBackward -> -1
                                expanded && dy < 0 && playerScroll.canScrollForward -> -1
                                else -> 1
                            }
                            ax > ay * 1.25f -> if (!expanded && controllerNow != null && skipAllowed(change.position.x)) 2 else -1
                            maxOf(ax, ay) > slop * 3 -> -1
                            else -> 0
                        }
                        if (direction < 0) break
                        if (direction == 1) { cancelPendingAlbum(); chooseEndpoints() }
                        if (direction == 2) controllerNow?.let {
                            val current = it.currentMediaItemIndex; val next = it.nextMediaItemIndex; val previous = it.previousMediaItemIndex
                            miniSwipe.begin(current, next, previous, it.occurrenceAt(current), it.occurrenceAt(next), it.occurrenceAt(previous))
                        }
                    }
                    // Once the drag is ours it is consumed in both directions, as v0.1.5's detectDragGestures did: the sleeve
                    // pager then gives up and a mostly-vertical swipe (up: queue, down: close) can never also page or seek.
                    if (direction == 1) {
                        change.consume()
                        if (!expanded || dy > 0) scope.launch { progress.snapTo(((if (expanded) 1f else 0f) - dy / miniTopPx).coerceIn(0f, 1f)) }
                    } else if (direction == 2) { change.consume(); miniSwipe.drag(dx, rtl) }
                }
                if (direction == 1) {
                    if (!ended) scope.launch { progress.animateTo(if (expanded) 1f else 0f, spring(1f, 500f)) }
                    else if (expanded && dy < -48.dp.toPx()) queueOpen = true
                    else {
                        val velocity = dy / ((android.os.SystemClock.uptimeMillis() - down.uptimeMillis).coerceAtLeast(1) / 1000f)
                        expand(if (expanded) progress.value > .65f && velocity < 1000.dp.toPx()
                            else progress.value > .35f || velocity < -1000.dp.toPx(), fromGesture = true)
                    }
                } else if (direction == 2) {
                    val commit = ended && kotlin.math.abs(dx) > 56.dp.toPx()
                    settleMini(commit, dx)
                }
                } finally {
                    // The gesture's coroutine ended without a release (the modifier restarted): never leave a swipe held.
                    if (miniSwipe.holding) settleMini(false, dx)
                }
            }
        }
        val dock = PlayerDock(
            modifier = Modifier.onSizeChanged { classicDockHeight = with(density) { it.height.toDp() } }
                .graphicsLayer { translationY = classicDockHeight.toPx() * (progress.value * 2).coerceAtMost(1f) })
        val miniSlot: @Composable () -> Unit = {
            if (hasItem && controller != null) {
                val miniPlayer = @Composable {
                    dev.coda.android.ui.theme.AccentTheme(accent) {
                        MiniPlayer(controller, revision, fetcher, accent, onOpen = { expand(true) }, onQueue = { cancelPendingAlbum(); queueOpen = true }, swipe = miniSwipe)
                    }
                }
                // Keep the gesture owner composed until release, including past 20% expansion.
                Box(Modifier.onGloballyPositioned { if (progress.value == 0f) classicMiniY = with(density) { it.positionInRoot().y.toDp() } }
                    .then(gesture).graphicsLayer { alpha = (1f - progress.value * 5).coerceIn(0f, 1f) }) {
                    if (miniGone) Spacer(Modifier.fillMaxWidth().height(56.dp)) else miniPlayer()
                }
            }
        }
        // The player covers the dock too, which slides away beneath it.
        Box(Modifier.fillMaxSize().semantics { if (expanded) hideFromAccessibility() }) {
            CompositionLocalProvider(LocalAlbumStage provides stage,
                LocalPageTextAlpha provides { if (inPlace != null) 1f - (progress.value / .3f).coerceIn(0f, 1f) else 1f }) { content(miniSlot, dock) }
        }
        Box(Modifier.fillMaxSize().semantics { if (expanded) hideFromAccessibility() }) { dockLayer(miniSlot, dock) }
        val openPlaylist = dev.coda.android.ui.library.LocalOpenPlaylist.current
        val openAlbum = dev.coda.android.ui.library.LocalOpenAlbum.current
        val openTrackArtist = dev.coda.android.ui.library.LocalOpenTrackArtist.current
        val openArtist = dev.coda.android.ui.library.LocalOpenArtist.current
        val trackArtists = dev.coda.android.ui.library.LocalTrackArtists.current
        if (hasItem && controller != null && visible) {
            Box(Modifier.zIndex(1f).fillMaxWidth().height(playerHeight)
                .graphicsLayer {
                    scaleX = 1f - .1f * back.value; scaleY = scaleX
                    // The normal open and close cross-fade with the mini player over their first 20 % (its alpha is 1 - 5 p),
                    // so the card's last frames over the mini row never hide its title and controls and leave only the cover tile.
                    // In place the card is the whole window from the start and the page underneath fades instead.
                    alpha = if (inPlace != null) 1f else (progress.value / .2f).coerceIn(0f, 1f)
                    translationX = with(density) { 8.dp.toPx() } * back.value * if (backEdge == 0) 1 else -1
                }.drawWithContent {
                    // The window is the container from the start; the page underneath fades out instead.
                    val amount = if (inPlace != null) 1f else progress.value
                    val inset = 8.dp.toPx() * (1 - amount)
                    val start = miniTopPx
                    val top = start * (1 - amount)
                    val bottom = (start + 56.dp.toPx()) * (1 - amount) + size.height * amount
                    val radius = (24 * (1 - amount) + 28 * back.value).dp.toPx()
                    val path = Path().apply { addRoundRect(RoundRect(inset, top, size.width - inset, bottom, CornerRadius(radius))) }
                    val contentScope = this
                    clipPath(path) {
                        val fade = (progress.value / .3f).coerceIn(0f, 1f)
                        if (inPlace != null && fade < 1f) {
                            // The album's text and rows fade out over p 0–0.3. Not under the sleeve: Now Playing's identical
                            // sleeve crossfades with the page's there (a backdrop between them would dip the picture).
                            val cover = androidx.compose.ui.geometry.lerp(inPlace.sleeve, inPlace.coverEnd.takeIf { it != Rect.Zero } ?: inPlace.sleeve, progress.value)
                            clipRect(cover.left, cover.top, cover.right, cover.bottom, androidx.compose.ui.graphics.ClipOp.Difference) {
                                drawRect(accent.page, alpha = fade)
                                drawPageBackdrop(accent, fade, fade)
                            }
                        } else if (inPlace != null) {
                            drawRect(accent.page); drawPageBackdrop(accent)
                        } else {
                            // One Voice: the container starts in the dock's own surface colour.
                            drawRect(dev.coda.android.ui.theme.srgbLerp(dev.coda.android.ui.theme.srgbLerp(Color(0xFF15171A), accent.accent, dockTint), accent.page, (amount / .6f).coerceAtMost(1f)))
                            drawPageBackdrop(accent, (amount / .6f).coerceAtMost(1f), amount)
                        }
                        contentScope.drawContent()
                    }
                }) {
                dev.coda.android.ui.theme.AccentTheme(accent) { NowPlaying(controller, revision, fetcher, accent, { progress.value }, miniTop, gesture, scroll = playerScroll,
                    onCollapse = { expand(false) }, onQueue = { cancelPendingAlbum(); queueOpen = true },
                    bottomPadding = systemBottom, inPlace = inPlace,
                    onArtist = { albumId, songId -> collapseThen { openTrackArtist(albumId, songId) } },
                    onArtistId = { id -> collapseThen { openArtist(id) } }, artistsOf = trackArtists,
                    onAlbum = { albumId -> collapseToAlbum(albumId, openAlbum) }) }
            }
        }
        if (controller != null && queueOpen) Box(Modifier.zIndex(3f)) { CompositionLocalProvider(dev.coda.android.ui.library.LocalOpenPlaylist provides { id ->
            expand(false); openPlaylist(id)
        }, dev.coda.android.ui.library.LocalOpenAlbum provides { id -> expand(false); openAlbum(id) }) { dev.coda.android.ui.theme.AccentTheme(accent) { QueueSheet(controller, revision, fetcher, accent) { queueOpen = false } } } }
    }
}

/** The occurrence at [index] ([occurrenceKey]); null outside the queue. */
private fun androidx.media3.session.MediaController.occurrenceAt(index: Int): String? =
    if (index in 0 until mediaItemCount) getMediaItemAt(index).let { occurrenceKey(it.mediaId, it.mediaMetadata.extras?.getString("queueEntryId")) } else null
