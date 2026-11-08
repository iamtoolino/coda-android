package dev.coda.android.cache

import dev.coda.core.cache.*
import dev.coda.core.connectivity.ConnectivityRecovery
import dev.coda.core.playback.*
import dev.coda.android.playback.RemoteStreamResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * Full-speed complete-file downloads into the one cache, independently of the player's read rate.
 *
 * Free space is checked before each download (the 1 GiB reserve, silent oldest-first eviction; the read-ahead
 * window may use the reserve). Originals have an exact length and accept `Range`: their space is reserved up front
 * and an interrupted download resumes from its partial file. A fresh Opus transcode has neither: space is checked
 * against the 16 KB/s estimate. An interrupted transcode is resumed with Range as well (Navidrome finishes it
 * into its transcode cache and then honours ranges, after an overlap check); a server that ignores the Range (200)
 * sends the whole file again, which restarts the partial file from zero.
 */
class Prefetcher(
    private val client: OkHttpClient,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /**
     * One download. [addedAt] dates a new record in wall-clock ms (null: now, as for the read-ahead). It makes room
     * strictly oldest first; [protect] are the songs of its own add or window, which it never evicts.
     */
    data class Request(
        val songId: String,
        val format: StreamFormat,
        val song: SongFormat = SongFormat(),
        val meta: TrackMeta? = null,
        val addedAt: Long? = null,
        val protect: Set<String> = emptySet(),
        val window: Boolean = true,
    ) {
        val key get() = TrackKey(songId, format)
    }
    data class Result(val stored: Boolean = false, val noRoomBytes: Long = 0, val incomplete: Boolean = false) {
        val budgetReached get() = noRoomBytes > 0
    }
    private val fetchLock = kotlinx.coroutines.sync.Mutex()
    private var cacheDirectory: java.io.File? = null
    private val unavailable: MutableSet<TrackKey> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private data class Retry(val attempt: Int, val atMs: Long)
    private val retries = mutableMapOf<TrackKey, Retry>()
    private var window: Set<String>? = null
    /** Opus transcodes that came whole from the server twice over but not as a complete Ogg stream; no more tries until the network changes. */
    private val incomplete = mutableSetOf<TrackKey>()

    /** Song IDs still wanted by the rolling window or an add; departed songs stop retrying. */
    @Synchronized fun updateWindow(songIds: Collection<String>) {
        val songs = songIds.toSet()
        window = songs
        retries.keys.retainAll { it.songId in songs }
        incomplete.retainAll { it.songId in songs }
    }

    private var networkIdentity: String? = null

    /**
     * Retries restart on any change. "Incomplete" tracks are tried again only after a real network switch: [identity] names the active
     * network and transports (null: unknown, treated as a switch), so RSSI and bandwidth updates do not clear it.
     */
    @Synchronized fun networkChanged(identity: String? = null) {
        retries.clear()
        if (identity == null || identity != networkIdentity) incomplete.clear()
        networkIdentity = identity
    }

    /** The server said it doesn't have this song (404): the download counts as done, not pending. */
    fun unavailable(songId: String) = unavailable.any { it.songId == songId }

    /** The server's copy came whole but cut, twice: failed for this run (until a real network switch), not pending. */
    @Synchronized fun isIncomplete(songId: String) = incomplete.any { it.songId == songId }

    /** Bound the service's housekeeping sleep ([idleMs] at most) by the earliest pending deadline. */
    @Synchronized fun retryDelayMs(idleMs: Long = 1000): Long = retries.values.minOfOrNull { it.atMs - nowMs() }
        ?.coerceIn(1, idleMs) ?: idleMs

    suspend fun fetch(cache: TrackCache, key: TrackKey, remote: RemoteStreamResolver, bulk: Boolean): Boolean =
        fetch(cache, key, { source -> remote.resolve(source).toString() }, bulk)

    internal suspend fun fetch(cache: TrackCache, key: TrackKey, remote: (TrackSource.Remote) -> String, bulk: Boolean = false): Boolean =
        fetchResult(cache, Request(key.songId, key.format, window = !bulk), remote).stored

    suspend fun fetchResult(cache: TrackCache, request: Request, remote: (TrackSource.Remote) -> String): Result =
        fetchLock.withLock { withContext(Dispatchers.IO) {
        val key = request.key
        synchronized(this@Prefetcher) {
            if (cacheDirectory != cache.directory) { unavailable.clear(); retries.clear(); incomplete.clear(); cacheDirectory = cache.directory }
        }
        // Never downloaded again or downgraded: only an Opus copy may be replaced by the original.
        val cached = cache.kind(key.songId)
        if (cached == StreamFormat.RAW || cached == key.format) {
            synchronized(this@Prefetcher) { retries.remove(key) }
            return@withContext Result(stored = true)
        }
        if (isUnavailable(key)) return@withContext Result()
        synchronized(this@Prefetcher) {
            if (key in incomplete || window?.contains(key.songId) == false || nowMs() < (retries[key]?.atMs ?: Long.MIN_VALUE))
                return@withContext Result()
        }
        val result = download(cache, request, remote)
        synchronized(this@Prefetcher) {
            // No room is checked again before the next attempt (cheap, no request), so it isn't a retry.
            if (result.incomplete) { incomplete += key; retries.remove(key) }
            else if (result.stored || isUnavailable(key) || result.budgetReached) retries.remove(key)
            else if (window?.contains(key.songId) != false) {
                val attempt = (retries[key]?.attempt?.plus(1) ?: 0).coerceAtMost(5)
                retries[key] = Retry(attempt, nowMs() + ConnectivityRecovery.retryDelayMs(attempt))
            }
        }
        result
    } }

    private fun isUnavailable(key: TrackKey) = key in unavailable || key.copy(format = StreamFormat.RAW) in unavailable

    private sealed interface Outcome {
        data object Stored : Outcome
        data object Failed : Outcome
        data object UseOriginal : Outcome
        /** A fresh Opus download that is not a complete Ogg stream (cut short on the server): discarded, not retried. */
        data object Incomplete : Outcome
        data class NoRoom(val bytes: Long) : Outcome
    }

    /** Any storage or transport error is a pending failure; only cancellation escapes. */
    private suspend fun download(cache: TrackCache, request: Request, remote: (TrackSource.Remote) -> String): Result {
        val outcome = try {
            // begin() throws on cache-file creation failures (missing directory, full disk).
            val writer = cache.begin(request.key) ?: return Result()
            try { transfer(cache, request, writer, remote) } finally { writer.close() }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Outcome.Failed }
        return when (outcome) {
            Outcome.Stored -> Result(stored = true)
            Outcome.Failed -> Result()
            Outcome.Incomplete -> Result(incomplete = true)
            is Outcome.NoRoom -> Result(noRoomBytes = outcome.bytes.coerceAtLeast(1))
            Outcome.UseOriginal -> download(cache, request.copy(format = StreamFormat.RAW), remote)
        }
    }

    /** Admitted for [writer] in the shared cache, so another Prefetcher's admission counts these bytes as spent. */
    private fun room(cache: TrackCache, request: Request, writer: TrackCache.Write, bytes: Long) =
        cache.makeRoom(bytes, request.protect + request.songId, request.window, request.songId, writer)

    private suspend fun transfer(cache: TrackCache, request: Request, writer: TrackCache.Write,
        remote: (TrackSource.Remote) -> String): Outcome = coroutineScope {
        val key = request.key
        val estimate = request.song.estimate(key.format)
        val resumeFrom = writer.resumeFrom
        val expected = (estimate - resumeFrom).coerceAtLeast(0)
        if (!room(cache, request, writer, expected)) return@coroutineScope Outcome.NoRoom(expected)
        val builder = okhttp3.Request.Builder().url(remote(TrackSource.Remote(key.songId, key.format)))
        // A transcode resumes too, from a little earlier: the overlap must match what we have, so a different
        // transcode of the song (another encoder run) is never spliced onto the partial file.
        val overlap = if (key.format == StreamFormat.OPUS_128) minOf(resumeFrom, OVERLAP_BYTES).toInt() else 0
        val rangeFrom = resumeFrom - overlap
        val saved = if (resumeFrom > 0) Identity.parse(writer.identity) else Identity()
        if (resumeFrom > 0) {
            builder.header("Range", "bytes=$rangeFrom-")
            // The server answers 200 (the whole file) instead of 206 when its file is not the one we started with.
            saved.ifRange()?.let { builder.header("If-Range", it) }
        }
        val call = client.newCall(builder.build())
        // Blocking OkHttp reads ignore coroutine cancellation; cancelling the call unblocks them at once.
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
        try {
            call.execute().use { response ->
                if (response.code == 404) unavailable += key
                if (key.format == StreamFormat.OPUS_128 && response.code in listOf(400, 406, 415, 501)) return@coroutineScope Outcome.UseOriginal
                if (response.code == 416) { writer.restart(); return@coroutineScope Outcome.Failed }
                if (!response.isSuccessful) return@coroutineScope Outcome.Failed
                val body = response.body ?: return@coroutineScope Outcome.Failed
                val contentType = body.contentType()?.toString()
                val type = body.contentType()?.subtype.orEmpty()
                if (type.contains("json") || type.contains("xml")) {
                    val failure = streamBodyFailure(body.byteStream().use { String(it.readNBytes(4096)) })
                    if (failure == PlaybackFailure.NotFound) unavailable += key
                    return@coroutineScope if (key.format == StreamFormat.OPUS_128 && failure == PlaybackFailure.Decode) Outcome.UseOriginal else Outcome.Failed
                }
                val resumedTotal = resumedTotal(response, rangeFrom, asked = resumeFrom > 0)
                if (response.code == 206 && resumedTotal == null) {
                    // A range we didn't ask for: start again from zero next time.
                    writer.restart(); return@coroutineScope Outcome.Failed
                }
                val seen = Identity.of(response, resumedTotal ?: body.contentLength().takeIf { it >= 0 })
                if (resumedTotal != null) {
                    // The file on the server is not the one we started: an ETag, Last-Modified or total that differs, or one
                    // shorter than what we already hold (the overlap below could not even be read). Start again from zero.
                    if (!saved.sameFile(seen) || resumedTotal < resumeFrom) { writer.restart(); return@coroutineScope Outcome.Failed }
                }
                if (resumedTotal != null && overlap > 0) {
                    val head = body.source().readByteArray(overlap.toLong())
                    if (!head.contentEquals(writer.readBack(resumeFrom, overlap))) {
                        writer.restart(); return@coroutineScope Outcome.Failed
                    }
                }
                // A 200 to a range request is the whole file again.
                if (resumedTotal == null && writer.written > 0) writer.restart()
                writer.identity = (if (resumedTotal != null) saved.merge(seen) else seen).encode()
                val length = resumedTotal ?: body.contentLength().takeIf { it >= 0 }
                if (length != null) {
                    val incoming = length - writer.written
                    if (incoming > expected && !room(cache, request, writer, incoming)) return@coroutineScope Outcome.NoRoom(incoming)
                    if (!writer.reserve(length)) return@coroutineScope Outcome.NoRoom(incoming)
                }
                // Without a length (an Opus transcode), space is checked again in small steps beyond the estimate.
                val step = (estimate / 4).coerceIn(MIN_EXTRA_BYTES, EXTRA_BYTES)
                var allowance = if (length != null) Long.MAX_VALUE else estimate
                var sinceCheckpoint = 0L
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        writer.output.write(buffer, 0, count)
                        dev.coda.android.diag.BatteryLog.count(dev.coda.android.diag.BatteryIds.PREFETCH_BYTES, count.toLong())
                        sinceCheckpoint += count
                        if (sinceCheckpoint >= CHECKPOINT_BYTES) { writer.checkpoint(); sinceCheckpoint = 0 }
                        if (writer.written > allowance) {
                            if (!room(cache, request, writer, step)) return@coroutineScope Outcome.NoRoom(writer.written)
                            allowance += step
                        }
                    }
                }
                ensureActive()
                if (length != null && writer.written != length) return@coroutineScope Outcome.Failed
                // An Opus file is published only when it is whole and consistent, resumed or fresh (a transcode the
                // server cut short is otherwise published one download later). A resumed one that is not is discarded and
                // the next attempt downloads it from zero; a fresh one is discarded and not asked for again until the
                // network changes, so a server that always ends early is not hit in a loop.
                if (key.format == StreamFormat.OPUS_128 && !writer.isCompleteOgg() && arrivesAsOpus(writer, contentType, request)) {
                    writer.restart(); return@coroutineScope if (resumedTotal != null) Outcome.Failed else Outcome.Incomplete
                }
                if (writer.commit(contentType, request.song, request.meta, request.addedAt)) {
                    dev.coda.android.diag.BatteryLog.count(dev.coda.android.diag.BatteryIds.PREFETCH_FILES); Outcome.Stored
                } else Outcome.Failed
            }
        } finally { watcher.cancel() }
    }

    /** The server may answer an Opus request with the original (pass-through): only what is stored as Opus must be an Ogg stream. */
    private fun arrivesAsOpus(writer: TrackCache.Write, contentType: String?, request: Request): Boolean =
        ArrivalDetector.kind(request.format, contentType, writer.readBack(minOf(writer.written, 256L), minOf(writer.written, 256L).toInt()), request.song) == StreamFormat.OPUS_128

    /** What the server said about its file; two requests that disagree on any part both state are not the same file. */
    private data class Identity(val etag: String = "", val lastModified: String = "", val total: Long? = null) {
        fun encode(): String? = "$etag\t$lastModified\t${total ?: ""}".takeIf { etag.isNotEmpty() || lastModified.isNotEmpty() || total != null }
        fun ifRange(): String? = etag.takeIf { it.isNotEmpty() && !it.startsWith("W/") } ?: lastModified.takeIf { it.isNotEmpty() }
        fun sameFile(other: Identity) = (etag.isEmpty() || other.etag.isEmpty() || etag == other.etag) &&
            (lastModified.isEmpty() || other.lastModified.isEmpty() || lastModified == other.lastModified) &&
            (total == null || other.total == null || total == other.total)
        /** What we saved, completed by what this response added. */
        fun merge(other: Identity) = Identity(etag.ifEmpty { other.etag }, lastModified.ifEmpty { other.lastModified }, total ?: other.total)

        companion object {
            fun parse(text: String?): Identity {
                val parts = text?.split('\t').orEmpty()
                return Identity(parts.getOrNull(0).orEmpty(), parts.getOrNull(1).orEmpty(), parts.getOrNull(2)?.toLongOrNull())
            }
            fun of(response: okhttp3.Response, total: Long?) = Identity(response.header("ETag").orEmpty().replace('\t', ' '),
                response.header("Last-Modified").orEmpty().replace('\t', ' '), total)
        }
    }

    /** The total length when the server continued our range exactly (from [resumeFrom], which may be 0 when asked for); null otherwise. */
    private fun resumedTotal(response: okhttp3.Response, resumeFrom: Long, asked: Boolean): Long? {
        if (!asked || response.code != 206) return null
        val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").find(response.header("Content-Range").orEmpty()) ?: return null
        val (start, _, total) = range.destructured
        return total.toLong().takeIf { start.toLong() == resumeFrom }
    }

    private companion object {
        const val CHECKPOINT_BYTES = 4L * 1024 * 1024
        /** A resumed transcode re-reads this much before the resume point and compares it. */
        const val OVERLAP_BYTES = 4L * 1024
        /** Space beyond an Opus estimate is checked in steps of a quarter estimate, 64 KB to 1 MB. */
        const val EXTRA_BYTES = 1L * 1024 * 1024
        const val MIN_EXTRA_BYTES = 64L * 1024
    }
}
