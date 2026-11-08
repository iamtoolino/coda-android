package dev.coda.core.playback

/**
 * Makes an Opus decoder run start audibly at exactly its seek target (RFC 7845 §4.6).
 *
 * The media period starts every Opus read [PRE_ROLL_US] before the target ([preRollStartUs]), so the
 * decoder runs through real audio before the target and has converged there. The
 * renderer still resets to the target itself and skips every output labelled before it (decode-only).
 * Per decoder run (codec start or flush), until the first output the codec itself labels at or after the
 * target, this decides
 * for each output, from its codec label and [OpusOutputClock]'s correction to packet (granule) time:
 * - wholly before the target: unchanged, so the renderer skips it;
 * - holding the target: drop the frames before it and label the rest with the target itself, so the
 *   first audible frame is the target frame and the position starts at the target;
 * - at or after the target but labelled before it (a decoder that labels early by its discard): label
 *   it with its packet time, so the renderer keeps it.
 * A relabelled output carries no further correction ([Head.shiftUs] 0).
 */
class OpusSeekHead(private val sampleRate: Int = 48_000) {
    private var pending = false

    /** How to hand one output on: drop [dropFrames] leading frames, label it [presentationTimeUs], correct by [shiftUs]. */
    data class Head(val dropFrames: Int, val presentationTimeUs: Long, val shiftUs: Long)

    /** A codec start or flush: the next outputs belong to a new run that started before its target. */
    fun restart() { pending = true }

    /**
     * [targetUs] is the renderer's last reset position, [presentationTimeUs] the codec's label, [shiftUs]
     * the clock's correction for this output, all in renderer time.
     */
    fun onOutput(targetUs: Long, presentationTimeUs: Long, shiftUs: Long, frames: Int): Head {
        val unchanged = Head(0, presentationTimeUs, shiftUs)
        if (!pending) return unchanged
        if (presentationTimeUs >= targetUs) { pending = false; return unchanged }
        val startUs = presentationTimeUs + shiftUs
        if (startUs >= targetUs) return Head(0, startUs, 0)
        val drop = Math.round((targetUs - startUs) * sampleRate / 1e6)
        if (drop >= frames) return unchanged // Decode-only pre-roll; the next output may still hold the target.
        return Head(drop.toInt(), targetUs, 0)
    }

    companion object {
        /**
         * RFC 7845 §4.6 asks for at least 80 ms (3840 samples): enough to converge audibly, not bit for bit.
         * Measured on c2.android.opus.decoder: with 80-100 ms the output still differed from an
         * uninterrupted decode (up to 1184 of 32768) until about 375 ms after decoding started; with 600 ms
         * every seek was bit-exact. Decoding 600 ms costs a few milliseconds.
         */
        const val PRE_ROLL_US = 600_000L

        /** Where the media period starts reading for a seek to [positionUs]; the stream start at most. */
        fun preRollStartUs(positionUs: Long) = (positionUs - PRE_ROLL_US).coerceAtLeast(0)
    }
}
