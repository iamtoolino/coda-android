package dev.coda.core.waveform

/**
 * The waveform's intro animation (bars grow out of the calculating tide) plays once for every new track, also
 * when the waveform is already cached. The gate remembers the track whose intro already ran, so re-composing or
 * re-opening Now Playing on the same track does not replay it, while any other track (including a return to an earlier
 * one) is new. Not thread-safe: used from the main thread only.
 */
class WaveformIntro {
    private var played: String? = null

    /** True once the intro already ran (or started) for [songId]; the bars then start fully drawn. */
    fun hasPlayed(songId: String): Boolean = songId.isNotEmpty() && played == songId

    /** True exactly once per new track: the caller starts the intro from the baseline. */
    fun begin(songId: String): Boolean {
        if (songId.isEmpty() || played == songId) return false
        played = songId
        return true
    }

    /**
     * Like [begin], but only while Now Playing is shown with the screen on; a track that changes while hidden or
     * with the screen off unfolds once it is shown (no frames before that).
     */
    fun beginIfShown(songId: String, onScreen: Boolean): Boolean = onScreen && begin(songId)

    companion object {
        const val DURATION_MS = 800
        /** Reduced motion (animator scale 0): a short fade instead of the unfold. */
        const val REDUCED_MOTION_MS = 150
        fun durationMs(reduceMotion: Boolean) = if (reduceMotion) REDUCED_MOTION_MS else DURATION_MS

        /** Where a track's bars start: fully drawn only for a track whose intro already ran and whose waveform is known. */
        fun initialArrival(waveformReady: Boolean, hasPlayed: Boolean): Float = if (waveformReady && hasPlayed) 1f else 0f
    }
}
