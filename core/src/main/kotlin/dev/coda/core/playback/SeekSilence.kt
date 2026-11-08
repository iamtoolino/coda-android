package dev.coda.core.playback

/**
 * The engine's one volume state machine for the player's output volume. It saves the level it finds,
 * moves the volume in small steps (the platform mixer smooths each step, so there is no click) and always puts back
 * exactly that level. ReplayGain is applied in the PCM, not here, so it is untouched.
 *
 * - Seek silence ([begin]): while a seek inside an uncached transcode waits for its reopened stream
 *   ([LatestReopen]), the old audio must not play on. Faded to 0 within [RAMP_MS] and held. It works on the player
 *   volume, not on the PCM: the audio track already holds a few hundred ms of old audio (more than the debounce), which
 *   only the track volume reaches. The engine restores the level right after the call that drops that audio (the
 *   reopen's new source, a player seek, stop, load), so the volume change runs after the flush on the player's thread
 *   and the new position is heard from its first frame.
 * - Pause fade ([fadeOut]): down to 0 over [FADE_MS] while the player keeps playing, then the engine's pause, then
 *   the level back (queued after the pause on the player's thread, so not heard). [fadeIn] during it cancels the pause.
 * - Resume fade ([fadeIn]): from silence (or from where a fade-out was) up to the level over [FADE_MS].
 * - Seek fade ([seek]): every seek while audio plays (a local file, a local seek in a stream, a reopen, a
 *   continue-from-cache) fades out like the seek silence, runs the seek once silent ([SEEK_AT_MS], after the volume step
 *   to 0 reached the mixer), holds 0 until the player plays the new position ([landed]), then fades in over
 *   [SEEK_FADE_IN_MS]. Seeks while it fades out replace the pending one (one seek, one fade per final position); a seek
 *   during the fade-in fades down from where it is. The seek itself is unchanged (sample-exact); only the volume moves.
 *
 * Every other way out ([end], [Exit]) restores the level at once and drops a pending pause: the caller sets the player
 * state itself (load, stop, error, end). [completePause] runs a pending pause now (a seek, an automatic track change).
 * Steps run on the main thread via [post]; a busy main thread stretches a fade a little, never the restored level.
 */
class SeekSilence(
    private val volume: () -> Float,
    private val setVolume: (Float) -> Unit,
    private val post: (delayMs: Long, block: () -> Unit) -> () -> Unit,
) {
    enum class Exit { REOPENED, FAILED, SUPERSEDED, STOPPED, PAUSED, TRACK_CHANGED, LOADED, ENDED }
    private enum class Mode { SILENT, FADING_OUT, FADING_IN, SEEK_OUT, SEEK_HELD }

    private var saved: Float? = null
    private var mode: Mode? = null
    private var pendingPause: (() -> Unit)? = null
    private var pendingSeek: (() -> Unit)? = null
    /** The target of a seek that waits for its fade-out (the position to show meanwhile). */
    var pendingSeekMs: Long? = null
        private set
    private val ramp = mutableListOf<() -> Unit>()
    val active: Boolean get() = saved != null
    /** A faded pause is under way: the player still plays, but it counts as paused. */
    val pausing: Boolean get() = mode == Mode.FADING_OUT

    /** A reopen seek was accepted while the audio is playing (or the player resumed while one still waits). */
    fun begin() {
        if (mode == Mode.SILENT) return
        val from = volume()
        if (saved == null) saved = from
        cancelRamp(); pendingPause = null; dropSeek() // A reopen seek supersedes a local one still fading out.
        mode = Mode.SILENT
        RAMP.forEachIndexed { step, factor ->
            if (step == 0) setVolume(from * factor) else ramp += post(step * RAMP_STEP_MS) { setVolume(from * factor) }
        }
    }

    /** Fade out, then [pause], then the exact level. During the seek silence nothing is heard: pause at once. */
    fun fadeOut(pause: () -> Unit) {
        when (mode) {
            Mode.SILENT, Mode.SEEK_OUT, Mode.SEEK_HELD -> { completeSeek(); pause(); end(Exit.PAUSED); return }
            Mode.FADING_OUT -> return
            Mode.FADING_IN, null -> Unit
        }
        if (saved == null) saved = volume()
        cancelRamp()
        mode = Mode.FADING_OUT
        pendingPause = pause
        fade(to = 0f, FADE_MS) { completePause() }
    }

    /** Before the player plays again. Not during the seek silence: that stays silent until the reopen. */
    fun fadeIn() {
        if (mode == Mode.SILENT || mode == Mode.SEEK_OUT || mode == Mode.SEEK_HELD) return
        val level = saved ?: volume().also { saved = it; setVolume(0f) }
        cancelRamp(); pendingPause = null
        mode = Mode.FADING_IN
        fade(to = level, FADE_MS) { saved = null; mode = null } // The last step set the level exactly.
    }

    /**
     * A seek while audio plays. Fades out (as [begin]), runs [run] once silent and holds 0 until [landed]. While
     * silent already (a reopen waits, or a seek is held) it runs at once. A seek still waiting is replaced by this one.
     */
    fun seek(targetMs: Long, run: () -> Unit) {
        when (mode) {
            Mode.SEEK_OUT -> { pendingSeek = run; pendingSeekMs = targetMs; return }
            Mode.SILENT, Mode.SEEK_HELD -> { if (saved == null) saved = volume(); cancelRamp(); mode = Mode.SEEK_HELD; run(); return }
            Mode.FADING_OUT -> completePause() // The engine pauses first; not expected here.
            Mode.FADING_IN, null -> Unit
        }
        val from = volume()
        if (saved == null) saved = from
        cancelRamp(); pendingPause = null
        mode = Mode.SEEK_OUT
        pendingSeek = run; pendingSeekMs = targetMs
        RAMP.forEachIndexed { step, factor ->
            if (step == 0) setVolume(from * factor) else ramp += post(step * RAMP_STEP_MS) { setVolume(from * factor) }
        }
        ramp += post(SEEK_AT_MS) { completeSeek() }
    }

    /** A seek still fading out runs now (and the silence holds). No-op otherwise. */
    fun completeSeek() {
        val run = pendingSeek ?: return
        dropSeek(); cancelRamp()
        mode = Mode.SEEK_HELD
        setVolume(0f) // If the fade-out was cut short: the seek's flush must not be heard at a level.
        run()
    }

    /** The player plays again after a held seek: fade in to exactly the saved level. No-op otherwise. */
    fun landed() {
        if (mode != Mode.SEEK_HELD) return
        val level = saved ?: return
        mode = Mode.FADING_IN
        fade(to = level, SEEK_FADE_IN_MS) { saved = null; mode = null }
    }

    /** A faded pause still under way pauses now, then the level comes back. No-op otherwise. */
    fun completePause() {
        val pause = pendingPause ?: return
        pendingPause = null
        pause()
        end(Exit.PAUSED)
    }

    /** The level from before, exactly; a pending pause is dropped. No-op when nothing is under way. */
    fun end(@Suppress("UNUSED_PARAMETER") exit: Exit) {
        val level = saved ?: return
        cancelRamp()
        saved = null; mode = null; pendingPause = null; dropSeek()
        setVolume(level)
    }

    /** The player is gone: stop the ramp, touch nothing. */
    fun release() { cancelRamp(); saved = null; mode = null; pendingPause = null; dropSeek() }
    private fun dropSeek() { pendingSeek = null; pendingSeekMs = null }

    /** Linear steps from the current volume to [to], [RAMP_STEP_MS] apart, ending exactly on [to] after [ms]. */
    private fun fade(to: Float, ms: Long, done: () -> Unit) {
        val from = volume()
        val steps = (ms / RAMP_STEP_MS).toInt()
        for (step in 1..steps) ramp += post(step * RAMP_STEP_MS) {
            if (step == steps) { setVolume(to); ramp.clear(); done() } else setVolume(from + (to - from) * step / steps)
        }
    }

    private fun cancelRamp() { ramp.forEach { it() }; ramp.clear() }

    companion object {
        /** Fractions of the level, [RAMP_STEP_MS] apart; the last one is silence. */
        private val RAMP = floatArrayOf(0.5f, 0.2f, 0f)
        const val RAMP_STEP_MS = 10L
        /** Silence is reached this long after [begin]. */
        val RAMP_MS = (RAMP.size - 1) * RAMP_STEP_MS
        /** Length of the pause and resume fades. */
        const val FADE_MS = 150L
        /** A seek runs one step after the volume reached 0 (the mixer has applied the 0 by then). */
        val SEEK_AT_MS = RAMP_MS + RAMP_STEP_MS
        /** The fade-in after a seek, from when the new position plays; short, so a seek still feels instant. */
        const val SEEK_FADE_IN_MS = 60L
    }
}
