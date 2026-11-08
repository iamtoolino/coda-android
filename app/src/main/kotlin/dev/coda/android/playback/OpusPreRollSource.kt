package dev.coda.android.playback

import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import dev.coda.core.playback.OpusSeekHead

/**
 * An Opus period reads from [OpusSeekHead.PRE_ROLL_US] before every requested position (RFC 7845 §4.6
 * seek pre-roll), while the player, the renderer's reset position and the UI keep the requested position.
 *
 * This must sit above the sample queue: a seek inside the buffer goes to `SampleQueue.seekTo`, which snaps
 * to the last sync sample at or before the position, and every Opus packet is a sync sample, so packets an
 * extractor-side pre-roll wrote before the target would be skipped there. Asking the inner period for the
 * earlier position puts the read position (in the buffer, or through an extractor seek) at the packet that
 * holds `position - pre-roll`. The renderer skips the pre-roll as decode-only and OpusCodecAdapter trims the
 * output that holds the target. Other formats pass through unchanged.
 */
@UnstableApi
internal class OpusPreRollSource(source: MediaSource) : WrappingMediaSource(source) {
    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        Period(super.createPeriod(id, allocator, startPositionUs))

    override fun releasePeriod(mediaPeriod: MediaPeriod) = super.releasePeriod((mediaPeriod as Period).period)

    private class Period(val period: MediaPeriod) : MediaPeriod, MediaPeriod.Callback {
        private var callback: MediaPeriod.Callback? = null
        private var opus = false

        private fun inner(positionUs: Long) = if (opus) OpusSeekHead.preRollStartUs(positionUs) else positionUs
        /** The requested position when the inner period took the pre-roll position as asked, else what it chose. */
        private fun outer(requestedUs: Long, askedUs: Long, resultUs: Long) = if (resultUs == askedUs) requestedUs else resultUs

        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            this.callback = callback
            period.prepare(this, positionUs)
        }
        override fun onPrepared(mediaPeriod: MediaPeriod) {
            val groups = period.trackGroups
            opus = (0 until groups.length).any { groups[it].getFormat(0).sampleMimeType == MimeTypes.AUDIO_OPUS }
            callback?.onPrepared(this)
        }
        override fun onContinueLoadingRequested(source: MediaPeriod) { callback?.onContinueLoadingRequested(this) }

        override fun selectTracks(selections: Array<out ExoTrackSelection?>, mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>, streamResetFlags: BooleanArray, positionUs: Long): Long {
            val asked = inner(positionUs)
            return outer(positionUs, asked, period.selectTracks(selections, mayRetainStreamFlags, streams, streamResetFlags, asked))
        }
        override fun seekToUs(positionUs: Long): Long {
            val asked = inner(positionUs)
            return outer(positionUs, asked, period.seekToUs(asked))
        }
        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = period.discardBuffer(inner(positionUs), toKeyframe)

        override fun maybeThrowPrepareError() = period.maybeThrowPrepareError()
        override fun getTrackGroups(): TrackGroupArray = period.trackGroups
        override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> = period.getStreamKeys(trackSelections)
        override fun readDiscontinuity() = period.readDiscontinuity()
        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters) =
            period.getAdjustedSeekPositionUs(positionUs, seekParameters)
        override fun getBufferedPositionUs() = period.bufferedPositionUs
        override fun getNextLoadPositionUs() = period.nextLoadPositionUs
        override fun continueLoading(loadingInfo: LoadingInfo) = period.continueLoading(loadingInfo)
        override fun isLoading() = period.isLoading
        override fun reevaluateBuffer(positionUs: Long) = period.reevaluateBuffer(positionUs)
        override fun setUsesStreamPrerollFlags() = period.setUsesStreamPrerollFlags()
        override fun setEndPositionUs(endPositionUs: Long) = period.setEndPositionUs(endPositionUs)
    }
}
