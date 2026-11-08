package dev.coda.android.cache

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import dev.coda.core.cache.ArrivalDetector
import dev.coda.core.playback.*
import dev.coda.android.playback.RemoteStreamResolver

/**
 * Streams a track for the player. It never writes the cache: the Prefetcher owns every cache write and
 * downloads the current track at full speed instead of at the player's read-ahead pace.
 */
@UnstableApi
class TrackByteSource(
    private val stream: TrackSource,
    private val remote: RemoteStreamResolver,
    private val onFallback: (TrackSource.Remote) -> Unit = {},
    /** The source to open, decided at open time on the loader thread (the transcode offset). */
    private val atOpen: () -> TrackSource = { stream },
    /**
     * Called with the [atOpen] source and the response's Content-Type once it opened (the offset is pinned only
     * by a stream that opened; And only by a transcode).
     */
    private val onOpened: (TrackSource, String?) -> Unit = { _, _ -> },
) : BaseDataSource(true) {
    /**
     * Per occurrence: once the original replaced a rejected Opus request, later opens use it directly.
     * [requestedOffsetSeconds] > 0 asks the Opus transcode to start there when the server supports it. The
     * decision is pinned by the first open that succeeds and kept for every later open (retries, re-opens), so each
     * open that reads bytes of this occurrence reads the same stream. An open that failed (e.g. the capability query
     * and the stream both hit a flaky network) pins nothing: the retry asks again. The item's own [source] keeps
     * offset 0; only the opened stream carries it.
     */
    class Factory(
        source: TrackSource,
        private val remote: RemoteStreamResolver,
        private val requestedOffsetSeconds: Int = 0,
        private val onFallback: (TrackSource) -> Unit = {},
    ) : DataSource.Factory {
        @Volatile var source: TrackSource = source
            private set
        @Volatile private var offsetSeconds: Int? = if (requestedOffsetSeconds > 0) null else 0

        /**
         * Where the opened stream starts in the song: 0 until an open pinned the offset, without server support,
         * and after a fallback to the original (which ignores `timeOffset`).
         */
        val startOffsetUs: Long get() {
            val current = source
            return if (current is TrackSource.Remote && current.format == StreamFormat.OPUS_128)
                (offsetSeconds ?: 0) * 1_000_000L else 0
        }

        /** An offset was asked for and no open has pinned it yet, so the stream may still start there. */
        val mayStartAtOffset: Boolean get() {
            val current = source
            return current is TrackSource.Remote && current.format == StreamFormat.OPUS_128 && offsetSeconds == null
        }

        /** The source the next open asks for. The capability query may block; it runs without holding a lock. */
        internal fun opening(): TrackSource {
            val current = source
            if (current !is TrackSource.Remote || current.format != StreamFormat.OPUS_128) return current
            val seconds = offsetSeconds ?: TranscodeOffset.appliedSeconds(requestedOffsetSeconds,
                try { remote.supportsTranscodeOffset() } catch (_: Exception) { false })
            return if (seconds > 0) current.copy(timeOffsetSeconds = seconds) else current
        }

        /** [source] opened with [contentType]: the first open that succeeded pins its offset for the occurrence. */
        internal fun opened(source: TrackSource, contentType: String? = null) {
            if (source !is TrackSource.Remote || source.format != StreamFormat.OPUS_128) return
            // Navidrome without a usable transcoder answers an offset request successfully with the original,
            // which ignores `timeOffset` and starts at 0. Its own type gives it away (the shared arrival policy): no
            // offset, and the occurrence uses the original from now on, as after a rejected Opus request.
            if (source.timeOffsetSeconds > 0 &&
                ArrivalDetector.kind(StreamFormat.OPUS_128, contentType, ByteArray(0)) == StreamFormat.RAW) {
                val original = source.copy(format = StreamFormat.RAW, timeOffsetSeconds = 0)
                if (fellBack(original)) onFallback(original)
                return
            }
            synchronized(this) { if (offsetSeconds == null) offsetSeconds = source.timeOffsetSeconds }
        }

        /** An Opus request was rejected and [fallback] (the original) read instead; false when already in use. */
        internal fun fellBack(fallback: TrackSource.Remote): Boolean =
            synchronized(this) { (source != fallback).also { source = fallback } }

        override fun createDataSource(): DataSource = TrackByteSource(source, remote, { fallback ->
            if (fellBack(fallback)) onFallback(fallback)
        }, ::opening, ::opened)
    }
    private var upstream: DataSource? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val source = atOpen()
        try {
            val file = when (source) {
                is TrackSource.CachedFile -> java.io.File(source.path)
                is TrackSource.Remote -> null
            }
            val uri: Uri
            if (file != null) {
                // Opening a file (also the gapless pre-load of the next one) is not "played".
                upstream = FileDataSource(); uri = Uri.fromFile(file)
            } else {
                source as TrackSource.Remote
                dev.coda.android.diag.BatteryLog.count(dev.coda.android.diag.BatteryIds.STREAM_OPENS)
                if (dataSpec.position > 0) dev.coda.android.diag.BatteryLog.event(dev.coda.android.diag.BatteryIds.EV_STREAM_REOPEN, dataSpec.position / 1024)
                upstream = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(dev.coda.android.net.HttpClientFactory.client).createDataSource()
                uri = remote.resolve(source)
                // An Opus transcode is asked with a Range like any stream (it used to be re-read from byte 0).
                // Navidrome serves a fresh transcode chunked and ignores the Range (200: OkHttpDataSource reads through
                // the offset, as before); a cached transcode has a length and honours it (206), so Exo's Ogg duration
                // probe at the end of the file and loader retries no longer download the whole file again.
            }
            remaining = upstream!!.open(dataSpec.withUri(uri))
            var type: String? = null
            if (file == null) {
                type = upstream!!.responseHeaders.entries.firstOrNull { it.key.equals("content-type", true) }?.value?.joinToString().orEmpty()
                if (type.contains("json") || type.contains("xml")) {
                    val bytes = ByteArray(4096)
                    val count = upstream!!.read(bytes, 0, bytes.size).coerceAtLeast(0)
                    throw TrackReadException(streamBodyFailure(String(bytes, 0, count)))
                }
            }
            opened = true; transferStarted(dataSpec)
            onOpened(source, type?.takeIf { it.isNotEmpty() }) // no Content-Type: what was requested
            if (file == null && dataSpec.position > 0 && (upstream as? HttpDataSource)?.responseCode == 200)
                dev.coda.android.diag.BatteryLog.count(dev.coda.android.diag.BatteryIds.STREAM_SKIP_BYTES, dataSpec.position)
            return remaining
        } catch (error: Exception) {
            close()
            val rejectedFormat = (error is HttpDataSource.InvalidResponseCodeException && error.responseCode in listOf(400, 406, 415, 501)) ||
                (error is TrackReadException && error.failure == PlaybackFailure.Decode)
            if (source is TrackSource.Remote && source.format == StreamFormat.OPUS_128 && rejectedFormat) {
                // Navidrome installations without an Opus transcoder use the original for this item.
                android.util.Log.i("CodaPlayback", "Opus unavailable; using original audio")
                val original = source.copy(format = StreamFormat.RAW, timeOffsetSeconds = 0)
                val fallback = TrackByteSource(original, remote)
                upstream = fallback
                remaining = fallback.open(dataSpec)
                opened = true; transferStarted(dataSpec)
                onFallback(original)
                return remaining
            }
            throw error
        }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val count = upstream!!.read(buffer, offset, length)
        if (count > 0) {
            if (remaining != C.LENGTH_UNSET.toLong()) remaining -= count
            bytesTransferred(count)
        }
        return count
    }
    override fun getUri(): Uri? = null // Authenticated upstream locations never escape into diagnostics.
    override fun close() {
        try { upstream?.close() } finally {
            upstream = null
            if (opened) { opened = false; transferEnded() }
        }
    }
}
