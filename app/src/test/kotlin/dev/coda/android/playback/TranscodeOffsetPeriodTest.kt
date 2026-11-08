package dev.coda.android.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import org.junit.Assert.*
import org.junit.Test

/**
 * Positions through [TranscodeOffsetSource]'s period are song time. A seek at or after the offset on
 * an unseekable stream (Media3's progressive period answers 0 to every seek, adjusted or not) lands on its target.
 */
@UnstableApi
class TranscodeOffsetPeriodTest {
    /** The inner progressive period: unseekable answers 0 to every position; seekable keeps it. */
    private class Inner(private val seekable: Boolean) : MediaPeriod {
        val seeks = mutableListOf<Long>()
        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {}
        override fun maybeThrowPrepareError() {}
        override fun getTrackGroups() = TrackGroupArray.EMPTY
        override fun selectTracks(selections: Array<out ExoTrackSelection?>, mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>, streamResetFlags: BooleanArray, positionUs: Long) = if (seekable) positionUs else 0L
        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {}
        override fun readDiscontinuity() = C.TIME_UNSET
        override fun seekToUs(positionUs: Long): Long { seeks += positionUs; return if (seekable) positionUs else 0L }
        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters) = if (seekable) positionUs else 0L
        override fun getBufferedPositionUs() = 1_000_000L
        override fun getNextLoadPositionUs() = C.TIME_END_OF_SOURCE
        override fun continueLoading(loadingInfo: LoadingInfo) = false
        override fun isLoading() = false
        override fun reevaluateBuffer(positionUs: Long) {}
    }

    private fun period(inner: Inner, offsetUs: Long) = TranscodeOffsetSource.Period(inner, { offsetUs }).also {
        it.prepare(object : MediaPeriod.Callback {
            override fun onPrepared(mediaPeriod: MediaPeriod) {}
            override fun onContinueLoadingRequested(source: MediaPeriod) {}
        }, 8_500_000)
        it.onPrepared(inner)
    }

    @Test fun aSeekAtOrAfterTheOffsetOfAnUnseekableStreamLandsOnTheTarget() {
        val inner = Inner(seekable = false)
        val period = period(inner, 8_000_000)
        assertEquals(10_300_000L, period.getAdjustedSeekPositionUs(10_300_000, SeekParameters.DEFAULT))
        assertEquals(10_300_000L, period.seekToUs(10_300_000))
        assertEquals("the stream is read again from its start (the offset)", listOf(2_300_000L), inner.seeks)
        assertEquals(8_000_000L, period.seekToUs(8_000_000))
        assertEquals("song time", 9_000_000L, period.bufferedPositionUs)
    }

    @Test fun aPositionBeforeTheOffsetLandsAtTheOffset() {
        val period = period(Inner(seekable = false), 8_000_000)
        assertEquals("not adjusted (the engine re-opens instead)", 5_000_000L, period.getAdjustedSeekPositionUs(5_000_000, SeekParameters.DEFAULT))
        assertEquals(8_000_000L, period.seekToUs(5_000_000))
    }

    @Test fun aSeekableOffsetStreamShiftsBothWays() {
        val period = period(Inner(seekable = true), 8_000_000)
        assertEquals(10_300_000L, period.getAdjustedSeekPositionUs(10_300_000, SeekParameters.DEFAULT))
        assertEquals(10_300_000L, period.seekToUs(10_300_000))
    }

    @Test fun withoutAnOffsetItIsTheIdentity() {
        val period = period(Inner(seekable = false), 0)
        assertEquals(0L, period.getAdjustedSeekPositionUs(10_300_000, SeekParameters.DEFAULT))
        assertEquals(0L, period.seekToUs(10_300_000))
        assertEquals(1_000_000L, period.bufferedPositionUs)
    }

    @Test fun theTimelineDurationIsSongTime() {
        val stream = androidx.media3.exoplayer.source.SinglePeriodTimeline(8_010_000, false, false, false, null,
            androidx.media3.common.MediaItem.EMPTY)
        val song = TranscodeOffsetSource.SongTimeTimeline(stream, 8_000_000)
        assertEquals(16_010_000L, song.getWindow(0, androidx.media3.common.Timeline.Window()).durationUs)
        assertEquals(16_010_000L, song.getPeriod(0, androidx.media3.common.Timeline.Period()).durationUs)
        val unknown = androidx.media3.exoplayer.source.SinglePeriodTimeline(C.TIME_UNSET, false, false, false, null,
            androidx.media3.common.MediaItem.EMPTY)
        assertEquals(C.TIME_UNSET, TranscodeOffsetSource.SongTimeTimeline(unknown, 8_000_000)
            .getWindow(0, androidx.media3.common.Timeline.Window()).durationUs)
    }

    @Test fun samplesMoveToSongTimeButTheEndOfStreamMarkerDoesNot() {
        var endOfStream = false
        val stream = TranscodeOffsetSource.Stream(object : SampleStream {
            override fun isReady() = true
            override fun maybeThrowError() {}
            override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
                if (endOfStream) { buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM); buffer.timeUs = C.TIME_END_OF_SOURCE }
                else buffer.timeUs = 20_000
                return C.RESULT_BUFFER_READ
            }
            override fun skipData(positionUs: Long) = 0
        }, 8_000_000)
        val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED)
        stream.readData(FormatHolder(), buffer, 0)
        assertEquals(8_020_000L, buffer.timeUs)
        buffer.clear(); endOfStream = true
        stream.readData(FormatHolder(), buffer, 0)
        assertEquals(C.TIME_END_OF_SOURCE, buffer.timeUs)
    }

    /** An unseekable transcode's queue, read up to [readUs] and loaded to [bufferedUs] (stream time). */
    private class Queue(var bufferedUs: Long) : MediaPeriod by Inner(seekable = false) {
        val inner = Inner(seekable = false)
        var readUs = 0L
        val skips = mutableListOf<Long>()
        val child = object : SampleStream {
            override fun isReady() = true
            override fun maybeThrowError() {}
            override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
                buffer.timeUs = readUs; readUs += 20_000; return C.RESULT_BUFFER_READ
            }
            override fun skipData(positionUs: Long): Int { skips += positionUs; readUs = positionUs; return 1 }
        }
        override fun selectTracks(selections: Array<out ExoTrackSelection?>, mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>, streamResetFlags: BooleanArray, positionUs: Long): Long {
            streams[0] = child; streamResetFlags[0] = true; return 0L
        }
        override fun seekToUs(positionUs: Long) = inner.seekToUs(positionUs)
        override fun getBufferedPositionUs() = bufferedUs
    }

    private fun localPeriod(queue: Queue, offsetUs: Long) = TranscodeOffsetSource.Period(queue, { offsetUs }, localSeeks = true).also {
        it.prepare(object : MediaPeriod.Callback {
            override fun onPrepared(mediaPeriod: MediaPeriod) {}
            override fun onContinueLoadingRequested(source: MediaPeriod) {}
        }, 0)
        it.onPrepared(queue)
        val streams = arrayOfNulls<SampleStream>(1)
        it.selectTracks(arrayOfNulls(1), BooleanArray(1), streams, BooleanArray(1), offsetUs)
        val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED)
        repeat(50) { _ -> buffer.clear(); streams[0]!!.readData(FormatHolder(), buffer, 0) } // the renderer read 1 s
        it.bufferedPositionUs
    }

    @Test fun aForwardSeekIntoTheBufferIsServedFromTheQueueWithoutANewRead() {
        val queue = Queue(bufferedUs = 12_000_000)
        val period = localPeriod(queue, 0)
        assertTrue(period.inBuffer(8_500_000))
        assertEquals(8_500_000L, period.getAdjustedSeekPositionUs(8_500_000, SeekParameters.DEFAULT))
        assertEquals(8_500_000L, period.seekToUs(8_500_000))
        assertEquals("skipped to the GP-2 pre-roll", listOf(7_900_000L), queue.skips)
        assertTrue("no restart of the stream", queue.inner.seeks.isEmpty())
        assertFalse("its pre-roll is now behind the read position", period.inBuffer(8_000_000))
    }

    @Test fun backwardsOrPastTheLoadedEdgeIsNotBuffered() {
        val queue = Queue(bufferedUs = 6_000_000)
        val period = localPeriod(queue, 0)
        assertFalse("past the loaded edge", period.inBuffer(6_500_000))
        assertFalse("behind the read position", period.inBuffer(500_000))
        assertEquals("handed to the inner period, as before", 0L, period.seekToUs(6_500_000))
        assertEquals(listOf(6_500_000L), queue.inner.seeks)
        assertTrue(queue.skips.isEmpty())
    }

    @Test fun anOffsetStreamSeeksItsBufferInSongTime() {
        val queue = Queue(bufferedUs = 6_000_000)
        val period = localPeriod(queue, 8_000_000)
        assertTrue(period.inBuffer(12_000_000))
        assertFalse("before the offset", period.inBuffer(7_000_000))
        assertEquals(12_000_000L, period.seekToUs(12_000_000))
        assertEquals(listOf(3_400_000L), queue.skips)
    }

    @Test fun withoutLocalSeeksNothingChanges() {
        val queue = Queue(bufferedUs = 12_000_000)
        val period = TranscodeOffsetSource.Period(queue, { 0L })
        assertFalse(period.inBuffer(8_500_000))
    }
}
