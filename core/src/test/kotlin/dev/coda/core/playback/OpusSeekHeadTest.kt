package dev.coda.core.playback

import org.junit.Assert.*
import org.junit.Test

/**
 * Seek pre-roll and head trimming, on the decoder model of [OpusOutputClockTest]. A run starts at
 * the packet holding [OpusSeekHead.preRollStartUs], the decoder discards [discard] samples, the renderer
 * skips outputs labelled before the target and ItemAudioProcessor anchors each output on its corrected label.
 */
class OpusSeekHeadTest {
    private val preSkip = 312
    private val packet = 960
    private val finalGranule = 100_315L // Synthetic track 1: 100003 audible frames.
    private val offsetUs = 1_000_000_000_000L // Media3's first renderer offset.

    private fun us(granule: Long) = granule * 1_000_000 / 48_000

    private data class Output(val presentationTimeUs: Long, val frames: Int, val firstGranule: Long)

    private fun decode(firstGranule: Long, discard: Int, runTime: Boolean): List<Output> {
        val outputs = mutableListOf<Output>()
        var left = discard
        var emitted = 0L
        var granule = firstGranule
        while (granule < finalGranule) {
            val size = minOf(packet.toLong(), finalGranule - granule).toInt()
            val dropped = minOf(left, size)
            left -= dropped
            val frames = size - dropped
            if (frames > 0) {
                val time = if (runTime) us(firstGranule) + emitted * 1_000_000 / 48_000 else us(granule)
                outputs += Output(offsetUs + time, frames, granule + dropped)
                emitted += frames
            }
            granule += size
        }
        return outputs
    }

    /** The packet the media period starts at for a seek to [targetUs] (period time). */
    private fun firstPacket(targetUs: Long) = OpusSeekHead.preRollStartUs(targetUs) * 48_000 / 1_000_000 / packet * packet

    /** Plays a seek to [targetUs]; returns the true granule of every emitted frame, in order. */
    private fun seek(targetUs: Long, discard: Int, runTime: Boolean): List<Long> {
        val first = firstPacket(targetUs)
        val clock = OpusOutputClock().apply { restart(discard); onInput(offsetUs + us(first)) }
        val head = OpusSeekHead().apply { restart() }
        val reset = offsetUs + targetUs
        val granules = mutableListOf<Long>()
        for (output in decode(first, discard, runTime)) {
            val shift = clock.onOutput(output.presentationTimeUs, output.frames)
            val h = head.onOutput(reset, output.presentationTimeUs, shift, output.frames)
            if (h.presentationTimeUs < reset) continue // Decode-only.
            val positionUs = h.presentationTimeUs + h.shiftUs - offsetUs
            val framesOut = if (positionUs <= 0) 0 else (Math.round(positionUs * 48_000 / 1e6) - preSkip).coerceAtLeast(0)
            val truth = output.firstGranule + h.dropFrames
            assertEquals("audible frame of the output at ${output.presentationTimeUs}", maxOf(truth - preSkip, 0), framesOut)
            for (i in 0 until output.frames - h.dropFrames) granules += truth + i
        }
        return granules
    }

    private fun assertExact(targetUs: Long, discard: Int = preSkip) {
        for (runTime in listOf(false, true)) {
            val target = maxOf(Math.round(targetUs * 48_000 / 1e6), preSkip.toLong())
            val first = firstPacket(targetUs)
            assertTrue("pre-roll for $targetUs", first == 0L || first <= target - 3_840)
            val granules = seek(targetUs, discard, runTime)
            assertEquals("first audible granule, $targetUs runTime=$runTime", target, granules.first())
            assertEquals("contiguous to the end, $targetUs runTime=$runTime", (target until finalGranule).toList(), granules)
        }
    }

    @Test fun preRollStartsEarlierClampedToTheStreamStart() {
        assertTrue("RFC 7845 minimum", OpusSeekHead.PRE_ROLL_US >= 80_000)
        assertEquals(0L, OpusSeekHead.preRollStartUs(0))
        assertEquals(0L, OpusSeekHead.preRollStartUs(50_000))
        assertEquals(0L, OpusSeekHead.preRollStartUs(OpusSeekHead.PRE_ROLL_US))
        assertEquals(1_000_000L - OpusSeekHead.PRE_ROLL_US, OpusSeekHead.preRollStartUs(1_000_000))
    }

    @Test fun startOfStream() {
        // A flushed decoder discards the pre-skip like a fresh one (OpusReader's seek pre-roll csd).
        for (t in listOf(0L, 3_000L, 6_500L, 20_000L, 50_000L, 79_999L, 80_000L, OpusSeekHead.PRE_ROLL_US + 10_000)) assertExact(t)
    }

    @Test fun midPacketAndPacketBoundary() {
        for (t in listOf(500_000L, 512_345L, 1_000_000L, 1_010_000L, 1_000_021L)) assertExact(t)
    }

    @Test fun lastPage() {
        val endUs = us(finalGranule)
        for (t in listOf(endUs - 100_000, endUs - 30_000, endUs - 5_000, endUs - 21)) assertExact(t)
    }

    @Test fun decoderThatStillDiscardsTheFullSeekPreRollStillStartsExactlyMidStream() {
        for (t in listOf(500_000L, 512_345L)) assertExact(t, discard = 3_840)
    }

    @Test fun onlyOutputsLabelledBeforeTheTargetAreChangedPerRun() {
        val head = OpusSeekHead()
        // No run started (gapless successor on a running codec): untouched.
        assertEquals(OpusSeekHead.Head(0, 100, 0), head.onOutput(500, 100, 0, 960))
        head.restart()
        assertEquals(OpusSeekHead.Head(480, 50_000, 0), head.onOutput(50_000, 40_000, 0, 960))
        assertEquals(OpusSeekHead.Head(0, 60_000, 0), head.onOutput(50_000, 60_000, 0, 960))
        // A stale target (codec restarted for the next item) never changes anything.
        head.restart()
        assertEquals(OpusSeekHead.Head(0, 90_000, 6_500), head.onOutput(50_000, 90_000, 6_500, 648))
        assertEquals(OpusSeekHead.Head(0, 103_500, 6_500), head.onOutput(50_000, 103_500, 6_500, 960))
    }
}
