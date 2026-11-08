package dev.coda.core.playback

/**
 * A seek inside an uncached Opus transcode (chunked, no length, Range ignored).
 *
 * What Media3 can do locally: its progressive period treats every seek into an unseekable stream as a seek to 0
 * (`ProgressiveMediaPeriod.seekToUs` / `getAdjustedSeekPositionUs`), and 0 is gone from the buffer once playback has
 * read past it (the back buffer is 0), so on its own it re-reads the stream from its start. What the sample queue still
 * holds is everything from the renderer's read position up to the loaded edge, which for a fresh transcode is usually
 * the whole rest of the song (50 s of buffer). The engine serves a forward seek into that part itself, by skipping the
 * queue to the pre-roll before the target ([Plan.Local], no request). Anything else (backwards, or past what is
 * loaded) is opened again at the target's whole second with `timeOffset` ([Plan.Reopen]) when the server supports it.
 */
object TranscodeSeek {
    /** The target must lie this far past what the renderer has read, so the queue still holds its pre-roll. */
    const val READ_MARGIN_US = 200_000L
    /** Reopens wait this long for a later seek (a drag from a system controller, repeated taps): one request per burst. */
    const val REOPEN_DEBOUNCE_MS = 200L

    sealed interface Plan {
        /** Skip the buffered stream to the target; no request. */
        data object Local : Plan
        /** Open the item again at [targetMs] (`timeOffset` = its whole second). */
        data class Reopen(val targetMs: Long) : Plan
        /** Hand the seek to the player as before (an unseekable stream then restarts at 0). */
        data object Player : Plan
    }

    /**
     * [readUs]: the last sample time the renderer read (stream time; [Long.MIN_VALUE] when nothing yet),
     * [bufferedUs]: the loaded edge (stream time), or null when the whole stream is loaded.
     */
    fun buffered(targetUs: Long, preRollStartUs: Long, readUs: Long, bufferedUs: Long?): Boolean =
        targetUs >= 0 && preRollStartUs >= (if (readUs == Long.MIN_VALUE) 0 else readUs + READ_MARGIN_US) &&
            (bufferedUs == null || targetUs < bufferedUs)

    /**
     * [inBuffer]: [buffered] for the target. [offsetStream]: the stream started (or will start) at `timeOffset`, so it
     * holds nothing before it and cannot serve the target otherwise. [serverSupportsOffset]: `transcodeOffset` known.
     */
    fun plan(targetMs: Long, durationMs: Long?, inBuffer: Boolean, offsetStream: Boolean, serverSupportsOffset: Boolean): Plan = when {
        inBuffer -> Plan.Local
        offsetStream || serverSupportsOffset -> Plan.Reopen(clamp(targetMs, durationMs))
        else -> Plan.Player
    }

    /** A reopen target inside the song: `timeOffset` must stay below its length (asking past the end is unverified). */
    fun clamp(targetMs: Long, durationMs: Long?): Long =
        if (durationMs != null && durationMs > 0) targetMs.coerceIn(0, durationMs - 1) else targetMs.coerceAtLeast(0)
}

/**
 * The latest of a burst of reopens, after [TranscodeSeek.REOPEN_DEBOUNCE_MS] without a newer one. [post] schedules a
 * block and returns its cancel. Not thread-safe: one thread (the engine's main thread).
 */
class LatestReopen(private val post: (delayMs: Long, block: () -> Unit) -> () -> Unit) {
    private var cancel: (() -> Unit)? = null
    /** The target waiting to be opened, if any (what the engine reports as its position meanwhile). */
    @Volatile var pendingMs: Long? = null
        private set

    fun request(targetMs: Long, open: (Long) -> Unit) {
        cancel?.invoke()
        pendingMs = targetMs
        cancel = post(TranscodeSeek.REOPEN_DEBOUNCE_MS) { cancel = null; pendingMs = null; open(targetMs) }
    }

    fun clear() { cancel?.invoke(); cancel = null; pendingMs = null }
}
