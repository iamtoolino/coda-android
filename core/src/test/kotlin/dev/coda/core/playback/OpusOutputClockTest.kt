package dev.coda.core.playback

import org.junit.Assert.*
import org.junit.Test

/**
 * ID3: both Opus decoder labellings, modelled on the outputs logged on emulator-5556
 * (`c2.android.opus.decoder` from swcodec 360527520 in ID2-5556, and from 372042380 in ID3).
 */
class OpusOutputClockTest {
    private val preSkip = 312
    private val seekPreRoll = 3840
    private val packet = 960
    private val finalGranule = 100_315L // Synthetic track 1: 100003 audible frames.

    private data class Output(val presentationTimeUs: Long, val frames: Int, val firstGranule: Long)

    private fun us(granule: Long) = granule * 1_000_000 / 48_000

    /** One decoder run from [firstGranule]: discards [discard] samples, then labels as configured. */
    private fun decode(firstGranule: Long, discard: Int, runTime: Boolean): List<Output> {
        val outputs = mutableListOf<Output>()
        var left = discard
        var emitted = 0L
        var granule = firstGranule
        while (granule < finalGranule) {
            val dropped = minOf(left, packet)
            left -= dropped
            val frames = packet - dropped
            if (frames > 0) {
                val time = if (runTime) us(firstGranule) + emitted * 1_000_000 / 48_000 else us(granule)
                outputs += Output(time, frames, granule + dropped)
                emitted += frames
            }
            granule += packet
        }
        return outputs
    }

    /**
     * Feeds a run through the clock as OpusCodecAdapter does, skips decode-only outputs before
     * [startUs] as the renderer does and caps like ItemAudioProcessor. Returns the emitted frame
     * count and the true audible frame of the first emitted sample.
     */
    private fun play(firstGranule: Long, discard: Int, runTime: Boolean, startUs: Long, preSkip: Int): Pair<Long, Long> {
        val audibleFrames = finalGranule - preSkip
        val clock = OpusOutputClock().apply { restart(discard); onInput(us(firstGranule)) }
        var emitted = 0L
        var firstAudible = -1L
        for (output in decode(firstGranule, discard, runTime)) {
            val shiftUs = clock.onOutput(output.presentationTimeUs, output.frames)
            if (output.presentationTimeUs < startUs) continue
            val truth = output.firstGranule - preSkip
            val positionUs = output.presentationTimeUs + shiftUs
            val framesOut = if (positionUs <= 0) 0 else (Math.round(positionUs * 48_000 / 1e6) - preSkip).coerceAtLeast(0)
            assertEquals("audible frame of the output at ${output.presentationTimeUs}", maxOf(truth, 0), framesOut)
            if (firstAudible < 0) firstAudible = truth
            emitted += (audibleFrames - framesOut).coerceIn(0, output.frames.toLong())
        }
        return emitted to firstAudible
    }

    private fun assertExact(firstGranule: Long, discard: Int, runTime: Boolean, startUs: Long, preSkip: Int = this.preSkip) {
        val (emitted, firstAudible) = play(firstGranule, discard, runTime, startUs, preSkip)
        assertEquals("frames until the final granule", finalGranule - preSkip - firstAudible, emitted)
    }

    @Test fun packetTimeDecoderFromStartResumeAndSeek() {
        assertExact(0, preSkip, runTime = false, startUs = 0)
        assertExact(23_040, preSkip, runTime = false, startUs = 500_000) // Resume: first packet decode-only.
        assertExact(24_000, preSkip, runTime = false, startUs = 500_000) // Short first buffer.
        assertExact(24_000, seekPreRoll, runTime = false, startUs = 500_000) // In-place seek flush.
        assertExact(23_040, seekPreRoll, runTime = false, startUs = 500_000)
    }

    @Test fun runTimeDecoderFromStartResumeAndSeek() {
        assertExact(0, preSkip, runTime = true, startUs = 0)
        assertExact(23_040, preSkip, runTime = true, startUs = 500_000)
        assertExact(24_000, preSkip, runTime = true, startUs = 500_000)
        assertExact(24_000, seekPreRoll, runTime = true, startUs = 500_000)
        assertExact(23_040, seekPreRoll, runTime = true, startUs = 500_000)
    }

    @Test fun detectionFollowsTheDecoderOutputPerRun() {
        val clock = OpusOutputClock()
        // swcodec 372042380 from the start: 648 frames at 0, then contiguous at 13.5 ms.
        clock.restart(preSkip); clock.onInput(0)
        assertEquals(6_500L, clock.onOutput(0, 648))
        assertEquals(6_500L, clock.onOutput(13_500, 960))
        assertEquals(true, clock.runTime)
        // The next run (a seek flush) is decided again: whole packets discarded, first output late.
        clock.restart(seekPreRoll); clock.onInput(480_000)
        assertEquals(0L, clock.onOutput(560_000, 960))
        assertEquals(false, clock.runTime)
        // swcodec 360527520 from the start: the short buffer keeps its packet time.
        clock.restart(preSkip); clock.onInput(0)
        assertEquals(6_500L, clock.onOutput(0, 648))
        assertEquals(0L, clock.onOutput(20_000, 960))
        assertEquals(false, clock.runTime)
    }

    @Test fun nothingDiscardedNeedsNoCorrection() {
        for (runTime in listOf(false, true)) assertExact(24_000, 0, runTime, startUs = 500_000)
    }

    @Test fun preSkipLongerThanAPacket() {
        for (runTime in listOf(false, true)) {
            assertExact(0, 2_000, runTime, startUs = 0, preSkip = 2_000)
            assertExact(0, 3_840, runTime, startUs = 0, preSkip = 3_840)
        }
    }
}
