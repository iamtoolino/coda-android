package dev.coda.android.waveform

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteOrder

/** Used only on the service's single background-priority worker, never the playback decoder. */
class WaveformDecoder {
    private companion object { const val PERMIT_MS = 100L }
    suspend fun decode(file: File, permit: suspend () -> Unit = {}): List<Float> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            permit()
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            format.setInteger(MediaFormat.KEY_PRIORITY, 1) // Non-realtime codec work.
            decoder.configure(format, null, null, 0)
            decoder.start()
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val envelope = WaveformEnvelope(durationUs)
            var samples = FloatArray(channels)
            val pcm = PcmFrames()
            var inputEnded = false
            val info = MediaCodec.BufferInfo()
            // Bound malformed input/decoder stalls as well as coroutine cancellation.
            var idle = 0
            // The permit hops to Main; asking once per codec buffer (~12,000 times per 4-minute Opus track) cost
            // more than the decode. Asked at most every PERMIT_MS instead; a boundary is still respected within that.
            val gate = PermitGate(PERMIT_MS) { android.os.SystemClock.elapsedRealtime() }
            while (idle < 500) {
                currentCoroutineContext().ensureActive()
                gate.ask(permit)
                var progressed = false
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(0)
                    if (index >= 0) {
                        val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        progressed = true
                    }
                }
                when (val index = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = decoder.outputFormat
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        samples = FloatArray(channels)
                        encoding = output.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT)
                        envelope.format(output.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels)
                        progressed = true
                    }
                    else -> if (index >= 0) {
                        try {
                            val buffer = decoder.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val target = checkNotNull(envelope.current)
                            // One bulk copy per buffer instead of a ByteBuffer call per sample (same values).
                            pcm.forEachFrame(buffer, channels, encoding == AudioFormat.ENCODING_PCM_FLOAT, samples) { target.frame(it) }
                        } finally { decoder.releaseOutputBuffer(index, false) }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return checkNotNull(envelope.current).finish()
                        progressed = true
                    }
                }
                idle = if (progressed) 0 else idle + 1
            }
            error("Waveform decoder stalled")
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }
}
