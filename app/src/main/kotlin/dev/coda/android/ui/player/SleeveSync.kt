package dev.coda.android.ui.player

/**
 * Links the Now Playing sleeve pager to the queue (main thread only).
 *
 * A swipe seeks only if it began after the last change made elsewhere: a different playing item (by queue index or by
 * queue-entry identity, so removing or replacing the playing entry counts even when its successor takes the same index)
 * or a different queue (by [queue key][scrollStarted]). Any other mismatch at rest (a follow scroll that a held drag
 * rejected or cut short, a gesture that predates such a change) scrolls the sleeve back to the playing item instead of
 * seeking, so the sleeve can never undo a change made elsewhere.
 *
 * A gesture is owned from its start (a drag, or an explicit accessibility page action) until the sleeve comes to rest,
 * wherever that is: every [atRest] ends it. The caller reports a rest only once the sleeve has stayed at rest for a
 * short settle time (see [SleevePager]), so the frame between a drag and its fling, which can look like a rest on any
 * page, never reaches [atRest] and never seeks or ends the gesture early.
 */
internal class SleeveSync(index: Int, entry: String?) {
    sealed interface Rest {
        /** The sleeve already shows the playing item (or there's nothing valid to do). */
        data object Stay : Rest
        /** A fresh swipe came to rest here: play this item. */
        data class Seek(val page: Int) : Rest
        /** Scroll the sleeve back to the playing item without seeking. */
        data class Follow(val index: Int) : Rest
    }

    /** The playing item the sleeve last knew about: the controller's, or the one the sleeve just asked for. */
    private var known = index
    private var knownEntry = entry
    /** Counts changes made elsewhere. */
    private var selection = 0
    /** [selection] when the latest user or accessibility scroll began; -1 when none is pending. */
    private var gesture = -1
    /** The queue key when that scroll began. */
    private var gestureQueue = 0

    /** A user drag or an accessibility page action began on the queue identified by [queueKey]. */
    fun scrollStarted(queueKey: Int) { gesture = selection; gestureQueue = queueKey }

    /** The controller plays queue entry [entry] at [index]. True for a change made elsewhere, which the sleeve should follow. */
    fun indexChanged(index: Int, entry: String?): Boolean {
        if (index == known && entry == knownEntry) return false
        known = index; knownEntry = entry; selection++
        return true
    }

    /**
     * The pager has settled on [page] (showing [pageEntry]) while the controller plays [entry] at [index] of [count],
     * in the queue identified by [queueKey]. Ends the gesture, whatever the outcome: a drag released on the playing
     * page leaves nothing armed for a later scroll that no drag or accessibility action started.
     */
    fun atRest(page: Int, pageEntry: String?, index: Int, entry: String?, count: Int, queueKey: Int): Rest {
        indexChanged(index, entry)
        val fresh = gesture >= 0 && gesture == selection && gestureQueue == queueKey
        gesture = -1
        return when {
            page == index || index !in 0 until count -> Rest.Stay
            fresh && page in 0 until count -> { known = page; knownEntry = pageEntry; Rest.Seek(page) }
            else -> Rest.Follow(index)
        }
    }
}
