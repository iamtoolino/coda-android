package dev.coda.core.waveform

import org.junit.Assert.*
import org.junit.Test

class WaveformTest {
    @Test fun rmsCombinesChannelsWithoutPhaseCancellationAndNormalises() {
        val waveform = PcmWaveform(4, 2)
        waveform.frame(floatArrayOf(1f, -1f)); waveform.frame(floatArrayOf(0f, 0f))
        waveform.frame(floatArrayOf(.25f, -.25f)); waveform.frame(floatArrayOf(.25f, -.25f))
        val peaks = waveform.finish()
        assertEquals(1f, peaks[0], .00001f)
        assertEquals(.353553f, peaks[1], .00001f)
    }
    @Test fun silenceNonFiniteAndShortInputAreFinite() {
        val waveform = PcmWaveform(10, 200)
        waveform.frame(floatArrayOf(0f, Float.NaN, Float.POSITIVE_INFINITY))
        assertEquals(List(200) { 0f }, waveform.finish())
        assertEquals(listOf(.8f, 1f), resampleWaveform(listOf(.2f, .8f, 1f, .3f), 2))
        assertEquals(List(44) { 0f }, resampleWaveform(emptyList(), 44))
    }
    /** Golden values of the v^3 power curve. */
    @Test fun powerCurveGoldenValues() {
        assertEquals(0f, waveformBarHeight(0f), 0f)
        assertEquals(.125f, waveformBarHeight(.5f), 1e-7f)
        assertEquals(1f, waveformBarHeight(1f), 0f)
        assertEquals(.729f, waveformBarHeight(.9f), 1e-6f)
        assertEquals(.001f, waveformBarHeight(.1f), 1e-7f)
        // Out-of-range input cannot escape 0..1.
        assertEquals(0f, waveformBarHeight(-.2f), 0f); assertEquals(1f, waveformBarHeight(1.5f), 0f)
    }
    @Test fun barsAreTheResampledEnvelopeCubed() {
        // 200 buckets rising 0..1 linearly: bar i keeps its interval's strongest bucket, then the curve applies.
        val envelope = List(200) { it / 199f }
        val bars = waveformBars(envelope, 44)
        assertEquals(44, bars.size)
        val linear = resampleWaveform(envelope, 44)
        assertEquals(3 / 199f, linear[0], 1e-7f)
        // Hand-computed: bar 22 covers buckets 100..103 (22*200/44 = 100, 23*200/44 = 104), strongest is bucket 103.
        assertEquals(103 / 199f, linear[22], 1e-7f)
        assertEquals(99 / 199f, linear[21], 1e-7f) // bar 21 covers buckets 95..99 (21*200/44 = 95)
        // Golden cubes, computed by hand: (3/199)^3, (99/199)^3, (103/199)^3, 1.
        assertEquals(3.4261355e-6f, bars[0], 1e-9f)
        assertEquals(.12313f, bars[21], 1e-5f)
        assertEquals(.13866f, bars[22], 1e-5f)
        assertEquals(1f, bars[43], 0f)
        assertTrue(bars.zipWithNext().all { (a, b) -> b >= a })
        // A compressed ("brick") passage: linear 0.90 and 1.00 differ by 10 %, the bars by 27 %.
        val brick = waveformBars(List(200) { if (it < 100) .9f else 1f }, 2)
        assertEquals(.729f, brick[0], 1e-6f); assertEquals(1f, brick[1], 0f)
        assertEquals(List(44) { 0f }, waveformBars(emptyList(), 44))
    }
    /** Mean and max differ inside one bar: a transient must win (peak, not mean), then be cubed. */
    @Test fun aTransientInsideOneBarWinsOverItsQuietNeighboursThenIsCubed() {
        val envelope = MutableList(200) { .1f }
        envelope[101] = .9f // inside bar 22 (buckets 100..103); the mean of that bar would be .3
        val bars = waveformBars(envelope, 44)
        assertEquals(.729f, bars[22], 1e-6f)
        assertEquals(.001f, bars[21], 1e-7f); assertEquals(.001f, bars[23], 1e-7f)
        assertEquals(43, bars.count { it < .0011f })
    }
    @Test fun guardDefersUnknownDurationAndTheFinalTenSeconds() {
        assertFalse(waveformDecodeAllowed(true, 290_000, 300_000))
        assertFalse(waveformDecodeAllowed(true, 0, 0))
        assertTrue(waveformDecodeAllowed(true, 289_999, 300_000))
        assertTrue(waveformDecodeAllowed(false, 299_999, 300_000))
    }
}
