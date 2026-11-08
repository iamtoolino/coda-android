package dev.coda.android.playback

import android.media.MediaCodec
import android.media.MediaFormat
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ThrowingRunnable
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import dev.coda.core.playback.OpusOutputClock
import dev.coda.core.playback.OpusSeekHead
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Observes an Opus codec's own input and output, including the outputs the renderer later skips as
 * decode-only, so [OpusOutputClock] can tell how this decoder labels timestamps (ID3). Publishes the
 * correction for the output buffer the renderer is about to hand to the sink.
 * Every run starts with seek pre-roll (OpusPreRollSource); [OpusSeekHead] trims the output that
 * holds the renderer's reset position so the first audible frame is exactly the target.
 */
@UnstableApi
internal class OpusCodecAdapter(
    private val codec: MediaCodecAdapter,
    format: Format,
    private val audio: ItemAudioState,
) : MediaCodecAdapter by codec {
    private val preSkipFrames = format.initializationData.getOrNull(1)?.let(::nanosToFrames)
        ?: format.initializationData.firstOrNull()?.takeIf { it.size >= 12 }
            ?.let { (it[10].toInt() and 255) or ((it[11].toInt() and 255) shl 8) } ?: 0
    private val seekPreRollFrames = format.initializationData.getOrNull(2)?.let(::nanosToFrames) ?: 3840
    private val clock = OpusOutputClock().also { it.restart(preSkipFrames) }
    private val head = OpusSeekHead().also { it.restart() }
    private var bytesPerFrame = 2 * format.channelCount.coerceAtLeast(1)

    init { audio.opusOutputShiftUs = 0 }

    override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
        if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && size > 0) clock.onInput(presentationTimeUs)
        codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
    }

    override fun dequeueOutputBufferIndex(bufferInfo: MediaCodec.BufferInfo): Int {
        val index = codec.dequeueOutputBufferIndex(bufferInfo)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            val output = codec.outputFormat
            val channels = output.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: (bytesPerFrame / 2)
            val bytes = when (output.getIntegerOrNull(MediaFormat.KEY_PCM_ENCODING)) {
                android.media.AudioFormat.ENCODING_PCM_FLOAT -> 4; else -> 2
            }
            bytesPerFrame = channels * bytes
        }
        if (index >= 0 && bufferInfo.size > 0) {
            val frames = bufferInfo.size / bytesPerFrame
            val shift = clock.onOutput(bufferInfo.presentationTimeUs, frames)
            val h = head.onOutput(audio.rendererResetUs, bufferInfo.presentationTimeUs, shift, frames)
            if (h.dropFrames > 0 || h.presentationTimeUs != bufferInfo.presentationTimeUs) {
                val drop = h.dropFrames * bytesPerFrame
                bufferInfo.set(bufferInfo.offset + drop, bufferInfo.size - drop, h.presentationTimeUs, bufferInfo.flags)
            }
            audio.opusOutputShiftUs = h.shiftUs
        }
        return index
    }

    override fun flush() {
        codec.flush()
        clock.restart(seekPreRollFrames)
        head.restart()
        audio.opusOutputShiftUs = 0
    }

    // Java default methods are not delegated by `by`; Media3's asynchronous mode relies on these.
    override fun registerOnBufferAvailableListener(listener: MediaCodecAdapter.OnBufferAvailableListener) =
        codec.registerOnBufferAvailableListener(listener)
    override fun <E : Exception> useBuffer(runnable: ThrowingRunnable<E>) = codec.useBuffer(runnable)

    companion object {
        fun isOpus(format: Format) = format.sampleMimeType == MimeTypes.AUDIO_OPUS

        private fun nanosToFrames(bytes: ByteArray): Int? = if (bytes.size != 8) null else
            (ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).long * 48_000 / 1_000_000_000L).toInt()

        private fun MediaFormat.getIntegerOrNull(key: String) = if (containsKey(key)) getInteger(key) else null
    }
}
