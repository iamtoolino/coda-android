package dev.coda.android.waveform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The waveform decoder's bulk PCM read equals the per-sample read it replaced; the permit is asked every 100 ms. */
class PcmFramesTest {
    /** The loop BAT-1a replaced. */
    private fun reference(buffer: ByteBuffer, channels: Int, float: Boolean): List<List<Float>> {
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val bytes = if (float) 4 else 2
        val out = ArrayList<List<Float>>()
        while (b.remaining() >= channels * bytes) out.add(List(channels) { if (float) b.float else b.short / 32768f })
        return out
    }

    private fun bulk(buffer: ByteBuffer, channels: Int, float: Boolean): List<List<Float>> {
        val out = ArrayList<List<Float>>()
        PcmFrames().forEachFrame(buffer, channels, float, FloatArray(channels)) { out.add(it.toList()) }
        return out
    }

    @Test fun sixteenBitMatchesTheOldLoopWithOffsetPartialFrameAndAnyInputOrder() {
        val random = java.util.Random(7)
        for (channels in 1..2) {
            val data = ByteArray(2 + channels * 2 * 500 + 1).also(random::nextBytes) // 2 bytes before the window, a partial frame after
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN) // the codec's buffer order is irrelevant
            buffer.position(2).limit(data.size)
            val expected = reference(buffer, channels, false)
            assertEquals(500, expected.size)
            assertEquals(expected, bulk(buffer, channels, false))
            assertEquals("the caller's buffer is not consumed", 2, buffer.position())
        }
    }

    @Test fun floatMatchesTheOldLoop() {
        val random = java.util.Random(9)
        val buffer = ByteBuffer.allocate(4 * 2 * 300 + 1).order(ByteOrder.LITTLE_ENDIAN)
        repeat(600) { buffer.putFloat(random.nextFloat() * 2 - 1) }
        buffer.flip()
        assertEquals(reference(buffer, 2, true), bulk(buffer, 2, true))
        assertEquals(300, bulk(buffer, 2, true).size)
    }

    @Test fun scratchGrowsAndAnEmptyBufferGivesNoFrames() {
        val frames = PcmFrames()
        assertEquals(0, frames.load(ByteBuffer.allocate(3), 2, false))
        assertEquals(10, frames.load(ByteBuffer.allocate(40), 2, false))
        assertEquals(2, frames.load(ByteBuffer.allocate(8), 2, false))
    }

    @Test fun thePermitIsAskedAtMostEveryPeriodAndCancellationPassesThrough() = runBlocking {
        var now = 0L
        val gate = PermitGate(100) { now }
        var asked = 0
        suspend fun step(ms: Long) { now += ms; gate.ask { asked++ } }
        step(0); assertEquals("asked before the first buffer", 1, asked)
        repeat(9) { step(10) }       // 90 ms
        assertEquals(1, asked)
        step(10); assertEquals(2, asked) // 100 ms
        repeat(9) { step(10) }; assertEquals(2, asked)
        step(10); assertEquals(3, asked)
        now += 500
        try { gate.ask { throw CancellationException("job cancelled") }; fail() } catch (_: CancellationException) {}
        assertTrue(true)
    }

    /** PCM, a repeated equal output format (codec metadata only), PCM: one continuous envelope. */
    @Test fun repeatedEqualOutputFormatKeepsTheEnvelope() {
        val frames = 48_000L
        fun pcm(target: dev.coda.core.waveform.PcmWaveform, from: Long, to: Long) {
            for (f in from until to) target.frame(floatArrayOf((f % 7) / 7f, if (f < frames / 2) 0.9f else 0.1f))
        }
        val continuous = dev.coda.core.waveform.PcmWaveform(frames).also { pcm(it, 0, frames) }.finish()
        val envelope = WaveformEnvelope(1_000_000)
        envelope.format(48_000, 2)
        pcm(envelope.current!!, 0, frames / 3)
        envelope.format(48_000, 2)
        pcm(envelope.current!!, frames / 3, frames)
        assertEquals(continuous, envelope.current!!.finish())
        // A real layout change still starts afresh.
        val before = envelope.current
        envelope.format(44_100, 2)
        assertTrue(before !== envelope.current)
    }
}
