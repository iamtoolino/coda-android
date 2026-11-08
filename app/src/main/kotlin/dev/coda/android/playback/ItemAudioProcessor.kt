package dev.coda.android.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import dev.coda.core.playback.EngineItem
import dev.coda.core.playback.OccurrenceId
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import kotlin.math.roundToLong

/** Shared only between the playback thread and the source loader; keys are occurrences, never URLs. */
internal class ItemAudioState {
    val seekFrameOffsets = ConcurrentHashMap<OccurrenceId, Long>()
    val opusPreSkipFrames = ConcurrentHashMap<OccurrenceId, Int>()
    val encoderDelayFrames = ConcurrentHashMap<OccurrenceId, Int>()
    val encoderPaddingFrames = ConcurrentHashMap<OccurrenceId, Int>()
    val inPlaceSeeks = ConcurrentHashMap.newKeySet<OccurrenceId>()
    val mimeTypes = ConcurrentHashMap<OccurrenceId, String>()
    val endFrames = ConcurrentHashMap<OccurrenceId, Long>()
    // Edit-list M4A delay and exact audible length from the boxes; the sink turns it into endFrames.
    val mp4EditFrames = ConcurrentHashMap<OccurrenceId, Mp4EditFrames>()
    // The extractor's AAC sample rate; HE-AAC decodes to a multiple of it (ItemAudioSink.sbrFactor).
    val sourceSampleRates = ConcurrentHashMap<OccurrenceId, Int>()
    val gains = ConcurrentHashMap<OccurrenceId, Double>()
    private val sourceTokens = mutableMapOf<OccurrenceId, Any>()
    @Synchronized fun beginSource(id: OccurrenceId): Any {
        val token = Any()
        sourceTokens[id] = token
        endFrames.remove(id); opusPreSkipFrames.remove(id); seekFrameOffsets.remove(id)
        mimeTypes.remove(id); encoderDelayFrames.remove(id); encoderPaddingFrames.remove(id); inPlaceSeeks.remove(id)
        mp4EditFrames.remove(id); sourceSampleRates.remove(id)
        return token
    }
    @Synchronized fun fromSource(id: OccurrenceId, token: Any, publish: () -> Unit) {
        if (sourceTokens[id] === token) publish()
    }
    @Synchronized fun retireSources() { sourceTokens.clear() }
    @Synchronized fun retainSources(ids: Set<OccurrenceId>) { sourceTokens.keys.retainAll(ids) }
    // Written by the forwarding sink immediately before its synchronous processor calls.
    var presentationTimeUs = 0L
    var outputStreamOffsetUs = 0L
    // A new period's configuration arrived before the renderer applied that period's stream offset.
    var outputStreamOffsetPending = false
    // The sink is draining because the renderer's stream ended (queue end, renderer switch), not
    // for a mid-item discontinuity or parameter change that flushes and continues the same item.
    var playingToEndOfStream = false
    // The MediaItem ExoAudioEngine built for the current item of its latest load (index 0): a new instance per
    // load, source replacement and recovery reload, so it identifies that load (ItemAudioSink.forOutput).
    @Volatile var loadedFirst: Any? = null
    // Each audio stream's renderer offset and the period it belongs to, recorded by StreamRecordingRenderer when
    // Media3 enables a renderer or replaces its stream; the sink resolves every applied stream offset with it.
    private val streamPeriods = object : LinkedHashMap<Long, Any>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Any>?) = size > 16
    }
    @Synchronized fun recordStream(offsetUs: Long, periodUid: Any) { streamPeriods[offsetUs] = periodUid }
    @Synchronized fun periodOfStream(offsetUs: Long): Any? = streamPeriods[offsetUs]
    // Written by OpusCodecAdapter when the renderer dequeues the output the sink handles next.
    var opusOutputShiftUs = 0L
    // The audio renderer's last reset position (renderer time), recorded by StreamRecordingRenderer.
    @Volatile var rendererResetUs = 0L
}

@UnstableApi
internal class ItemAudioProcessor(private val items: ItemAudioState) : BaseAudioProcessor() {
    private var occurrence: OccurrenceId? = null
    private var framesOut = 0L
    private var offsetInWindowUs = 0L
    private var reconfigurationPending = false
    private var pendingStartTrimFrames = 0
    private var consumeSeekOnInput = false
    // MP3 and iTunSMPB AAC: the last encoder-padding frames received, mirroring Media3's TrimmingAudioProcessor.
    private var endHold = ByteArray(0)
    private var endHoldSize = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        reconfigurationPending = true
        return inputAudioFormat
    }

    override fun isActive() = true

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val timeline = streamMetadata.timeline
        val uid = streamMetadata.periodUid
        var media: MediaItem? = null
        offsetInWindowUs = 0
        if (uid != null && !timeline.isEmpty) {
            val period = timeline.getPeriodByUid(uid, Timeline.Period())
            offsetInWindowUs = period.positionInWindowUs
            val window = timeline.getWindow(period.windowIndex, Timeline.Window())
            media = window.mediaItem
        }
        occurrence = (media?.localConfiguration?.tag as? EngineItem)?.occurrence
        // Preserve the flush timestamp for AAC/MP3; the sink trims initial encoder delay before us.
        // Opus resolves its first audible timestamp in queueInput (see below).
        val positionUs = items.presentationTimeUs - items.outputStreamOffsetUs + offsetInWindowUs
        // Media3's TrimmingAudioProcessor reapplies encoder delay only after configure(), not
        // an in-place seek flush. AAC and FFmpeg MP3 then emit their warm-up samples again,
        // while timestamps still include that delay. Drop them before the timestamp end cap.
        // TrimmingAudioProcessor (before us) holds back the last encoderPadding frames and drops them
        // at a gapless reconfiguration, but releases them when the sink plays to end of stream (the
        // queue's end, or a renderer switch such as MP3 to Opus). We hold back the same number of
        // frames, so the frames it releases are exactly the ones we drop. This uses the LAME/iTunSMPB
        // padding count only, never a Xing/MLLT/TLEN duration or a seek-estimated timestamp.
        // The same for iTunSMPB AAC, whose padding Media3 also releases at the queue's end. Edit-list AAC is
        // capped at its exact length instead (endFrames), which already stops the released padding.
        if (reconfigurationPending) {
            val mime = occurrence?.let(items.mimeTypes::get)
            val padding = if (mime == MimeTypes.AUDIO_MPEG ||
                (mime == MimeTypes.AUDIO_AAC && occurrence?.let(items.endFrames::get) == null))
                occurrence?.let(items.encoderPaddingFrames::get) ?: 0 else 0
            endHold = ByteArray(padding.coerceAtLeast(0) * inputAudioFormat.bytesPerFrame)
        }
        endHoldSize = 0
        val sought = occurrence?.let(items.inPlaceSeeks::contains) == true
        consumeSeekOnInput = sought
        pendingStartTrimFrames = if (sought && !reconfigurationPending &&
            occurrence?.let(items.mimeTypes::get) in listOf(MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_MPEG))
            occurrence?.let(items.encoderDelayFrames::get) ?: 0 else 0
        reconfigurationPending = false
        framesOut = framesAt(positionUs)
    }

    private fun framesAt(positionUs: Long): Long {
        var frames = (positionUs.coerceAtLeast(0) * inputAudioFormat.sampleRate / 1e6).roundToLong()
        // Ogg timestamps count decoded granules, including Opus pre-skip.
        if (positionUs > 0) {
            frames = (frames - (occurrence?.let(items.opusPreSkipFrames::get) ?: 0) +
                (occurrence?.let(items.seekFrameOffsets::get) ?: 0)).coerceAtLeast(0)
        }
        return frames
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        // The sink can flush once with the old timestamp and again with the seek timestamp.
        // Keep the seek marker through those empty flushes until PCM actually arrives.
        if (consumeSeekOnInput) {
            occurrence?.let(items.inPlaceSeeks::remove)
            consumeSeekOnInput = false
        }
        val frameBytes = inputAudioFormat.bytesPerFrame
        val trim = minOf(pendingStartTrimFrames, inputBuffer.remaining() / frameBytes)
        inputBuffer.position(inputBuffer.position() + trim * frameBytes)
        pendingStartTrimFrames -= trim
        if (!inputBuffer.hasRemaining()) return
        val frames = inputBuffer.remaining() / frameBytes
        val total = occurrence?.let(items.endFrames::get)
        if (occurrence?.let(items.mimeTypes::get) == MimeTypes.AUDIO_OPUS) {
            // The decoder discards pre-skip after a codec start and seek pre-roll after a flush.
            // Depending on its build it then labels outputs with packet (granule) time or early by
            // the discard (ID3); OpusCodecAdapter measures which, per decoder run, and publishes
            // the correction to packet time. Anchor every non-empty buffer on its own corrected
            // timestamp, so the end cap depends only on the final packets.
            // An early-labelling decoder's last output precedes the last input packet's timestamp,
            // so Media3 applies a gapless successor's stream offset only after the successor's first
            // buffer; until then that successor is at its first frame.
            framesOut = if (items.outputStreamOffsetPending) 0 else framesAt(items.presentationTimeUs +
                items.opusOutputShiftUs - items.outputStreamOffsetUs + offsetInWindowUs)
        }
        val keep = if (total == null) frames else (total - framesOut).coerceIn(0, frames.toLong()).toInt()
        val gain = 10.0.pow((occurrence?.let(items.gains::get) ?: 0.0) / 20.0)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        if (endHold.isEmpty()) {
            val out = replaceOutputBuffer(keep * frameBytes).order(ByteOrder.LITTLE_ENDIAN)
            repeat(keep * inputAudioFormat.channelCount) { out.putShort(scaled(inputBuffer.short, gain)) }
            out.flip()
        } else {
            // Output whatever no longer fits in endHold: its oldest frames first, then new input.
            val newBytes = keep * frameBytes
            val outBytes = (endHoldSize + newBytes - endHold.size).coerceAtLeast(0)
            val out = replaceOutputBuffer(outBytes).order(ByteOrder.LITTLE_ENDIAN)
            val fromHold = minOf(outBytes, endHoldSize)
            out.put(endHold, 0, fromHold)
            System.arraycopy(endHold, fromHold, endHold, 0, endHoldSize - fromHold)
            endHoldSize -= fromHold
            repeat((outBytes - fromHold) / 2) { out.putShort(scaled(inputBuffer.short, gain)) }
            val held = ByteBuffer.wrap(endHold, endHoldSize, newBytes - (outBytes - fromHold)).order(ByteOrder.LITTLE_ENDIAN)
            while (held.hasRemaining()) held.putShort(scaled(inputBuffer.short, gain))
            endHoldSize = held.position()
            out.flip()
        }
        inputBuffer.position(inputBuffer.limit())
        framesOut += frames
    }

    private fun scaled(sample: Short, gain: Double) = (sample * gain).roundToLong().coerceIn(-32768, 32767).toShort()

    override fun onQueueEndOfStream() {
        // Only a terminal drain without a pending configuration makes the trimmer release its
        // padding, which we now hold: drop it. At a gapless transition the trimmer dropped the
        // padding itself; at a mid-item drain it released audible frames. Both keep ours (audible).
        if (!reconfigurationPending && items.playingToEndOfStream) endHoldSize = 0
    }

    override fun getOutput(): ByteBuffer {
        if (super.isEnded() && endHoldSize > 0) {
            replaceOutputBuffer(endHoldSize).put(endHold, 0, endHoldSize).flip()
            endHoldSize = 0
        }
        return super.getOutput()
    }

    override fun isEnded() = super.isEnded() && endHoldSize == 0

    override fun onReset() {
        endHold = ByteArray(0)
        endHoldSize = 0
    }
}
