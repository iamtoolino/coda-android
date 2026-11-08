package dev.coda.android.ui.player

import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.pageLeft
import androidx.compose.ui.semantics.pageRight
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/** What the sleeve needs to know about the playback queue (the controller, or a fake in tests). */
internal interface SleeveQueue {
    /** The index of the playing item. */
    val playingIndex: Int
    val count: Int
    /** The identity of the queue entry at [index] (unique per occurrence); null if out of range. */
    fun entryAt(index: Int): String?
    /** The cover ID a queue page shows; null (no artwork) is the placeholder, so two coverless tracks also match. */
    fun coverAt(index: Int): String?
    /** Plays the item at [index] from its start. */
    fun seekTo(index: Int)
}

/** Identifies the queue's entries and their order; any edit made while a swipe is held makes that swipe stale. */
internal fun SleeveQueue.key(): Int = (0 until count).fold(count) { key, i -> 31 * key + entryAt(i).hashCode() }

internal class ControllerSleeveQueue(private val controller: MediaController) : SleeveQueue {
    override val playingIndex get() = controller.currentMediaItemIndex
    override val count get() = controller.mediaItemCount
    override fun entryAt(index: Int): String? =
        if (index in 0 until count) controller.getMediaItemAt(index).let { it.mediaMetadata.extras?.getString("queueEntryId") ?: it.mediaId } else null
    override fun coverAt(index: Int): String? =
        if (index in 0 until count) controller.getMediaItemAt(index).mediaMetadata.extras?.getString("coverArtId") else null
    override fun seekTo(index: Int) = controller.seekTo(index, 0)
}

/**
 * Makes a queue whose getters Compose cannot observe (the MediaController's) observable: every read first reads
 * [revision], a snapshot state that changes whenever the controller reports a change. Without it, the sleeve could be
 * skipped on recomposition and miss a change made elsewhere.
 */
internal class RevisionSleeveQueue(private val revision: () -> Int, private val source: SleeveQueue) : SleeveQueue {
    override val playingIndex get() = revision().let { source.playingIndex }
    override val count get() = revision().let { source.count }
    override fun entryAt(index: Int) = revision().let { source.entryAt(index) }
    override fun coverAt(index: Int) = revision().let { source.coverAt(index) }
    override fun seekTo(index: Int) = source.seekTo(index)
}

private enum class SleeveMotion { Moving, BetweenPages, AtRest }

/** How long (frame time) the sleeve must stay at rest before the rest counts: several frames; a fling starts within one. */
internal const val REST_SETTLE_MS = 120L

/**
 * The Now Playing sleeve pager. [SleeveSync] decides between seeking (a fresh swipe) and following the playing item
 * (everything else). A gesture is armed by an explicit signal only: a drag starting, or one of the pager's accessibility
 * page actions (see [sleeveAccessibility]). Nothing is inferred from the scroll state, which cannot tell an action that
 * interrupts a follow from the follow itself.
 *
 * [queue] must be observable (Compose snapshot reads): wrap a controller in [RevisionSleeveQueue].
 */
@Composable
internal fun SleevePager(
    queue: SleeveQueue, userScrollEnabled: Boolean, modifier: Modifier = Modifier,
    onState: (PagerState) -> Unit = {}, page: @Composable PagerScope.(Int) -> Unit,
) {
    // Semantics (and test harnesses) evaluate the page count off the main thread; the MediaController may only be
    // read on it, so the count is read here, in composition, and the lambda returns the latest value.
    val itemCount by rememberUpdatedState(queue.count)
    val pager = rememberPagerState(initialPage = queue.playingIndex.coerceAtLeast(0), pageCount = { itemCount })
    SideEffect { onState(pager) }
    val sync = remember(pager) { SleeveSync(queue.playingIndex, queue.entryAt(queue.playingIndex)) }
    /** A drag is held (its finger is down): the sleeve counts as moving, so no rest is acted on. */
    var dragging by remember { mutableStateOf(false) }
    /** Follow scrolls in flight; a counter, because a new follow can start before a cancelled one has finished. */
    var following by remember { mutableIntStateOf(0) }
    suspend fun follow(target: Int) {
        if (target < 0 || target >= queue.count || pager.currentPage == target) return
        following++
        try {
            // The sleeve only moves when the artwork does. The next track of the same album jumps silently.
            if (sleeveAnimates(queue.coverAt(pager.currentPage), queue.coverAt(target)))
                pager.animateScrollToPage(target, animationSpec = spring(1f, 400f))
            else pager.scrollToPage(target)
        } finally { following-- }
    }
    val playingEntry = queue.entryAt(queue.playingIndex)
    LaunchedEffect(queue.playingIndex, playingEntry) {
        val target = queue.playingIndex
        // A held drag rejects this scroll; the sleeve then catches up when it comes to rest.
        if (sync.indexChanged(target, playingEntry)) follow(target)
    }
    LaunchedEffect(pager, queue) {
        launch {
            pager.interactionSource.interactions.collect {
                when (it) {
                    is DragInteraction.Start -> { dragging = true; sync.scrollStarted(queue.key()) }
                    is DragInteraction.Stop, is DragInteraction.Cancel -> dragging = false
                }
            }
        }
        // The pager updates settledPage while its snap animation still runs, and a fast swipe past halfway leaves a
        // frame between the drag and its fling with no scroll running. So the sleeve is only at rest when nothing
        // scrolls and it sits exactly on a page. Stopped between pages for longer (a cancelled follow), it snaps
        // to the nearest page, which then comes to rest as usual.
        snapshotFlow {
            when {
                pager.isScrollInProgress || following > 0 || dragging -> SleeveMotion.Moving
                abs(pager.currentPageOffsetFraction) > .01f -> SleeveMotion.BetweenPages
                else -> SleeveMotion.AtRest
            }
        }.collectLatest { motion ->
            when (motion) {
                SleeveMotion.Moving -> Unit
                SleeveMotion.BetweenPages -> {
                    delay(300)
                    launch { following++; try { pager.animateScrollToPage(pager.currentPage) } finally { following-- } }
                }
                SleeveMotion.AtRest -> {
                    // The frame between a drag and its fling has no scroll running and may sit within .01 of any page.
                    // Act only once the sleeve stays at rest for a few frames: a fling (or any scroll) starting cancels
                    // this block. Counted in frame time, so Compose tests drive it with their clock.
                    val restedAt = withFrameMillis { it }
                    while (withFrameMillis { it } - restedAt < REST_SETTLE_MS) Unit
                    val page = pager.settledPage
                    val index = queue.playingIndex
                    when (val rest = sync.atRest(page, queue.entryAt(page), index, queue.entryAt(index), queue.count, queue.key())) {
                        is SleeveSync.Rest.Seek -> queue.seekTo(rest.page)
                        is SleeveSync.Rest.Follow -> launch { follow(rest.index) }
                        SleeveSync.Rest.Stay -> Unit
                    }
                }
            }
        }
    }
    HorizontalPager(pager, beyondViewportPageCount = 1, userScrollEnabled = userScrollEnabled,
        modifier = modifier.then(sleeveAccessibility(pager, userScrollEnabled) { sync.scrollStarted(queue.key()) }), pageContent = page)
}

/**
 * The pager's accessibility page actions, declared explicitly so each one arms a gesture before it scrolls. The pager
 * declares the same actions itself, but they scroll without any signal that the sleeve could tell from a follow, and an
 * action that interrupts a follow replaces its animation without the scroll ever stopping. These take precedence over the
 * pager's own (the outer modifier wins) and scroll the same way.
 */
@Composable
internal fun sleeveAccessibility(pager: PagerState, enabled: Boolean, armed: () -> Unit): Modifier {
    val scope = rememberCoroutineScope()
    val reversed = LocalLayoutDirection.current == LayoutDirection.Rtl
    val latestArmed by rememberUpdatedState(armed)
    fun toward(direction: Int): Boolean {
        val target = pager.currentPage + direction
        if (direction == 0 || target < 0 || target >= pager.pageCount) return false
        latestArmed()
        scope.launch { pager.animateScrollToPage(target) }
        return true
    }
    return if (!enabled) Modifier else Modifier.semantics {
        pageLeft { toward(if (reversed) 1 else -1) }
        pageRight { toward(if (reversed) -1 else 1) }
        scrollBy { x, _ -> toward((if (reversed) -x else x).let { if (it > 0f) 1 else if (it < 0f) -1 else 0 }) }
    }
}
