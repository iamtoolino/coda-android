package dev.coda.android.ui.player

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** No queue item (an unset slot of [MiniSwipe]). */
internal const val NoTrack = -1

/**
 * Whether a horizontal move of [dx] px (screen x, positive = right) goes to the next track. A swipe toward the
 * start edge is "next" in reading order: right-to-left in LTR, left-to-right in RTL, as the Now Playing sleeve pager
 * (a HorizontalPager mirrors itself) pages.
 */
internal fun swipesToNext(dx: Float, rtl: Boolean): Boolean = if (rtl) dx > 0f else dx < 0f

/** A queue item's occurrence: its queue entry id, else (an item from elsewhere without one) its media id. */
internal fun occurrenceKey(mediaId: String?, entryId: String?): String? = entryId ?: mediaId

/**
 * What a swipe pinned when it began, checked on release. A skip is relative to the playing item, so it only
 * goes ahead while the player still plays the pinned occurrence at its index and the neighbour the move pulled in is
 * still the pinned one; a replacement elsewhere (even the same song at the same index) makes the swipe spring back.
 */
internal class MiniPin(val current: Int, val currentId: String?, val next: Int, val nextId: String?, val previous: Int, val previousId: String?) {
    fun stillCurrent(playing: Int, occurrenceAt: (Int) -> String?, toNext: Boolean): Boolean {
        if (playing != current || currentId == null || occurrenceAt(current) != currentId) return false
        val (index, id) = if (toNext) next to nextId else previous to previousId
        return index == NoTrack || occurrenceAt(index) == id
    }
}

/**
 * A horizontal swipe on the mini player. While the finger is down the track info and cover follow it ([offset]) and
 * the neighbouring track slides in from the side the finger is pulling toward; on release past the threshold the swipe
 * commits (the neighbour finishes sliding in and the player skips), otherwise everything springs back.
 *
 * The tracks shown are pinned for the whole swipe ([current], [next], [previous] are queue indices read when it began), so
 * a change made elsewhere meanwhile, or the player's own skip landing mid-animation, never swaps the content under the
 * finger. [offset] is read only in graphics layers, never in composition. Main thread only (it reads the MediaController).
 */
@Stable
internal class MiniSwipe {
    /** Screen-x offset in px of the pinned current track; its neighbours sit one [width] to either side of it. */
    var offset by mutableFloatStateOf(0f); private set
    /** The queue index of the track the swipe started on; [NoTrack] when no swipe is held or settling. */
    var current by mutableIntStateOf(NoTrack); private set
    var next by mutableIntStateOf(NoTrack); private set
    var previous by mutableIntStateOf(NoTrack); private set
    /** The pinned current track's media id: with [current], what the player must still play for a commit to skip from it. */
    var currentId: String? = null; private set
    /** The pinned neighbours' occurrences. */
    private var nextId: String? = null
    private var previousId: String? = null
    /** The tracks of a commit in flight: the one sliding in and where it sits relative to the pinned current one. */
    private var entering = NoTrack
    private var enteringShift = 0f
    /** Where the finger's move starts: 0, or the offset carried over from a swipe that was still settling. */
    private var base = 0f
    private var settling: Job? = null
    /** Bumped by every swipe and settle: a settle that is no longer the latest must not clear what a newer swipe set up. */
    private var generation = 0
    /** Width in px of the area the tracks slide in (set by the layout). */
    var width = 0f
    /** A swipe is on screen: held, springing back or sliding to the new track. A new one waits for it to settle. */
    val active get() = current != NoTrack
    /** The finger is still down on a swipe. */
    var holding = false; private set

    /**
     * A finger went down on a swipe. It may start at any time, also while the last one is still settling (a rapid double
     * flick): that settle is cancelled and the new swipe carries on from what is on screen, so nothing pops. The tracks
     * are the player's own, read now; if a committed skip is not reported yet, the new swipe starts from the track that
     * was pinned (a one-off jump back) rather than from a state it cannot trust.
     */
    fun begin(current: Int, next: Int, previous: Int, currentId: String?, nextId: String? = null, previousId: String? = null) {
        settling?.cancel(); settling = null
        generation++
        val carry = when {
            !active -> 0f
            entering != NoTrack -> if (entering == current) offset + enteringShift else 0f
            this.current == current -> offset
            else -> 0f
        }
        Snapshot.withMutableSnapshot {
            this.current = current; this.next = next; this.previous = previous; offset = carry
        }
        this.currentId = currentId; this.nextId = nextId; this.previousId = previousId
        base = carry; entering = NoTrack
        holding = true
    }

    /** The occurrences this swipe pinned, for the release's [MiniPin.stillCurrent]. */
    fun pin() = MiniPin(current, currentId, next, nextId, previous, previousId)

    /** The finger is up: [settle] follows. Synchronous, so a gesture that ends later knows the release was handled. */
    fun letGo() { holding = false }

    /** The finger moved [dx] px since it went down. Dragging toward a side with no neighbour is resisted. */
    fun drag(dx: Float, rtl: Boolean) {
        val neighbour = if (swipesToNext(dx, rtl)) next else previous
        val limit = if (width > 0f) width else Float.MAX_VALUE
        offset = (base + if (neighbour != NoTrack) dx else dx * EdgeResistance).coerceIn(-limit, limit)
    }

    /**
     * The finger lifted (or the gesture was cancelled) after a total move of [dx]. [commit]: skip to the neighbour that the
     * move pulled in, if there is one and [stillCurrent] (the player still plays the pinned track: a skip is relative to the
     * playing track, so after an auto-advance or a change made elsewhere it would land one too far, and the swipe springs
     * back instead); [seek] skips and [reached] tells when the player plays the item at an index.
     * Animated like the sleeve's follow spring; with [reduceMotion] it jumps.
     */
    suspend fun settle(commit: Boolean, dx: Float, rtl: Boolean, reduceMotion: Boolean, stillCurrent: () -> Boolean,
        seek: () -> Unit, reached: (Int) -> Boolean) {
        val mine = ++generation
        settling = currentCoroutineContext()[Job]
        val toNext = swipesToNext(dx, rtl)
        val target = if (toNext) next else previous
        try {
            if (commit && target != NoTrack && stillCurrent()) {
                seek()
                entering = target
                enteringShift = (if (toNext == !rtl) 1f else -1f) * width
                moveTo(if (dx < 0f) -width else width, reduceMotion)
                // Hold the slid-in neighbour until the player reports it, so the swap to live data is invisible.
                withTimeoutOrNull(ArrivalTimeoutMs) { while (!reached(target)) delay(16) }
            } else moveTo(0f, reduceMotion)
        } finally {
            if (mine == generation) { // not when a newer swipe has taken over
                settling = null; entering = NoTrack
                Snapshot.withMutableSnapshot { offset = 0f; current = NoTrack; next = NoTrack; previous = NoTrack }
            }
        }
    }

    private suspend fun moveTo(target: Float, reduceMotion: Boolean) {
        if (reduceMotion) offset = target
        else animate(offset, target, animationSpec = spring(1f, 400f)) { value, _ -> offset = value }
    }

    companion object {
        /** How much of the finger's move an end-of-queue drag follows. */
        const val EdgeResistance = .25f
        /** The longest the committed neighbour waits for the player to report the skip. */
        const val ArrivalTimeoutMs = 700L
    }
}
