package dev.coda.android.playback

import androidx.media3.common.C
import androidx.media3.common.StreamKey
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import dev.coda.core.playback.TranscodeSeek

/**
 * A transcode the server started at `timeOffset` ([offsetUs], known once the stream was opened, i.e. by the time
 * the period is prepared). Its samples are labelled from 0; this period moves them, and every position, by the offset,
 * so the player, the renderer, the seek head and the UI all work in song time.
 *
 * Exact start: the offset is the whole second at or before the requested position. The stream cannot seek, so the inner
 * period answers 0 (its first sample) to any position in it; this period then keeps the requested position. The renderer
 * resets there and decodes the head between the offset and the position as decode-only, and OpusCodecAdapter trims the
 * output holding it (the path). The same holds for a seek at or after the offset: Media3's progressive period
 * adjusts every seek on an unseekable stream to 0 ([getAdjustedSeekPositionUs]), which this period keeps as the
 * requested song position, so the seek lands on its target (the stream is read again from the offset). A position
 * before the offset cannot be served by this stream: it lands at the offset. ExoAudioEngine.seekTo does not seek an
 * offset stream at all: it opens the item again at the target (a new `timeOffset`), which is cheaper than decoding
 * the head from the old offset.
 *
 * MP-2c, [localSeeks] (an uncached Opus transcode): Media3 cannot seek such a stream at all (every seek becomes a seek
 * to 0, see [TranscodeSeek]), but its sample queue still holds what was loaded past the renderer's read position. A seek
 * into that part ([inBuffer]) is served here: the streams skip to the pre-roll before the target, the renderer
 * resets at the target and decodes the pre-roll as decode-only (as for a seek inside the buffer of a seekable file),
 * and nothing is requested. Every other seek goes to the inner period as before.
 *
 * Modelled on Media3's private `MergingMediaPeriod.TimeOffsetMediaPeriod`.
 */
@UnstableApi
internal class TranscodeOffsetSource(source: MediaSource, private val offsetUs: () -> Long, private val localSeeks: Boolean = false) :
    WrappingMediaSource(source) {
    @Volatile private var period: Period? = null

    /** Whether a seek to [songUs] can be served from what is buffered (any thread; see [Period.inBuffer]). */
    fun inBuffer(songUs: Long): Boolean = period?.inBuffer(songUs) == true

    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        Period(super.createPeriod(id, allocator, startPositionUs), offsetUs, localSeeks).also { period = it }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        if (period === mediaPeriod) period = null
        super.releasePeriod((mediaPeriod as Period).period)
    }

    /**
     * The inner timeline's duration is stream time: the progressive period learns it at the end of a chunked load (its
     * last sample + 10 ms), or from the length of a seekable one. In song time it ends [offsetUs] later. Without this the
     * gapless successor was placed `offset` too early and the player's duration was short.
     */
    override fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
        val offset = offsetUs().coerceAtLeast(0)
        refreshSourceInfo(if (offset > 0) SongTimeTimeline(newTimeline, offset) else newTimeline)
    }

    internal class SongTimeTimeline(timeline: Timeline, private val offsetUs: Long) : ForwardingTimeline(timeline) {
        override fun getWindow(windowIndex: Int, window: Timeline.Window, defaultPositionProjectionUs: Long): Timeline.Window =
            super.getWindow(windowIndex, window, defaultPositionProjectionUs).also {
                if (it.durationUs != C.TIME_UNSET) it.durationUs += offsetUs
            }
        override fun getPeriod(periodIndex: Int, period: Timeline.Period, setIds: Boolean): Timeline.Period =
            super.getPeriod(periodIndex, period, setIds).also {
                if (it.durationUs != C.TIME_UNSET) it.durationUs += offsetUs
            }
    }

    internal class Period(val period: MediaPeriod, private val offset: () -> Long, private val localSeeks: Boolean = false) :
        MediaPeriod, MediaPeriod.Callback {
        private var callback: MediaPeriod.Callback? = null
        /** Read when the period is prepared: the stream has been opened and its offset decided by then. */
        @Volatile private var offsetUs = 0L
        @Volatile private var prepared = false
        private var streams: Array<SampleStream?> = emptyArray()
        /** MP-2c, stream time: the latest sample the renderer read (or skipped to), and the loaded edge (null: all of it). */
        private val read = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
        @Volatile private var loadedUs: Long? = 0L

        /** The pre-roll start (stream time) to skip to for a seek to [songUs] from the buffer, or null when it is not there. */
        private fun localStartUs(songUs: Long): Long? {
            if (!localSeeks || !prepared || songUs == C.TIME_UNSET) return null
            val target = songUs - offsetUs
            val start = dev.coda.core.playback.OpusSeekHead.preRollStartUs(target)
            return start.takeIf { TranscodeSeek.buffered(target, it, read.get(), loadedUs) }
        }
        fun inBuffer(songUs: Long) = localStartUs(songUs) != null

        private fun inner(positionUs: Long) = (positionUs - offsetUs).coerceAtLeast(0)
        private fun outer(positionUs: Long) =
            if (positionUs == C.TIME_END_OF_SOURCE || positionUs == C.TIME_UNSET) positionUs else positionUs + offsetUs
        /** A requested position inside the stream that the stream restarted from its first sample for stays requested. */
        private fun started(requestedUs: Long, resultUs: Long) =
            if (offsetUs > 0 && resultUs == 0L && requestedUs >= offsetUs) requestedUs else outer(resultUs)

        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            this.callback = callback
            period.prepare(this, 0) // The progressive period loads from its start regardless of the position.
        }
        override fun onPrepared(mediaPeriod: MediaPeriod) {
            offsetUs = offset().coerceAtLeast(0)
            prepared = true
            callback?.onPrepared(this)
        }
        override fun onContinueLoadingRequested(source: MediaPeriod) { callback?.onContinueLoadingRequested(this) }

        override fun selectTracks(selections: Array<out ExoTrackSelection?>, mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>, streamResetFlags: BooleanArray, positionUs: Long): Long {
            val children = Array(streams.size) { (streams[it] as? Stream)?.stream }
            val result = period.selectTracks(selections, mayRetainStreamFlags, children, streamResetFlags, inner(positionUs))
            for (i in streams.indices) {
                val child = children[i]
                streams[i] = when {
                    child == null -> null
                    (streams[i] as? Stream)?.stream === child -> streams[i]
                    else -> Stream(child, offsetUs, read)
                }
            }
            this.streams = streams.copyOf()
            if (streamResetFlags.any { it }) read.set(Long.MIN_VALUE)
            return started(positionUs, result)
        }
        override fun seekToUs(positionUs: Long): Long {
            val start = localStartUs(positionUs)
            if (start != null) {
                // The streams' read position moves to the packet holding the pre-roll start; the queue keeps the rest.
                for (stream in streams) (stream as? Stream)?.stream?.skipData(start)
                read.accumulateAndGet(start, ::maxOf)
                return positionUs
            }
            read.set(Long.MIN_VALUE) // The inner period starts the stream again: nothing of it has been read.
            return started(positionUs, period.seekToUs(inner(positionUs)))
        }
        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = period.discardBuffer(inner(positionUs), toKeyframe)
        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters) =
            if (positionUs < offsetUs || localStartUs(positionUs) != null) positionUs else started(positionUs, period.getAdjustedSeekPositionUs(inner(positionUs), seekParameters))
        override fun readDiscontinuity() = outer(period.readDiscontinuity())
        override fun getBufferedPositionUs(): Long {
            val buffered = period.bufferedPositionUs
            loadedUs = if (buffered == C.TIME_END_OF_SOURCE) null else buffered
            return outer(buffered)
        }
        override fun getNextLoadPositionUs() = outer(period.nextLoadPositionUs)
        override fun continueLoading(loadingInfo: LoadingInfo) =
            period.continueLoading(loadingInfo.buildUpon().setPlaybackPositionUs(inner(loadingInfo.playbackPositionUs)).build())
        override fun reevaluateBuffer(positionUs: Long) = period.reevaluateBuffer(inner(positionUs))
        override fun setEndPositionUs(endPositionUs: Long) =
            period.setEndPositionUs(if (endPositionUs == C.TIME_UNSET) endPositionUs else inner(endPositionUs))

        override fun maybeThrowPrepareError() = period.maybeThrowPrepareError()
        override fun getTrackGroups(): TrackGroupArray = period.trackGroups
        override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> = period.getStreamKeys(trackSelections)
        override fun isLoading() = period.isLoading
        override fun setUsesStreamPrerollFlags() = period.setUsesStreamPrerollFlags()
    }

    internal class Stream(val stream: SampleStream, private val offsetUs: Long,
        private val read: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong()) : SampleStream {
        override fun isReady() = stream.isReady
        override fun maybeThrowError() = stream.maybeThrowError()
        override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
            val result = stream.readData(formatHolder, buffer, readFlags)
            if (result == C.RESULT_BUFFER_READ && !buffer.isEndOfStream) {
                if (readFlags and SampleStream.FLAG_PEEK == 0) read.accumulateAndGet(buffer.timeUs, ::maxOf)
                buffer.timeUs += offsetUs
            }
            return result
        }
        override fun skipData(positionUs: Long) = stream.skipData((positionUs - offsetUs).coerceAtLeast(0))
    }
}
