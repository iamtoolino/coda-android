package dev.coda.android.cache

import dev.coda.core.testing.memoryTrackCache
import dev.coda.core.cache.CacheRules
import dev.coda.core.cache.SongFormat
import dev.coda.core.cache.TrackCache
import dev.coda.core.cache.TrackKey
import dev.coda.core.testing.FakeFreeSpace
import dev.coda.core.playback.StreamFormat
import dev.coda.core.playback.TrackSource
import dev.coda.core.testing.FakeSubsonicServer
import dev.coda.core.testing.OggFixture
import dev.coda.core.testing.SyntheticLibrary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Level
import java.util.logging.Logger

@OptIn(ExperimentalCoroutinesApi::class)
class PrefetcherTest {
    @get:Rule val temporary = TemporaryFolder()
    private val first = TrackKey("track-1", StreamFormat.RAW)
    private fun audio(cache: TrackCache) = cache.directory.listFiles()!!.filter { it.isFile && (it.name.endsWith(".orig") || it.name.endsWith(".opus") || it.name.contains(".part")) }
    private val second = TrackKey("track-2", StreamFormat.RAW)

    private class Downloads(val server: FakeSubsonicServer, nowMs: () -> Long) {
        val attempts = CopyOnWriteArrayList<Pair<Long, String>>()
        val urls: (TrackSource.Remote) -> String = { source ->
            attempts += nowMs() to source.songId
            server.authenticatedUrl("stream") + "&id=${source.songId}&format=" +
                if (source.format == StreamFormat.RAW) "raw" else "opus"
        }
        init { Logger.getLogger("okhttp3.mockwebserver.MockWebServer").level = Level.OFF }
    }

    @Test fun offlineDownloadsStayPendingRetryOnScheduleInQueueOrderAndCompleteAfterRecovery() = runTest {
        FakeSubsonicServer().use { server ->
            val cache = memoryTrackCache(temporary.newFolder())
            val prefetcher = Prefetcher(OkHttpClient()) { testScheduler.currentTime }
            val downloads = Downloads(server) { testScheduler.currentTime }
            val order = listOf(first, second)
            prefetcher.updateWindow(order.map { it.songId })
            suspend fun cycle() = order.forEach { prefetcher.fetch(cache, it, downloads.urls, false) }
            server.goOffline()
            cycle()
            val expected = mutableListOf(0L to first.songId, 0L to second.songId)
            for (seconds in listOf(1L, 2, 4, 8, 16, 30, 30, 30)) {
                advanceTimeBy(seconds * 1000 - 1)
                cycle()
                assertEquals("A pending download retried before its deadline", expected, downloads.attempts)
                assertEquals(1L, prefetcher.retryDelayMs())
                advanceTimeBy(1)
                cycle()
                expected += testScheduler.currentTime to first.songId
                expected += testScheduler.currentTime to second.songId
                assertEquals(expected, downloads.attempts)
                assertNull(cache.find(first)); assertNull(cache.find(second))
                assertTrue(audio(cache).isEmpty())
            }
            server.goOnline()
            advanceTimeBy(30_000)
            cycle()
            for (key in order) {
                val track = SyntheticLibrary.albums.flatMap { it.tracks }.first { it.id == key.songId }
                assertArrayEquals(track.bytes("raw"), cache.find(key)!!.readBytes())
            }
            val completedCount = downloads.attempts.size
            advanceTimeBy(60_000)
            cycle()
            assertEquals("Completed downloads were fetched again", completedCount, downloads.attempts.size)
        }
    }

    @Test fun networkCallbackRetriesImmediatelyAndResetsBackoff() = runTest {
        FakeSubsonicServer().use { server ->
            val cache = memoryTrackCache(temporary.newFolder())
            val prefetcher = Prefetcher(OkHttpClient()) { testScheduler.currentTime }
            val downloads = Downloads(server) { testScheduler.currentTime }
            prefetcher.updateWindow(listOf(first).map { it.songId })
            suspend fun fetch() = prefetcher.fetch(cache, first, downloads.urls, false)
            server.goOffline()
            fetch()
            advanceTimeBy(1000); fetch()
            advanceTimeBy(2000); fetch()
            advanceTimeBy(125)
            prefetcher.networkChanged()
            assertFalse(fetch())
            assertEquals(listOf(0L, 1000L, 3000L, 3125L), downloads.attempts.map { it.first })
            advanceTimeBy(999); fetch()
            assertEquals(4, downloads.attempts.size)
            advanceTimeBy(1); fetch()
            assertEquals(4125L, downloads.attempts.last().first)
            server.goOnline()
            prefetcher.networkChanged()
            assertTrue(fetch())
            assertNotNull(cache.find(first))
        }
    }

    @Test fun leavingWindowCancelsPendingRetryAndReentryStartsFresh() = runTest {
        FakeSubsonicServer().use { server ->
            val cache = memoryTrackCache(temporary.newFolder())
            val prefetcher = Prefetcher(OkHttpClient()) { testScheduler.currentTime }
            val downloads = Downloads(server) { testScheduler.currentTime }
            prefetcher.updateWindow(listOf(first, second).map { it.songId })
            server.goOffline()
            prefetcher.fetch(cache, first, downloads.urls, false)
            prefetcher.fetch(cache, second, downloads.urls, false)
            prefetcher.updateWindow(listOf(second).map { it.songId })
            advanceTimeBy(60_000)
            assertFalse(prefetcher.fetch(cache, first, downloads.urls, false))
            prefetcher.fetch(cache, second, downloads.urls, false)
            prefetcher.networkChanged()
            assertFalse(prefetcher.fetch(cache, first, downloads.urls, false))
            assertEquals(listOf(first.songId, second.songId, second.songId), downloads.attempts.map { it.second })
            prefetcher.updateWindow(listOf(first, second).map { it.songId })
            server.goOnline()
            assertTrue(prefetcher.fetch(cache, first, downloads.urls, false))
            assertNotNull(cache.find(first))
        }
    }

    @Test fun onlyNotFoundIsUnavailableAndNetworkCallbackDoesNotRetryIt() = runTest {
        FakeSubsonicServer().use { server ->
            val cache = memoryTrackCache(temporary.newFolder())
            val prefetcher = Prefetcher(OkHttpClient()) { testScheduler.currentTime }
            val downloads = Downloads(server) { testScheduler.currentTime }
            val missing = TrackKey("missing", StreamFormat.RAW)
            prefetcher.updateWindow(listOf(missing, first).map { it.songId })
            assertFalse(prefetcher.fetch(cache, missing, downloads.urls, false))
            server.rejectedEndpoints = setOf("stream") // Unauthorized is still pending in the prefetcher.
            assertFalse(prefetcher.fetch(cache, first, downloads.urls, false))
            advanceTimeBy(1000)
            assertFalse(prefetcher.fetch(cache, first, downloads.urls, false))
            server.rejectedEndpoints = emptySet()
            prefetcher.networkChanged()
            assertFalse(prefetcher.fetch(cache, missing, downloads.urls, false))
            assertTrue(prefetcher.fetch(cache, first, downloads.urls, false))
            assertEquals(listOf(missing.songId, first.songId, first.songId, first.songId), downloads.attempts.map { it.second })
            assertNull(cache.find(missing))
        }
    }

    @Test fun cacheFileCreationFailureIsAPendingRetryNotACrash() = runTest {
        FakeSubsonicServer().use { server ->
            val directory = java.io.File(temporary.root, "tracks")
            val cache = memoryTrackCache(directory)
            val prefetcher = Prefetcher(OkHttpClient()) { testScheduler.currentTime }
            val downloads = Downloads(server) { testScheduler.currentTime }
            prefetcher.updateWindow(listOf(first.songId))
            directory.deleteRecursively(); directory.writeText("storage unavailable")
            assertFalse(prefetcher.fetch(cache, first, downloads.urls, false))
            advanceTimeBy(400)
            assertEquals("The failed download is no longer pending", 600L, prefetcher.retryDelayMs())
            assertFalse(prefetcher.fetch(cache, first, downloads.urls, false)) // Before its deadline.
            directory.delete() // Storage comes back; begin() recreates the directory.
            advanceTimeBy(600)
            assertTrue(prefetcher.fetch(cache, first, downloads.urls, false))
            assertArrayEquals(SyntheticLibrary.albums.first().tracks.first().bytes("raw"), cache.find(first)!!.readBytes())
        }
    }

    @Test fun freeSpaceIsCheckedBeforeEachDownloadWithoutARequest() = runTest {
        for (mode in FakeSubsonicServer.StreamMode.entries) FakeSubsonicServer(streamMode = mode).use { server ->
            val folder = temporary.newFolder()
            val space = FakeFreeSpace(folder, 64L shl 30)
            val cache = memoryTrackCache(folder, space)
            val prefetcher = Prefetcher(OkHttpClient()) { testScheduler.currentTime }
            val downloads = Downloads(server) { testScheduler.currentTime }
            prefetcher.updateWindow(listOf(first.songId))
            space.setFree(dev.coda.core.cache.CachePolicy.DEFAULT.keepFreeBytes)
            val background = Prefetcher.Request(first.songId, StreamFormat.RAW, SongFormat("flac", 900, 2), window = false)
            repeat(3) {
                assertTrue(prefetcher.fetchResult(cache, background, downloads.urls).budgetReached)
                advanceTimeBy(30_000)
            }
            assertEquals("A full disk must not cost requests", 0, downloads.attempts.size)
            assertTrue(audio(cache).isEmpty())
            // The read-ahead window may use the reserve, down to Android's own line.
            assertTrue(prefetcher.fetchResult(cache, background.copy(window = true), downloads.urls).stored)
            assertEquals(1, downloads.attempts.size)
        }
    }

    /**
     * Two downloaders (read-ahead and the offline downloader each have their own Prefetcher) whose
     * responses are held until both admissions ran must not both spend the same free space: the second is refused, the
     * phone stays above the line, and no kept set is evicted afterwards.
     */
    @Test fun twoDownloadersNeverSpendTheSameFreeSpace() = kotlinx.coroutines.runBlocking {
        FakeSubsonicServer().use { server ->
            val folder = temporary.newFolder()
            val space = FakeFreeSpace(folder, 64L shl 30)
            val cache = memoryTrackCache(folder, space)
            val line = dev.coda.core.cache.CachePolicy.DEFAULT.keepFreeBytes
            val tracks = SyntheticLibrary.albums.flatMap { it.tracks }
            val sizes = listOf(first, second).map { key -> tracks.first { it.id == key.songId }.bytes("raw").size.toLong() }
            listOf(first, second).forEachIndexed { i, key ->
                cache.renew(dev.coda.core.cache.KeptItem("album/$i", listOf(dev.coda.core.cache.KeptTrack(key.songId)), "Album $i"))
            }
            // Room above the line for either download, not for both.
            space.setFree(line + sizes.max())
            val arrived = java.util.concurrent.CountDownLatch(2)
            server.beforeRequest = { url ->
                if (url.encodedPath.endsWith("/stream.view") || url.encodedPath.endsWith("/stream")) {
                    arrived.countDown(); arrived.await(3, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
            val downloads = Downloads(server) { 0L }
            val results = listOf(first, second).mapIndexed { i, key ->
                val prefetcher = Prefetcher(OkHttpClient()) { 0L }.apply { updateWindow(listOf(key.songId)) }
                async(kotlinx.coroutines.Dispatchers.IO) {
                    prefetcher.fetchResult(cache, Prefetcher.Request(key.songId, StreamFormat.RAW, SongFormat("flac", 900, 2, sizes[i]),
                        protect = setOf(key.songId), window = false), downloads.urls)
                }
            }.map { it.await() }
            assertEquals("one stored, the other refused for room", 1, results.count { it.stored })
            assertEquals(1, results.count { it.budgetReached })
            assertTrue("the phone stays above the line", space.freeBytes() >= line)
            cache.housekeep()
            assertTrue("no kept set evicted", cache.isAdded("album/0") && cache.isAdded("album/1"))
        }
    }

    /** Round 8: the window writes a kept set's own song only above the line; with no room it streams and nothing is requested. */
    @Test fun theWindowDoesNotWriteAKeptSetsSongBelowTheLine() = runTest {
        FakeSubsonicServer().use { server ->
            val folder = temporary.newFolder()
            val space = FakeFreeSpace(folder, 64L shl 30)
            val cache = memoryTrackCache(folder, space)
            val prefetcher = Prefetcher(OkHttpClient()) { testScheduler.currentTime }
            val downloads = Downloads(server) { testScheduler.currentTime }
            prefetcher.updateWindow(listOf(first.songId))
            cache.renew(dev.coda.core.cache.KeptItem("album/a", listOf(dev.coda.core.cache.KeptTrack(first.songId)), "A"))
            space.setFree(dev.coda.core.cache.CachePolicy.DEFAULT.keepFreeBytes)
            val window = Prefetcher.Request(first.songId, StreamFormat.RAW, SongFormat("flac", 900, 2), window = true)
            val result = prefetcher.fetchResult(cache, window, downloads.urls)
            assertFalse(result.stored)
            assertEquals("no request for a write that cannot happen", 0, downloads.attempts.size)
            assertTrue(audio(cache).isEmpty())
        }
    }

    @Test fun originalsReserveTheirLengthAndResumeFromThePartialFileWithRange() = runTest {
        FakeSubsonicServer().use { server ->
            val folder = temporary.newFolder()
            val space = FakeFreeSpace(folder, 64L shl 30)
            val cache = memoryTrackCache(folder, space)
            val prefetcher = Prefetcher(OkHttpClient()) { testScheduler.currentTime }
            val downloads = Downloads(server) { testScheduler.currentTime }
            val bytes = SyntheticLibrary.albums.first().tracks.first().bytes("raw")
            // An interrupted download left 10 000 confirmed bytes.
            cache.begin(first)!!.use { it.output.write(bytes, 0, 10_000); it.checkpoint() }
            assertTrue(prefetcher.fetch(cache, first, downloads.urls))
            assertEquals(listOf(FakeSubsonicServer.StreamRequest("track-1", "raw", "bytes=10000-")), server.streamRequests)
            assertArrayEquals(bytes, cache.find(first)!!.readBytes())
            assertEquals(listOf(bytes.size.toLong()), space.allocations)
            assertEquals("audio/flac", cache.record("track-1")!!.contentType)
        }
    }

    @Test fun aServerIgnoringTheRangeStartsAgainFromZero() = runTest {
        FakeSubsonicServer().use { server ->
            server.ignoreRange = true
            val cache = memoryTrackCache(temporary.newFolder())
            val prefetcher = Prefetcher(OkHttpClient()) { testScheduler.currentTime }
            val downloads = Downloads(server) { testScheduler.currentTime }
            val bytes = SyntheticLibrary.albums.first().tracks.first().bytes("raw")
            cache.begin(first)!!.use { it.output.write(ByteArray(10_000)); it.checkpoint() }
            assertTrue(prefetcher.fetch(cache, first, downloads.urls))
            assertArrayEquals(bytes, cache.find(first)!!.readBytes())
        }
    }

    /** A fresh transcode (the fake server's chunked Opus) ignores the Range: the partial file restarts from zero. */
    @Test fun aFreshOpusTranscodeIgnoringTheRangeRestartsFromZeroAndIsStoredAsOpus() = runTest {
        FakeSubsonicServer().use { server ->
            val cache = memoryTrackCache(temporary.newFolder())
            val prefetcher = Prefetcher(OkHttpClient()) { testScheduler.currentTime }
            val downloads = Downloads(server) { testScheduler.currentTime }
            val opus = TrackKey("track-1", StreamFormat.OPUS_128)
            cache.begin(opus)!!.use { it.output.write(ByteArray(5_000)) }
            assertTrue(prefetcher.fetch(cache, opus, downloads.urls))
            assertEquals("BAT-1b: resumed with an overlap", listOf(FakeSubsonicServer.StreamRequest("track-1", "opus", "bytes=904-")), server.streamRequests)
            assertEquals(StreamFormat.OPUS_128, cache.kind("track-1"))
            assertArrayEquals(SyntheticLibrary.albums.first().tracks.first().bytes("opus"), cache.resolve("track-1")!!.readBytes())
            // Never downloaded again, and never downgraded once the original is there.
            assertTrue(prefetcher.fetch(cache, opus, downloads.urls))
            assertEquals(1, server.streamRequests.size)
            assertTrue(prefetcher.fetch(cache, first, downloads.urls))
            assertEquals(StreamFormat.RAW, cache.kind("track-1"))
            assertTrue(prefetcher.fetch(cache, opus, downloads.urls))
            assertEquals(2, server.streamRequests.size)
        }
    }

    /**
     * The player may stream a transcode started at `timeOffset`, but the cache never holds one. The Prefetcher asks
     * for the song's whole transcode by its TrackKey (never an offset), and a partial file that is not the whole
     * transcode's head (e.g. the start of an offset stream) is not resumed: it is replaced from zero.
     */
    @Test fun theCacheNeverStoresOrResumesAnOffsetTranscode() = runTest {
        FakeSubsonicServer().use { server ->
            val bytes = SyntheticLibrary.albums.first().tracks.first().bytes("opus")
            val offset = bytes.copyOfRange(0, 10_000).also { it[9_000] = (it[9_000] + 1).toByte() }
            server.streamOverrides = mapOf("track-1" to (bytes to "audio/ogg"))
            server.offsetStreams = mapOf(("track-1" to 5) to offset)
            val cache = memoryTrackCache(temporary.newFolder())
            val prefetcher = Prefetcher(OkHttpClient()) { testScheduler.currentTime }
            val asked = CopyOnWriteArrayList<TrackSource.Remote>()
            val downloads = Downloads(server) { testScheduler.currentTime }
            val urls: (TrackSource.Remote) -> String = { asked += it; downloads.urls(it) }
            val opus = TrackKey("track-1", StreamFormat.OPUS_128)
            assertTrue(prefetcher.fetch(cache, opus, urls))
            assertTrue("never an offset source: $asked", asked.all { it.timeOffsetSeconds == 0 })
            assertTrue(server.streamRequests.all { it.timeOffset == null })
            assertArrayEquals(bytes, cache.resolve("track-1")!!.readBytes())

            // A partial file holding an offset stream's head (differs inside the overlap) is not continued as the whole file.
            val other = TrackKey("track-2", StreamFormat.OPUS_128)
            server.streamOverrides = mapOf("track-2" to (bytes to "audio/ogg"))
            cache.begin(other)!!.use { it.output.write(offset) }
            assertFalse(prefetcher.fetch(cache, other, urls))
            assertFalse(cache.contains("track-2"))
            advanceTimeBy(1_000)
            assertTrue(prefetcher.fetch(cache, other, urls))
            assertTrue(server.streamRequests.all { it.timeOffset == null })
            assertArrayEquals(bytes, cache.resolve("track-2")!!.readBytes())
        }
    }

    /**
     * Navidrome finishes an interrupted transcode into its cache and then honours ranges (QA server, see the
     * Report). The partial file resumes after a 4 KiB overlap check; a different transcode is never spliced on.
     */
    @Test fun anInterruptedTranscodeResumesFromTheServersCachedCopyOnlyWhenTheOverlapMatches() = runTest {
        FakeSubsonicServer().use { server ->
            val bytes = SyntheticLibrary.albums.first().tracks.first().bytes("opus")
            assertTrue(bytes.size > 12_000)
            server.streamOverrides = mapOf("track-1" to (bytes to "audio/ogg"))
            val cache = memoryTrackCache(temporary.newFolder())
            val prefetcher = Prefetcher(OkHttpClient()) { testScheduler.currentTime }
            val downloads = Downloads(server) { testScheduler.currentTime }
            val opus = TrackKey("track-1", StreamFormat.OPUS_128)
            cache.begin(opus)!!.use { it.output.write(bytes, 0, 10_000) }
            assertTrue(prefetcher.fetch(cache, opus, downloads.urls))
            assertEquals(listOf(FakeSubsonicServer.StreamRequest("track-1", "opus", "bytes=5904-")), server.streamRequests)
            assertArrayEquals(bytes, cache.resolve("track-1")!!.readBytes())

            // Another transcode of the song: its bytes differ inside the overlap, so nothing is appended.
            val other = TrackKey("track-2", StreamFormat.OPUS_128)
            server.streamOverrides = mapOf("track-2" to (bytes to "audio/ogg"))
            val foreign = bytes.copyOf(10_000).also { it[9_000] = (it[9_000] + 1).toByte() }
            cache.begin(other)!!.use { it.output.write(foreign) }
            assertFalse(prefetcher.fetch(cache, other, downloads.urls))
            assertFalse(cache.contains("track-2"))
            advanceTimeBy(1_000)
            assertTrue(prefetcher.fetch(cache, other, downloads.urls))
            assertNull("Then from zero", server.streamRequests.last().range)
            assertArrayEquals(bytes, cache.resolve("track-2")!!.readBytes())
        }
    }

    // ---- BAT-1b fix round 1: a resumed or range-continued file is published only when it is whole and consistent ----

    private class Resume(val server: FakeSubsonicServer, val newCache: () -> TrackCache, val prefetcher: Prefetcher, val downloads: Downloads) {
        var cache = newCache()
        /** A new, empty cache (a song cannot be deleted from one). */
        fun reset() { cache = newCache() }
        val key = TrackKey("resume-1", StreamFormat.OPUS_128)
        fun partial(bytes: ByteArray, upTo: Int, identity: String? = null) =
            cache.begin(key)!!.use { it.output.write(bytes, 0, upTo); it.identity = identity }
        fun serve(bytes: ByteArray, etag: String? = null) {
            server.streamOverrides = mapOf(key.songId to (bytes to "audio/ogg"))
            server.streamEtags = if (etag != null) mapOf(key.songId to etag) else emptyMap()
        }
        fun resumeFrom() = cache.begin(key)!!.use { it.resumeFrom }
        fun cached() = if (cache.contains(key.songId)) cache.resolve(key.songId)!!.readBytes() else null
    }

    private suspend fun kotlinx.coroutines.test.TestScope.resuming(block: suspend kotlinx.coroutines.test.TestScope.(Resume) -> Unit) {
        FakeSubsonicServer().use { server ->
            val prefetcher = Prefetcher(OkHttpClient()) { testScheduler.currentTime }
            block(Resume(server, { memoryTrackCache(temporary.newFolder()) }, prefetcher, Downloads(server) { testScheduler.currentTime }))
        }
    }

    /** Two valid encodes of the same song: identical audio in the first big page (so the 4 KiB overlap matches), another stream after it. */
    private val encodeA = OggFixture.transcode(serial = 1, seed = 5)
    private val encodeB = OggFixture.transcode(serial = 2, seed = 5)

    @Test fun aTranscodeThatChangedBetweenRequestsIsDiscardedEvenWhenTheOverlapMatches() = runTest {
        resuming { r ->
            // The same bytes at 5904..9999 (inside one page), but the rest is another stream: valid pages, mixed serials.
            assertArrayEquals(encodeA.copyOfRange(5_904, 10_000), encodeB.copyOfRange(5_904, 10_000))
            assertFalse(encodeA.contentEquals(encodeB))
            r.partial(encodeA, 10_000); r.serve(encodeB)
            assertFalse(r.prefetcher.fetch(r.cache, r.key, r.downloads.urls))
            assertNull("never published", r.cached())
            assertEquals("the partial is dropped", 0L, r.resumeFrom())
            advanceTimeBy(1_000)
            assertTrue(r.prefetcher.fetch(r.cache, r.key, r.downloads.urls))
            assertNull("then from zero", r.server.streamRequests.last().range)
            assertArrayEquals(encodeB, r.cached())
        }
    }

    @Test fun aTranscodeCopyTruncatedOnTheServerIsNeverPublished() = runTest {
        resuming { r ->
            val cut = encodeA.copyOf(encodeA.size - 25_000)   // ends inside a page: the server's own copy is incomplete
            r.partial(encodeA, 10_000); r.serve(cut)
            assertFalse(r.prefetcher.fetch(r.cache, r.key, r.downloads.urls))
            assertNull(r.cached())
            assertEquals(0L, r.resumeFrom())
            // A cut at a page boundary has no end-of-stream page.
            val pageCut = encodeA.copyOf(encodeA.size - 20_106)
            r.partial(encodeA, 10_000); r.serve(pageCut)
            advanceTimeBy(1_000)
            assertFalse(r.prefetcher.fetch(r.cache, r.key, r.downloads.urls))
            assertNull(r.cached())
        }
    }

    @Test fun aFreshTranscodeCutShortOnTheServerIsNotPublishedByTheRetryEitherAndNotAskedAgainInALoop() = runTest {
        resuming { r ->
            val cut = encodeA.copyOf(encodeA.size - 25_000)
            r.partial(encodeA, 10_000); r.serve(cut)
            assertFalse("the resumed copy is rejected", r.prefetcher.fetch(r.cache, r.key, r.downloads.urls))
            assertEquals(0L, r.resumeFrom())
            advanceTimeBy(1_000)
            // The retry starts from zero and the server sends the cut copy whole (a complete 200, or chunked to its end).
            assertFalse("the retry's cut copy is rejected too", r.prefetcher.fetch(r.cache, r.key, r.downloads.urls))
            assertNull("never published", r.cached())
            assertEquals("the partial is discarded", 0L, r.resumeFrom())
            val requests = r.server.streamRequests.size
            assertNull("the retry was a fresh download", r.server.streamRequests.last().range)
            advanceTimeBy(3_600_000)
            assertFalse(r.prefetcher.fetch(r.cache, r.key, r.downloads.urls))
            assertEquals("no tight loop, not asked again", requests, r.server.streamRequests.size)
            // A network change allows one more try, and a whole copy is published.
            r.serve(encodeA); r.prefetcher.networkChanged()
            assertTrue(r.prefetcher.fetch(r.cache, r.key, r.downloads.urls))
            assertArrayEquals(encodeA, r.cached())
        }
    }

    /** RSSI / bandwidth updates (same network identity) keep a track "incomplete"; only a real switch retries it. */
    @Test fun anIncompleteTrackIsRetriedOnlyAfterARealNetworkSwitch() = runTest {
        resuming { r ->
            val cut = encodeA.copyOf(encodeA.size - 25_000)
            r.prefetcher.networkChanged("wifi-1")
            r.partial(encodeA, 10_000); r.serve(cut)
            assertFalse(r.prefetcher.fetch(r.cache, r.key, r.downloads.urls))
            advanceTimeBy(1_000)
            assertFalse(r.prefetcher.fetch(r.cache, r.key, r.downloads.urls))   // second whole-but-cut copy: now incomplete
            val requests = r.server.streamRequests.size
            // Failed for this run: the offline library's missing() skips it, so a queue download neither spins nor stays open.
            assertTrue(r.prefetcher.isIncomplete(r.key.songId))
            r.serve(encodeA)
            r.prefetcher.networkChanged("wifi-1")                               // capability change only
            advanceTimeBy(3_600_000)
            assertFalse(r.prefetcher.fetch(r.cache, r.key, r.downloads.urls))
            assertEquals("not asked again", requests, r.server.streamRequests.size)
            r.prefetcher.networkChanged("cell-2")                               // a real switch
            assertFalse(r.prefetcher.isIncomplete(r.key.songId))
            assertTrue(r.prefetcher.fetch(r.cache, r.key, r.downloads.urls))
            assertArrayEquals(encodeA, r.cached())
        }
    }

    @Test fun aServerIgnoringTheRangeSendsTheWholeFileAndTheResumedPartIsNotKept() = runTest {
        resuming { r ->
            r.partial(encodeA, 10_000); r.serve(encodeB); r.server.ignoreRange = true
            assertTrue(r.prefetcher.fetch(r.cache, r.key, r.downloads.urls))
            assertEquals("bytes=5904-", r.server.streamRequests.single().range)
            assertArrayEquals("the 200 body replaces the partial, nothing of encode A is left", encodeB, r.cached())
        }
    }

    @Test fun aShorterReplacementTranscodeRestartsInsteadOfRetryingTheSameFailure() = runTest {
        resuming { r ->
            // 17 701 bytes: more than the 15 904 where the resumed request starts, less than the 20 000 we hold.
            val shorter = OggFixture.transcode(serial = 1, seed = 5, pages = 1, audio = 17_500)
            assertEquals(17_701, shorter.size)
            r.partial(encodeA, 20_000); r.serve(shorter)
            assertFalse(r.prefetcher.fetch(r.cache, r.key, r.downloads.urls))
            assertEquals("no retry loop on a partial the server can no longer continue", 0L, r.resumeFrom())
            advanceTimeBy(1_000)
            assertTrue(r.prefetcher.fetch(r.cache, r.key, r.downloads.urls))
            assertArrayEquals(shorter, r.cached())
        }
    }

    @Test fun aSmallPartialResumesFromZeroInOneRequest() = runTest {
        resuming { r ->
            for (held in listOf(4_096, 4_097, 1)) {
                r.reset(); r.serve(encodeA)
                r.partial(encodeA, held)
                r.server.streamRequests.clear()
                assertTrue("held $held", r.prefetcher.fetch(r.cache, r.key, r.downloads.urls))
                assertEquals("held $held", listOf(if (held <= 4_096) "bytes=0-" else "bytes=${held - 4_096}-"), r.server.streamRequests.map { it.range })
                assertArrayEquals(encodeA, r.cached())
            }
        }
    }

    @Test fun aChangedEtagStartsAgainFromZeroWhetherOrNotTheServerChecksIfRange() = runTest {
        resuming { r ->
            // The server checks If-Range: the changed file comes back whole (200).
            r.partial(encodeA, 10_000, identity = "\"a\"\t\t${encodeA.size}"); r.serve(encodeB, etag = "\"b\"")
            assertTrue(r.prefetcher.fetch(r.cache, r.key, r.downloads.urls))
            assertArrayEquals(encodeB, r.cached())
            r.reset()
            // The server ignores If-Range and continues the other file (206 with another ETag): discarded, then fetched whole.
            r.server.ignoreIfRange = true
            r.partial(encodeA, 10_000, identity = "\"a\"\t\t${encodeA.size}")
            assertFalse(r.prefetcher.fetch(r.cache, r.key, r.downloads.urls))
            assertNull(r.cached()); assertEquals(0L, r.resumeFrom())
            advanceTimeBy(1_000)
            assertTrue(r.prefetcher.fetch(r.cache, r.key, r.downloads.urls))
            assertArrayEquals(encodeB, r.cached())
            // Same file, same ETag: the resume goes on and sends If-Range.
            r.reset()
            r.serve(encodeA, etag = "\"a\""); r.server.ignoreIfRange = false
            r.partial(encodeA, 10_000, identity = "\"a\"\t\t${encodeA.size}")
            r.server.streamRequests.clear()
            assertTrue(r.prefetcher.fetch(r.cache, r.key, r.downloads.urls))
            assertEquals(listOf("bytes=5904-"), r.server.streamRequests.map { it.range })
            assertArrayEquals(encodeA, r.cached())
        }
    }

    @Test fun anAnotherTotalLengthThanTheOneSavedStartsAgain() = runTest {
        resuming { r ->
            r.partial(encodeA, 10_000, identity = "\t\t${encodeA.size + 1}"); r.serve(encodeA)
            assertFalse(r.prefetcher.fetch(r.cache, r.key, r.downloads.urls))
            assertNull(r.cached()); assertEquals(0L, r.resumeFrom())
        }
    }

    @Test fun anOpusRequestAnsweredWithTheOriginalIsStoredAsTheOriginal() = runTest {
        FakeSubsonicServer().use { server ->
            val cache = memoryTrackCache(temporary.newFolder())
            val prefetcher = Prefetcher(OkHttpClient()) { testScheduler.currentTime }
            val downloads = Downloads(server) { testScheduler.currentTime }
            // Navidrome sends an original it doesn't transcode with its own type (pass-through or fallback).
            server.streamOverrides = mapOf("track-1" to (SyntheticLibrary.albums.first().tracks.first().bytes("raw") to "audio/flac"))
            assertTrue(prefetcher.fetch(cache, TrackKey("track-1", StreamFormat.OPUS_128), downloads.urls))
            assertEquals(StreamFormat.RAW, cache.kind("track-1"))
            assertTrue(cache.resolve("track-1")!!.name.endsWith(".orig"))
            // A server without an Opus transcoder (415) is asked for the original.
            server.rejectOpus = true
            assertTrue(prefetcher.fetch(cache, TrackKey("track-2", StreamFormat.OPUS_128), downloads.urls))
            assertEquals(StreamFormat.RAW, cache.kind("track-2"))
        }
    }

    @Test fun failedRenameIsPendingAndNeverReportedAsStored() = runTest {
        FakeSubsonicServer().use { server ->
            val cache = memoryTrackCache(temporary.newFolder())
            val prefetcher = Prefetcher(OkHttpClient()) { testScheduler.currentTime }
            val downloads = Downloads(server) { testScheduler.currentTime }
            val obstruction = java.io.File(cache.directory, first.fileName).apply { mkdir(); resolve("block").writeText("x") }
            assertFalse(prefetcher.fetch(cache, first, downloads.urls, false))
            assertNull(cache.find(first))
            assertEquals(1000L, prefetcher.retryDelayMs())
            assertEquals("BAT-1b: a pending retry bounds the slow idle poll", 1000L, prefetcher.retryDelayMs(30_000))
            obstruction.deleteRecursively()
            advanceTimeBy(1000)
            assertTrue(prefetcher.fetch(cache, first, downloads.urls, false))
            assertEquals("nothing pending: the idle poll", 30_000L, prefetcher.retryDelayMs(30_000))
        }
    }

    @Test fun cancellingAStalledDownloadCancelsTheRequestAndReleasesTheWriter() = kotlinx.coroutines.runBlocking {
        FakeSubsonicServer().use { server ->
            val cache = memoryTrackCache(temporary.newFolder())
            val prefetcher = Prefetcher(OkHttpClient())
            val downloads = Downloads(server) { 0 }
            val requested = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            server.beforeStream = { requested.countDown(); release.await(20, java.util.concurrent.TimeUnit.SECONDS) }
            try {
                val job = launch(kotlinx.coroutines.Dispatchers.IO) { prefetcher.fetch(cache, first, downloads.urls, false) }
                assertTrue(requested.await(10, java.util.concurrent.TimeUnit.SECONDS))
                val started = System.nanoTime()
                job.cancel()
                kotlinx.coroutines.withTimeout(5_000) { job.join() }
                assertTrue("Cancellation waited for the stalled response", System.nanoTime() - started < 2_000_000_000L)
                assertTrue(audio(cache).isEmpty())
                cache.begin(first)!!.close() // The writer slot was released.
            } finally { release.countDown() }
        }
    }
}
