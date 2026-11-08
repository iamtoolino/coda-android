package dev.coda.core.playback

/**
 * Whether the start of a load or seek still has to be settled ([PositionTracker.settle]). Armed by the load or seek,
 * cleared by a stop, and taken once at the player's first state that is ready **and playing**: a paused ready state (a
 * cold start restores the queue paused) keeps it armed, so an unseekable source's position 0 does not replace the saved
 * resume point until the audio really starts there. Not thread-safe: the engine uses it on its player's thread.
 */
class PendingStart {
    var armed = false
        private set
    fun arm() { armed = true }
    fun clear() { armed = false }
    /** True (and cleared) when the start settles now. */
    fun take(ready: Boolean, playing: Boolean): Boolean {
        if (!armed || !ready || !playing) return false
        armed = false
        return true
    }
}
