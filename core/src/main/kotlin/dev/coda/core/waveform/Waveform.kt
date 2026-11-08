package dev.coda.core.waveform

import kotlin.math.sqrt

/** Streaming, constant-space RMS envelope. Channels contribute equally, without phase cancellation. */
class PcmWaveform(private val expectedFrames: Long, val bucketCount: Int = 200) {
    init { require(expectedFrames > 0); require(bucketCount > 0) }
    private val energy = DoubleArray(bucketCount)
    private val counts = LongArray(bucketCount)
    private var frame = 0L
    fun frame(samples: FloatArray) {
        require(samples.isNotEmpty())
        val bucket = ((frame++ * bucketCount / expectedFrames).coerceAtMost(bucketCount - 1L)).toInt()
        for (sample in samples) {
            val value = if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f
            energy[bucket] += value.toDouble() * value
            counts[bucket]++
        }
    }
    fun finish(): List<Float> {
        val rms = energy.indices.map { if (counts[it] == 0L) 0f else sqrt(energy[it] / counts[it]).toFloat() }
        val maximum = rms.maxOrNull() ?: 0f
        return if (maximum == 0f) rms else rms.map { (it / maximum).coerceIn(0f, 1f) }
    }
}

/** Each drawn bar preserves the strongest bucket in its interval, including narrow transients. */
fun resampleWaveform(peaks: List<Float>, bars: Int): List<Float> {
    require(bars > 0)
    if (peaks.isEmpty()) return List(bars) { 0f }
    return List(bars) { i ->
        val start = i * peaks.size / bars
        val end = ((i + 1) * peaks.size / bars).coerceAtLeast(start + 1).coerceAtMost(peaks.size)
        peaks.subList(start, end).maxOrNull()!!.coerceIn(0f, 1f)
    }
}

/**
 * Bar height = linear value cubed, so the top end of loud, compressed music shows
 * differences. Draw-time only: the decoder and the cached 200-bucket envelope stay linear. No normalisation, no floor.
 * Cubing is monotonic, so the strongest bucket per bar stays the strongest after the curve.
 */
fun waveformBarHeight(linear: Float): Float {
    val v = linear.coerceIn(0f, 1f)
    return v * v * v
}

/** The bars the seek bar draws, 0..1 each: [resampleWaveform] followed by [waveformBarHeight]. */
fun waveformBars(peaks: List<Float>, bars: Int): List<Float> = resampleWaveform(peaks, bars).map(::waveformBarHeight)

fun waveformDecodeAllowed(playing: Boolean, positionMs: Long, durationMs: Long): Boolean =
    !playing || (durationMs > 0 && durationMs - positionMs > 10_000)
