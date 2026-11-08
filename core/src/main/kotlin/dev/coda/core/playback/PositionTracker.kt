package dev.coda.core.playback

/**
 * The position an engine reports between its own samples. The player is sampled every 100 ms and the
 * value extrapolated with the wall clock in between; Media3's audio position is not smooth (it holds for 100-250 ms and
 * then catches up, and at the start of every play segment it trails the wall clock by the output start latency), so the
 * raw sample often lands behind what was reported.
 *
 * The reported value is a free-running clock: it advances with the wall clock while the player plays and follows the
 * audio sample only at a bounded rate ([SLEW]). Two reasons, both about what a MediaController does with it:
 * a controller extrapolates with the wall clock from the position it was last sent and re-anchors on the session's
 * position every 3 s. A value that holds or lags the wall clock at an anchor is a step backwards on screen
 * (the first re-anchor after a start was always 125-360 ms back, the start latency); a value that runs at
 * the wall clock rate can only differ from the controller's own extrapolation by [SLEW] x elapsed time, so the
 * anchors are invisible (at most 30 ms per 3 s). The price is that the shown position leads the audible one by up to the
 * start latency (a few hundred ms at most, decaying at [SLEW]) in the first seconds of a segment.
 *
 * Within one play segment the value never decreases (it advances at least 1 - [SLEW] per ms). A segment ends on a
 * [rebase] (seek, load, stop, position discontinuity), when the sample belongs to another [item], or when the audio is
 * more than [JUMP_MS] away from what was reported for longer than [GLITCH_MS]; a shorter disagreement is a glitch of the audio
 * clock and is not shown.
 * Thread-safe.
 */
class PositionTracker {
    private var position = 0L
    private var sampledAt = 0L
    private var advancing = false
    private var durationMs: Long? = null
    private var item: Any? = null
    private var reported: Double? = null
    private var reportedAt = 0L
    private var disagreeingSince: Long? = null
    private var durationPassed = false

    /**
     * Records what the player says at [nowMs]. [durationMs] is its duration estimate (null: unknown).
     * [rebase] starts a new segment: the position is where it is now, whatever was reported before.
     */
    @Synchronized
    fun sample(positionMs: Long, nowMs: Long, advancing: Boolean, durationMs: Long?, item: Any?, rebase: Boolean = false) {
        if (reported != null) positionMs(nowMs) // settle the free-running clock under the old state before it changes
        if (item != this.item) durationPassed = false
        val fresh = rebase || item != this.item
        this.item = item
        position = positionMs.coerceAtLeast(0)
        if (fresh) { reported = position.toDouble(); reportedAt = nowMs; disagreeingSince = null }
        sampledAt = nowMs
        this.advancing = advancing
        this.durationMs = durationMs?.takeIf { it >= 0 }
        // Playback got beyond the duration it was told: that is an estimate and not the end of the track.
        if (this.durationMs?.let { position > it } == true) durationPassed = true
    }

    /**
     * The player settled where a load or seek really starts (its first ready state after it). A sample more than
     * [JUMP_MS] away from what was reported is no clock glitch but a start the source could not honour (Navidrome's
     * fresh Opus transcode cannot be seeked, so it plays from its beginning): it is believed at once, as a [rebase],
     * instead of showing the requested position for [GLITCH_MS] more. A closer sample is an ordinary [sample].
     */
    @Synchronized
    fun settle(positionMs: Long, nowMs: Long, advancing: Boolean, durationMs: Long?, item: Any?) {
        val shown = if (reported != null) positionMs(nowMs) else null
        sample(positionMs, nowMs, advancing, durationMs, item,
            rebase = shown != null && Math.abs(positionMs.coerceAtLeast(0) - shown) > JUMP_MS)
    }

    @Synchronized
    fun positionMs(nowMs: Long): Long {
        var target = position + if (advancing) (nowMs - sampledAt).coerceAtLeast(0) else 0
        // The end of the track is the only place extrapolation may not go on; never against an estimate already passed.
        val end = durationMs?.takeIf { !durationPassed }
        end?.let { target = target.coerceAtMost(it) }
        val last = reported
        var value = if (last == null) target.toDouble() else if (!advancing) {
            last // Paused or buffering: the shown position stands still; the offset to the audio is corrected once it plays again.
        } else {
            val dt = (nowMs - reportedAt).coerceAtLeast(0).toDouble()
            val free = last + dt
            val error = target - free
            if (Math.abs(error) <= JUMP_MS) { disagreeingSince = null; free + error.coerceIn(-dt * SLEW, dt * SLEW_UP) }
            else {
                // Every real move of the position (seek, load, transition, stop) arrives as a rebase, so a sample more than
                // JUMP_MS away without one is a clock glitch of the audio path (ExoPlayer's position once went 2.0 s
                // back with no seek). It is not shown at once; if it lasts, the audio really is there and it is accepted.
                val since = disagreeingSince ?: nowMs.also { disagreeingSince = it }
                if (nowMs - since >= GLITCH_MS) { disagreeingSince = null; target.toDouble() } else free
            }
        }
        end?.let { value = value.coerceAtMost(it.toDouble()) }
        if (!advancing) disagreeingSince = null
        reported = value
        reportedAt = nowMs
        return Math.round(value)
    }

    companion object {
        /** A sample farther than this from the reported value is not jitter. */
        const val JUMP_MS = 1_000L
        /** How long such a disagreement must last (without a rebase) before the audio position is believed. */
        const val GLITCH_MS = 2_000L
        /** How fast the reported clock may fall behind the wall clock to follow the audio: 1 % = 30 ms per controller re-anchor. */
        const val SLEW = 0.01
        /** How fast it may run ahead when the audio is ahead of it (a stop of the clock, a long hold): a forward step is no jump back. */
        const val SLEW_UP = 0.05
    }
}
