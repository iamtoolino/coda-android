package dev.coda.core.playback

import kotlin.math.abs

/**
 * Tells how an Opus decoder labels its output after it discards samples (ID3), observed per decoder
 * run (codec start or flush) from the decoder's own input and output, never from a device or module
 * version.
 *
 * After a codec start a decoder discards the pre-skip; after a flush, the seek pre-roll. Ogg input
 * timestamps count granules, including the pre-skip. Two labellings exist:
 * - **packet time** (`c2.android.opus.decoder`, swcodec 360527520): each output keeps the timestamp of
 *   the packet it came from. A packet that lost only part of its samples keeps that timestamp too, so
 *   the next output jumps by a whole packet.
 * - **run time** (same decoder, swcodec 372042380): outputs are labelled contiguously from the run's
 *   first input timestamp, as if nothing had been discarded, so every timestamp is early by the
 *   discarded duration.
 *
 * The first output of a run tells them apart when it is labelled later than the first input (packet
 * time: whole packets were discarded). Otherwise it holds the first input's timestamp under both, and
 * the second output decides: contiguous with the first means run time, a gap means packet time. Until
 * then, the first output's timestamp is early by the discard under both labellings.
 *
 * [onOutput] returns how much to add to an output timestamp to get its packet (granule) time.
 */
class OpusOutputClock(private val sampleRate: Int = 48_000) {
    private var discardUs = 0L
    private var firstInputUs = UNSET
    private var firstOutputUs = UNSET
    private var firstOutputEndUs = UNSET

    /** Null until decided for the current run. */
    var runTime: Boolean? = null
        private set

    /** A codec start (discards the pre-skip) or flush (discards the seek pre-roll). */
    fun restart(discardFrames: Int) {
        discardUs = discardFrames * 1_000_000L / sampleRate
        firstInputUs = UNSET
        firstOutputUs = UNSET
        firstOutputEndUs = UNSET
        runTime = null
    }

    /** Every queued access unit except codec configuration. */
    fun onInput(presentationTimeUs: Long) {
        if (firstInputUs == UNSET) firstInputUs = presentationTimeUs
    }

    /** Every non-empty output buffer, including those the renderer then skips as decode-only. */
    fun onOutput(presentationTimeUs: Long, frames: Int): Long {
        if (firstOutputUs == UNSET) {
            firstOutputUs = presentationTimeUs
            firstOutputEndUs = presentationTimeUs + frames * 1_000_000L / sampleRate
            if (firstInputUs != UNSET && presentationTimeUs != firstInputUs) runTime = false
            return if (runTime == false) 0 else discardUs
        }
        if (runTime == null) runTime = abs(presentationTimeUs - firstOutputEndUs) <= 1
        return if (runTime == true) discardUs else 0
    }

    private companion object { const val UNSET = Long.MIN_VALUE }
}
