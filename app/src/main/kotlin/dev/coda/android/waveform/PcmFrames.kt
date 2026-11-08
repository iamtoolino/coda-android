package dev.coda.android.waveform

import dev.coda.core.waveform.PcmWaveform
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The decoder's PCM step as pure code (JVM-tested). One bulk copy per codec buffer instead of one `ByteBuffer`
 * call per sample; the values are exactly the per-sample ones (16 bit / 32768, or the float as is). Reusable scratch.
 */
internal class PcmFrames {
    private var shorts = ShortArray(0)
    private var floats = FloatArray(0)

    /** Calls [frame] with each complete frame (one value per channel) of [buffer]'s remaining bytes, little endian. */
    inline fun forEachFrame(buffer: ByteBuffer, channels: Int, float: Boolean, samples: FloatArray, frame: (FloatArray) -> Unit) {
        val frames = load(buffer, channels, float)
        for (index in 0 until frames) {
            val base = index * channels
            for (channel in samples.indices) samples[channel] = value(base + channel, float)
            frame(samples)
        }
    }

    /** Copies the complete frames out of [buffer] (consumed from its position) and returns their number. */
    fun load(buffer: ByteBuffer, channels: Int, float: Boolean): Int {
        val bytes = if (float) 4 else 2
        val frames = buffer.remaining() / (channels * bytes)
        val count = frames * channels
        val view = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        if (float) {
            if (floats.size < count) floats = FloatArray(count)
            view.asFloatBuffer().get(floats, 0, count)
        } else {
            if (shorts.size < count) shorts = ShortArray(count)
            view.asShortBuffer().get(shorts, 0, count)
        }
        return frames
    }

    fun value(i: Int, float: Boolean): Float = if (float) floats[i] else shorts[i] / 32768f
}

/** Asks [permit] on the first call, then at most every [periodMs] (a boundary is respected within that); cancellation passes through. */
internal class PermitGate(private val periodMs: Long, private val clock: () -> Long) {
    private var askedAt: Long? = null
    suspend fun ask(permit: suspend () -> Unit) {
        // The first call asks at once, so a short decode is never run unchecked.
        val last = askedAt
        if (last == null || clock() - last >= periodMs) { permit(); askedAt = clock() }
    }
}

/**
 * The envelope across the decoder's output-format notifications. Some decoders (c2 AAC) report a second
 * format a few buffers in that differs only in codec metadata (see ExoAudioEngine); same rate and channels keep the
 * envelope accumulated so far. Only a real PCM layout change starts a new one.
 */
internal class WaveformEnvelope(private val durationUs: Long) {
    private var rate = 0
    private var channels = 0
    var current: PcmWaveform? = null
        private set
    fun format(sampleRate: Int, channelCount: Int) {
        if (current != null && sampleRate == rate && channelCount == channels) return
        rate = sampleRate; channels = channelCount
        current = PcmWaveform((durationUs * sampleRate / 1_000_000).coerceAtLeast(1))
    }
}
