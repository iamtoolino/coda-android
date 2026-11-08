package dev.coda.android.ui.queue

/**
 * Decides whether a release in the queue is a tap or the end of a long press (2026-10-26: a long press on a
 * queue track must never start playback). Compose's `clickable` fires on any release in place, however long the
 * press lasted, while the list owns long-press dragging. A press at or beyond three quarters of the platform
 * long-press timeout counts as a long press (near-long presses too: the drag may not have started yet) and must not
 * trigger the tap action (seek + play).
 *
 * Feed it the pointer events from an earlier pass than the click handler ([onDown] / [onUp]), then ask
 * [shouldRunTap] from the click handler.
 */
class QueueTapGuard(private val longPressTimeoutMillis: () -> Long) {
    private var downAt = -1L
    private var longPress = false

    fun onDown(uptimeMillis: Long) { downAt = uptimeMillis; longPress = false }

    fun onUp(uptimeMillis: Long) {
        longPress = downAt >= 0 && uptimeMillis - downAt >= longPressTimeoutMillis()
    }

    fun onCancel() { downAt = -1L; longPress = false }

    /** Call from the click handler; consumes the press. */
    fun shouldRunTap(): Boolean {
        val run = !longPress
        downAt = -1L
        longPress = false
        return run
    }

    companion object {
        /** The album header's own, earlier cutoff (3/4 of the timeout); track rows use the full timeout via the guard. */
        fun isAlbumTap(pressMillis: Long, longPressTimeoutMillis: Long): Boolean = pressMillis < longPressTimeoutMillis * 3 / 4
    }
}
