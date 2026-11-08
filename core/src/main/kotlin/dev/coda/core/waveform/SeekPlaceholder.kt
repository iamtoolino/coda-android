package dev.coda.core.waveform

import kotlin.math.*

/** One Voice's low tide. Drifting bars shown until the real waveform arrives, then unfolded from the playhead. */
object SeekPlaceholder {
    fun height(index: Int, seconds: Float): Float =
        (7 + 2.8 * sin(index * .39 - seconds * 2.4) + .6 * sin(index * .83 - seconds * 1.3)).toFloat()

    /**
     * The tide's frame loop runs only while playing a track whose waveform is pending, with motion on, on a Now
     * Playing that is shown with the screen on ([onScreen], see `BackgroundWorkPolicy.waveformMotion`). Otherwise the
     * loop is not running at all (no frames), and the bars stay where they are.
     */
    fun tideRuns(playing: Boolean, waveformReady: Boolean, reduceMotion: Boolean, onScreen: Boolean): Boolean =
        playing && !waveformReady && !reduceMotion && onScreen
    fun unfold(index: Int, playhead: Float, arrival: Float, bars: Int = 44): Float {
        val head = (playhead * (bars - 1)).roundToInt()
        val distance = abs(index - head).toFloat() / (bars - 1)
        val p = ((arrival - distance * .5f) / .5f).coerceIn(0f, 1f)
        return 1 - (1 - p).pow(4)
    }
}
