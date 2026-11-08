package dev.coda.android.cache

import dev.coda.core.testing.MemoryCacheIndex
import dev.coda.core.testing.memoryTrackCache
import dev.coda.core.connectivity.Reachability
import dev.coda.core.cache.*
import dev.coda.core.connectivity.NetworkState
import dev.coda.core.playback.StreamFormat
import dev.coda.core.testing.FakeFreeSpace
import dev.coda.core.testing.FakeSubsonicServer
import dev.coda.core.testing.SyntheticLibrary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.logging.Level
import java.util.logging.Logger

/** Adds, Wi-Fi-only add downloads, "Make room?", delete now, re-sync and reachability, against a loopback server and a fake phone. */
class OfflineLibraryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val wifi = NetworkState(available = true, unmetered = true)
    private val mobile = NetworkState(available = true, unmetered = false, cellular = true)
    private val hotspot = NetworkState(available = true, unmetered = false, cellular = false)
    private val GiB = 1L shl 30
    private val day = CacheRules.DAY_MS
    /** the fixed rules as CA-3 settings (1 month × 3, a 1 GiB line), for the CA-2/DL-1 tests. */
    private var policy = CachePolicy(Lifetime.MONTH, Lifetime.MONTH, Lifetime.MONTH, keepFreeBytes = 1L shl 30)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = FakeSubsonicServer()
    private var wall = 1_000 * CacheRules.DAY_MS
    private val network = MutableStateFlow(wifi)
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    private lateinit var space: FakeFreeSpace
    private lateinit var cache: TrackCache
    @Volatile private var reachable = true

    init { Logger.getLogger("okhttp3.mockwebserver.MockWebServer").level = Level.OFF }
    @After fun tearDown() { scope.cancel(); server.close() }

    private val spaceQueries = java.util.concurrent.atomic.AtomicInteger()

    private fun library(folder: java.io.File = temporary.newFolder(), client: OkHttpClient = OkHttpClient(),
        ping: suspend () -> Unit = { if (!reachable) throw java.io.IOException("unreachable") },
        allowed: kotlinx.coroutines.flow.StateFlow<Boolean> = MutableStateFlow(true), housekeepingMs: Long = 60_000L,
        onUserWork: () -> Unit = {},
        active: kotlinx.coroutines.flow.StateFlow<Boolean> = MutableStateFlow(true), savePolicy: (CachePolicy, Boolean?) -> Unit = { p, _ -> policy = p },
        nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
        fallbackMs: Long = housekeepingMs, visible: kotlinx.coroutines.flow.StateFlow<Boolean> = MutableStateFlow(false),
        screenOn: kotlinx.coroutines.flow.StateFlow<Boolean> = MutableStateFlow(true), screenOffMs: Long = 5 * 60_000L,
        reachability: Reachability = reach(ping, nowMs), coverSource: CoverStore.Source? = null,
        runIn: CoroutineScope = scope, savesFail: Boolean = false,
        /** Saves fail only while this is set (a full disk that comes and goes). */
        failSaves: java.util.concurrent.atomic.AtomicBoolean? = null): OfflineLibrary {
        space = FakeFreeSpace(folder, 64 * GiB)
        val counted = object : FreeSpace {
            override fun allocatableBytes() = space.allocatableBytes().also { spaceQueries.incrementAndGet() }
            override fun allocate(fd: java.io.FileDescriptor, bytes: Long) = space.allocate(fd, bytes)
        }
        cache = if (failSaves != null) {
            val inner = dev.coda.core.testing.MemoryCacheIndex.at(folder)
            val index = object : dev.coda.core.cache.CacheIndexStore by inner {
                override fun save(writer: String, previous: dev.coda.core.cache.CacheSnapshot?, next: dev.coda.core.cache.CacheSnapshot) =
                    if (failSaves.get()) throw java.io.IOException("disk full") else inner.save(writer, previous, next)
            }
            TrackCache(folder, index, counted, { it.run() }, { policy }, wall = { wall })
        } else if (!savesFail) memoryTrackCache(folder, counted, policy = { policy }) { wall } else {
            // An index that reads but cannot be saved (a full disk): the cache opens degraded and deletes no music.
            val inner = dev.coda.core.testing.MemoryCacheIndex.at(folder)
            val index = object : dev.coda.core.cache.CacheIndexStore by inner {
                override fun save(writer: String, previous: dev.coda.core.cache.CacheSnapshot?, next: dev.coda.core.cache.CacheSnapshot) =
                    throw java.io.IOException("disk full")
            }
            TrackCache(folder, index, counted, { it.run() }, { policy }, wall = { wall })
        }
        return OfflineLibrary(runIn, cache, network, changes, { source ->
            server.authenticatedUrl("stream") + "&id=${source.songId}&format=" + if (source.format == StreamFormat.RAW) "raw" else "opus"
        }, reachability = reachability, client = client, log = {}, transfersAllowed = allowed, housekeepingMs = housekeepingMs, onUserWork = onUserWork,
            active = active, savePolicy = savePolicy, nowMs = nowMs, fallbackMs = fallbackMs, visible = visible,
            screenOn = screenOn, screenOffMs = screenOffMs, coverSource = coverSource)
    }

    /** The shared state as the app wires it: fed by [network] and [changes], probing with [ping]. */
    private fun reach(ping: suspend () -> Unit = { if (!reachable) throw java.io.IOException("unreachable") },
        nowMs: () -> Long = { System.nanoTime() / 1_000_000 }): Reachability {
        fun level(state: NetworkState) = if (state.available) Reachability.Network.USABLE else Reachability.Network.NONE
        val r = Reachability(scope, nowMs, { ping(); true }, initial = level(network.value))
        scope.launch { network.collect { r.network(level(it)) } }
        scope.launch { changes.collect { r.networkChanged() } }
        return r
    }

    private fun track(id: String) = KeptTrack(id, SongFormat("flac", 900, 2), TrackMeta(id))
    private val album = KeptItem("album/album-1", listOf(track("track-1"), track("track-2")))
    private fun eventually(what: String, check: () -> Boolean) {
        val until = System.nanoTime() + 15_000_000_000L
        while (!check()) { assertTrue("Timed out: $what", System.nanoTime() < until); Thread.sleep(25) }
    }
    private fun seed(id: String, size: Int = 1_000) {
        cache.begin(TrackKey(id, StreamFormat.RAW))!!.use { it.output.write("fLaC".toByteArray() + ByteArray(size - 4)); it.commit() }
    }

    @Test fun keepOfflineAndDownloadQueueAreUserWorkButOtherChangesAreNot() = runBlocking {
        var asked = 0
        val host = library(allowed = MutableStateFlow(false), onUserWork = { asked++ })
        host.add(album); assertEquals(1, asked)
        host.downloadQueue(listOf(track("track-3"))); assertEquals(2, asked)
        host.stopQueueDownload(); host.cancelDownloads(); host.poke(); assertEquals(2, asked)
    }

    @Test fun anAddOnWifiRenewsAndDownloadsTheWholeSetAsOriginals() = runBlocking {
        val host = library()
        val result = host.add(album)
        assertEquals(AddResult.Started(StreamFormat.RAW, 2 * 2L * 900 * 125), result)
        assertEquals(listOf("album/album-1"), host.items.value.map { it.key })
        eventually("both tracks cached") { host.status(album.key, album.trackIds).complete }
        assertTrue(album.trackIds.all { cache.kind(it) == StreamFormat.RAW })
        assertEquals(TrackMeta("track-1"), cache.record("track-1")!!.meta)
        val status = host.status(album.key, album.trackIds)
        assertTrue(status.added); assertFalse(status.downloading)
        assertArrayEquals(SyntheticLibrary.albums.first().tracks.first().bytes("raw"), cache.resolve("track-1")!!.readBytes())
    }

    /**
     * Adds download only on proper Wi-Fi. Elsewhere the add records the set without asking, plans its
     * size for Wi-Fi (originals in lossless mode), requests nothing, and starts by itself once Wi-Fi is back.
     */
    private fun anAddStaysPendingWithoutAskingUntilWifi(notWifi: NetworkState) = runBlocking {
        network.value = notWifi
        val host = library()
        assertEquals(AddResult.Started(StreamFormat.RAW, 2 * 2L * 900 * 125, waitsForWifi = true), host.add(album))
        assertEquals(listOf(album.key), host.items.value.map { it.key })
        val status = host.status(album.key, album.trackIds)
        assertTrue(status.added); assertEquals(2, status.pendingTracks); assertEquals(0, status.cachedTracks)
        assertTrue(host.waitingForWifi(album.key))
        host.poke(); delay(1_500)
        assertTrue("Nothing downloads off Wi-Fi: ${server.streamRequests}", server.streamRequests.isEmpty())
        assertTrue(host.waitingForWifi(album.key))
        network.value = wifi; changes.tryEmit(Unit)
        eventually("downloaded on Wi-Fi") { host.status(album.key, album.trackIds).complete }
        assertTrue(album.trackIds.all { cache.kind(it) == StreamFormat.RAW })
        assertFalse(host.waitingForWifi(album.key))
    }

    @Test fun onMobileDataAnAddStaysPendingWithoutAskingAndStartsOnWifi() = anAddStaysPendingWithoutAskingUntilWifi(mobile)

    /** A phone hotspot is Wi-Fi but metered and not cellular: it counts as "not Wi-Fi". */
    @Test fun onAHotspotAnAddStaysPendingWithoutAskingAndStartsOnWifi() = anAddStaysPendingWithoutAskingUntilWifi(hotspot)

    /** Body bytes of stream responses that reached Coda (counted where the response leaves the network). */
    private val streamed = java.util.concurrent.atomic.AtomicLong()
    private val countingClient = OkHttpClient.Builder().addNetworkInterceptor { chain ->
        val response = chain.proceed(chain.request())
        val body = response.body
        if (body == null || !chain.request().url.encodedPath.endsWith("/stream.view")) return@addNetworkInterceptor response
        val counted = object : okio.ForwardingSource(body.source()) {
            override fun read(sink: okio.Buffer, byteCount: Long) = super.read(sink, byteCount).also { if (it > 0) streamed.addAndGet(it) }
        }
        response.newBuilder().body(counted.buffer().asResponseBody(body.contentType(), body.contentLength())).build()
    }.build()

    /**
     * Leaving proper Wi-Fi stops an add's running download. The download is caught in flight (the
     * server got the request and part of the body arrived), the network switches to a hotspot, the transfer stops and
     * stores nothing; back on Wi-Fi it continues with Range. An original resumes; the fake server's Opus is a fresh
     * (chunked) transcode that ignores the Range (200), so the file restarts from zero and is still byte-exact.
     */
    private fun leavingWifiStopsARunningDownload(lossy: Boolean) = runBlocking {
        val host = library(client = countingClient)
        if (lossy) space.setFree(6 * GiB) // lossy mode: a FLAC is fetched as Opus 128 even on Wi-Fi
        val format = if (lossy) "opus" else "raw"
        val item = KeptItem("track/track-1", listOf(track("track-1")))
        val bytes = SyntheticLibrary.albums.first().tracks.first().bytes(format)
        server.streamThrottleBytes = 2_048
        host.add(item)
        // Three throttled steps: the bytes of the earlier reads are written by the time the third one arrives.
        eventually("request received and part of the body arrived") { server.streamRequests.size == 1 && streamed.get() >= 6_144 }
        assertEquals(format, server.streamRequests.single().format)
        network.value = hotspot; changes.tryEmit(Unit)
        eventually("stopped") { host.downloading.value == null }
        val received = streamed.get()
        delay(500)
        assertTrue("The transfer stopped mid-body: $received of ${bytes.size}", received < bytes.size)
        assertEquals("No more body after the switch", received, streamed.get())
        assertFalse(cache.contains("track-1"))
        assertEquals(1, server.streamRequests.size)
        assertTrue(host.waitingForWifi(item.key))
        server.streamThrottleBytes = 0
        network.value = wifi; changes.tryEmit(Unit)
        eventually("finished on Wi-Fi") { cache.contains("track-1") }
        val again = server.streamRequests[1]
        assertEquals(format, again.format)
        val from = Regex("bytes=(\\d+)-").find(again.range ?: "")?.groupValues?.get(1)?.toLong()
        assertTrue("The download resumes with Range: ${again.range}", from != null && from > 0 && from <= received)
        assertArrayEquals(bytes, cache.resolve("track-1")!!.readBytes())
        assertEquals(2, server.streamRequests.size)
    }

    @Test fun leavingProperWifiStopsARunningOriginalDownloadWhichResumesWithRange() = leavingWifiStopsARunningDownload(lossy = false)

    // Without the space mode an add never downloads Opus 128 on Wi-Fi, so the Opus restart case left with it
    // (a transcode part never resumes: TrackCache.Write). BAT-1b made it resume again; its Range/200-restart behaviour is
    // covered where it can still happen, in PrefetcherTest (the Prefetcher), not through an add on proper Wi-Fi.

    @Test fun renewingAPlaylistWhileItsFirstDownloadIsStalledDatesThatTrackAndTheRestWithTheRenewal() = runBlocking {
        val host = library()
        val release = java.util.concurrent.CountDownLatch(1)
        server.beforeStream = { id -> if (id == "track-1") release.await(10, java.util.concurrent.TimeUnit.SECONDS) }
        val playlist = KeptItem("playlist/p", listOf(track("track-1"), track("track-2")))
        host.add(playlist)
        eventually("first download stalled at the server") { server.streamRequests.any { it.id == "track-1" } }
        wall += 20 * day
        // The page is opened while the first track is still downloading: the whole playlist is renewed.
        assertTrue(host.resync(playlist))
        val renewed = wall
        release.countDown()
        eventually("downloaded") { host.status(playlist.key, playlist.trackIds).complete }
        for (id in playlist.trackIds) assertEquals(id, renewed + 30 * day, host.status(playlist.key, listOf(id)).availableUntil)
    }

    @Test fun anAddThatNeedsRoomAsksOnceThenDeletesMusicNotKeptFirst() = runBlocking {
        val host = library()
        seed("old-loose"); wall += day
        seed("old-kept")
        host.add(KeptItem("album/old", listOf(KeptTrack("old-kept")))) // nothing to download: renews only
        wall += day
        seed("recent")
        val size = SyntheticLibrary.albums.first().tracks.first().bytes("raw").size.toLong()
        val item = KeptItem("album/new", listOf(KeptTrack("track-1", SongFormat("mp3", 320, sizeBytes = size))))
        space.setFree(policy.keepFreeBytes + size - 1_500)
        val asked = host.add(item) as AddResult.NeedsRoom
        // Music not kept goes first, earliest keep-until first; the kept album stays.
        assertEquals(AddResult.NeedsRoom(2_000, size, emptyList(), 2), asked.copy(victims = emptyList()))
        assertEquals(listOf("old-loose", "recent"), asked.victims.map { it.songId })
        assertTrue("Asking deletes nothing", cache.contains("old-loose") && cache.contains("old-kept"))
        assertFalse(host.items.value.any { it.key == "album/new" })
        val started = host.add(item, makeRoom = asked)
        assertTrue(started is AddResult.Started)
        assertFalse(cache.contains("old-loose")); assertTrue(cache.contains("old-kept")); assertFalse(cache.contains("recent"))
        eventually("downloaded") { cache.contains("track-1") }
    }

    @Test fun anAddThatCannotFitAtAllSaysWhatIsMissingAndDownloadsNothing() = runBlocking {
        val host = library()
        seed("other")
        // The fake phone counts the index file too: let the first pass record reachability before fixing free space.
        eventually("first pass") { host.revision.value > 0 }
        cache.flush()
        space.setFree(policy.keepFreeBytes + 100)
        val item = KeptItem("album/huge", listOf(KeptTrack("track-1", SongFormat("mp3", 320, sizeBytes = 5_000))))
        assertEquals(AddResult.TooBig(5_000L - 100 - 1_000, policy.keepFreeBytes), host.add(item))
        delay(1_500)
        assertTrue(host.items.value.isEmpty()); assertTrue(server.streamRequests.isEmpty()); assertTrue(cache.contains("other"))
    }

    @Test fun onWifiAnOpusCopyInsideAnAddIsReplacedByTheOriginal() = runBlocking {
        val host = library()
        cache.begin(TrackKey("track-1", StreamFormat.OPUS_128))!!.use {
            it.output.write(SyntheticLibrary.albums.first().tracks.first().bytes("opus")); it.commit("audio/ogg")
        }
        assertEquals(StreamFormat.OPUS_128, cache.kind("track-1"))
        host.add(album)
        eventually("replaced") { cache.kind("track-1") == StreamFormat.RAW && cache.kind("track-2") == StreamFormat.RAW }
        assertTrue(cache.directory.listFiles()!!.none { it.name.endsWith(".opus") })
    }

    @Test fun onANearlyFullPhoneAnAddStillFetchesOriginalsOnWifi() = runBlocking {
        // No lossy mode. Free space never turns originals into Opus 128.
        val host = library()
        space.setFree(6 * GiB)
        cache.begin(TrackKey("track-1", StreamFormat.OPUS_128))!!.use {
            it.output.write(SyntheticLibrary.albums.first().tracks.first().bytes("opus")); it.commit("audio/ogg")
        }
        assertEquals(AddResult.Started(StreamFormat.RAW, 2 * 2L * 900 * 125), host.add(album))
        eventually("originals") { cache.kind("track-1") == StreamFormat.RAW && cache.kind("track-2") == StreamFormat.RAW }
    }

    @Test fun idlePassesOverALargeCompleteSetStayLinearAndQuerySpaceOncePerPass() = runBlocking {
        val folder = temporary.newFolder()
        val ids = (1..10_000).map { "t$it" }
        val records = ids.associateWith { id ->
            val name = songFileBase(id) + ".orig"
            java.io.File(folder, name).writeBytes("fLaC".toByteArray())
            CachedTrack(id, name, StreamFormat.RAW, 4, wall)
        }
        MemoryCacheIndex.at(folder).save("seed", null,
            CacheSnapshot(tracks = records, sets = listOf(KeptItem("album/big", ids.map { KeptTrack(it) }, addedAt = wall))))
        // The maintenance ticker is not the download loop: off here, this test is about the loop's own passes.
        val host = library(folder, housekeepingMs = 200, active = MutableStateFlow(false))
        assertTrue(host.status("album/big", ids).complete)
        eventually("first pass") { host.revision.value > 0 }
        delay(200)
        val from = host.revision.value
        spaceQueries.set(0)
        // With nothing pending the loop has no timer: no pass in 7 housekeeping intervals (200 ms here).
        delay(1_500)
        assertEquals("No idle passes while nothing is pending", from, host.revision.value)
        val started = System.nanoTime()
        repeat(3) {
            val before = host.revision.value
            host.poke()
            while (host.revision.value <= before) {
                assertTrue("Three woken passes within 10 s", System.nanoTime() - started < 10_000_000_000L)
                delay(20)
            }
        }
        val passes = host.revision.value - from
        assertTrue("${spaceQueries.get()} space queries in $passes passes", spaceQueries.get() <= 2 * (passes + 1))
        assertTrue(server.streamRequests.isEmpty())
    }


    // ---- DL-1: the shared download state, Cancel, the queue download ----

    /** Newest set first, one track at a time; the shared state counts the run and names the current set. */
    @Test fun theSharedStateCountsTheRunInOrderNewestSetFirst() = runBlocking {
        network.value = mobile
        val host = library()
        val older = KeptItem("album/album-1", listOf(track("track-1"), track("track-2")), "Continuous Signal")
        val newer = KeptItem("album/album-2", listOf(track("track-3")), "Colour Study")
        host.add(older); wall += 1_000; host.add(newer)
        eventually("waiting published") { host.downloads.value.waitingForWifi }
        val waiting = host.downloads.value
        assertEquals(3, waiting.pending); assertEquals(0, waiting.done)
        assertEquals("Waiting for Wi-Fi · 0 of 3", DownloadLines.notification(waiting))
        assertEquals(SetPhase.WAITING_FOR_WIFI, waiting.phase(older.key))
        // The newest add is the one that downloads (or would) first.
        assertEquals(newer.key, waiting.currentKey); assertEquals("Colour Study", waiting.currentTitle)
        server.streamThrottleBytes = 2_048
        network.value = wifi; changes.tryEmit(Unit)
        eventually("the newer set downloads first") { host.downloads.value.let { it.currentKey == newer.key && !it.waitingForWifi } }
        assertEquals(SetPhase.QUEUED, host.downloads.value.phase(older.key))
        assertEquals(SetPhase.DOWNLOADING, host.downloads.value.phase(newer.key))
        eventually("the newer set is done") { cache.contains("track-3") }
        assertFalse("One track at a time, newest set first", cache.contains("track-1") && !cache.contains("track-3"))
        server.streamThrottleBytes = 0
        eventually("everything stored") { !host.downloads.value.active }
        assertEquals(listOf("track-3", "track-1", "track-2"), server.streamRequests.map { it.id }.distinct())
        assertEquals(DownloadState(), host.downloads.value.copy(currentKey = null, currentTitle = null))
    }

    /** Work persisted before the process died is published by the time the library exists, before any ping. */
    @Test fun persistedWorkIsPublishedBeforeTheFirstReachabilityCheck() = runBlocking {
        val folder = temporary.newFolder()
        MemoryCacheIndex.at(folder).save("seed", null,
            CacheSnapshot(queueDownload = KeptItem(DownloadLines.QUEUE_KEY, listOf(track("track-1"), track("track-2")), "Queue", wall)))
        val hang = CompletableDeferred<Unit>()
        val host = library(folder, ping = { hang.await() })
        assertTrue("restored work is active at once", host.downloads.value.active)
        assertEquals(2, host.downloads.value.pending)
        assertEquals(DownloadLines.QUEUE_KEY, host.downloads.value.currentKey)
        hang.cancel()
    }

    /** While transfers are not allowed (job stopped, app in the background) nothing downloads; pending work is kept. */
    @Test fun heldTransfersDownloadNothingAndKeepPendingWorkUntilAllowedAgain() = runBlocking {
        val allowed = MutableStateFlow(false)
        val host = library(allowed = allowed)
        host.add(album)
        eventually("paused published") { host.downloads.value.paused }
        assertEquals(2, host.downloads.value.pending); assertFalse(host.downloads.value.waitingForWifi)
        delay(1_000)
        assertTrue("No transfer while held", server.streamRequests.isEmpty())
        allowed.value = true
        eventually("downloads once allowed") { host.status(album.key, album.trackIds).complete }
        assertFalse(host.downloads.value.paused)
    }

    /** The running transfer stops when transfers are no longer allowed. */
    @Test fun aRunningTransferStopsWhenTheJobEnds() = runBlocking {
        server.streamThrottleBytes = 1_024
        val allowed = MutableStateFlow(true)
        val host = library(allowed = allowed)
        host.add(album)
        eventually("transferring") { server.streamRequests.isNotEmpty() }
        allowed.value = false
        eventually("paused") { host.downloads.value.paused }
        delay(300)
        val requests = server.streamRequests.size
        delay(1_000)
        assertEquals("No new transfer after the stop", requests, server.streamRequests.size)
        assertFalse(album.trackIds.all(cache::contains))
        assertEquals("Pending work is kept", true, host.downloads.value.active)
    }

    /** An Opus → original replacement is download work, and Cancel stops it for good. */
    @Test fun anOpusReplacementCountsAsWorkAndCancelStopsIt() = runBlocking {
        network.value = mobile
        val host = library()
        seed("track-2")
        cache.begin(TrackKey("track-1", StreamFormat.OPUS_128))!!.use {
            it.output.write(SyntheticLibrary.albums.first().tracks.first().bytes("opus")); it.commit("audio/ogg")
        }
        host.add(album)
        assertFalse("On mobile data nothing is replaced", host.downloads.value.active)
        server.streamThrottleBytes = 1_024
        network.value = wifi; changes.tryEmit(Unit)
        eventually("the replacement is published as work") { host.downloads.value.active && host.downloads.value.pending == 1 }
        host.cancelDownloads()
        assertFalse(host.downloads.value.active)
        delay(1_500)
        assertEquals(StreamFormat.OPUS_128, cache.kind("track-1"))
        val requests = server.streamRequests.size
        changes.tryEmit(Unit); host.poke(); delay(1_000)
        assertEquals("Nothing restarts after Cancel", requests, server.streamRequests.size)
        // The cancel survives a restart; adding the set again replaces the copy.
        assertEquals(setOf("track-1"), MemoryCacheIndex.at(cache.directory).load("test")!!.replacementsCancelled)
        server.streamThrottleBytes = 0
        host.add(album)
        eventually("replaced after adding again") { cache.kind("track-1") == StreamFormat.RAW }
    }

    /** Review r2 P2: Cancel on mobile data also stops the Opus replacement that would start when Wi-Fi returns. */
    @Test fun cancelOffWifiAlsoStopsTheOpusReplacementThatWaitsForWifi() = runBlocking {
        network.value = mobile
        val host = library()
        seed("track-2")
        cache.begin(TrackKey("track-1", StreamFormat.OPUS_128))!!.use {
            it.output.write(SyntheticLibrary.albums.first().tracks.first().bytes("opus")); it.commit("audio/ogg")
        }
        host.add(album)
        host.cancelDownloads()
        assertEquals("recorded although off Wi-Fi", setOf("track-1"), MemoryCacheIndex.at(cache.directory).load("test")!!.replacementsCancelled)
        network.value = wifi; changes.tryEmit(Unit); host.poke()
        delay(1_500)
        assertEquals("Nothing is replaced after Cancel", StreamFormat.OPUS_128, cache.kind("track-1"))
        assertEquals(0, server.streamRequests.size)
    }

    /** Cancel: pending downloads stop; what is on the phone and the kept marks stay; a new add downloads the rest. */
    @Test fun cancelStopsPendingDownloadsKeepsWhatIsOnThePhoneAndDropsTheIncompleteMark() = runBlocking {
        network.value = mobile
        val host = library()
        seed("track-1")
        host.add(album)
        eventually("pending") { host.downloads.value.pending == 1 }
        host.cancelDownloads()
        assertFalse(host.downloads.value.active)
        // A cancelled, partly loaded set looks not kept, so it is not kept: the mark is dropped, the audio stays.
        assertTrue(host.items.value.isEmpty())
        val status = host.status(album.key, album.trackIds)
        assertFalse(status.added); assertEquals(0, status.pendingTracks); assertEquals(1, status.cachedTracks)
        assertFalse(status.looksKept)
        assertNull(DownloadLines.keepSheet(status, SetPhase.IDLE, null))
        network.value = wifi; changes.tryEmit(Unit)
        delay(1_500)
        assertTrue("Nothing downloads after Cancel", server.streamRequests.isEmpty())
        // The cancel survives a restart of the app.
        assertEquals(0, memoryTrackCache(cache.directory, space) { wall }.pending(album.key).size)
        host.add(album)
        eventually("the rest downloads") { cache.contains("track-2") }
    }

    /** Every track already on the phone: the load is done at once, also on mobile data (it used to stay "Loading 3 of 3"). */
    @Test fun aQueueThatIsAllOnThePhoneIsReadyAtOnceOnMobileData() = runBlocking {
        network.value = mobile
        val host = library()
        seed("track-1"); seed("track-2"); seed("track-3")
        assertFalse(host.downloadQueue(listOf(track("track-1"), track("track-2"), track("track-3"))))
        assertNull(host.downloads.value.queue)
        assertFalse(host.downloads.value.waitingForWifi)
        eventually("still ended after a pass") { host.revision.value > 0 }
        assertNull(host.downloads.value.queue)
        assertEquals(DownloadLines.QueueEntry("Cache queue ahead", "All 3 ready offline", enabled = false, running = false),
            DownloadLines.queueEntry(3, 3, host.downloads.value))
        assertNull(MemoryCacheIndex.at(cache.directory).load("test")!!.queueDownload)
        assertTrue(server.streamRequests.isEmpty())
        // The tracks got a fresh Heard once time, as for a normal load.
        assertEquals(wall, cache.record("track-1")!!.addedAt)
    }

    /** A mixed queue on mobile data loads the missing track at once (cached ones counted), no prompt. */
    @Test fun aMixedQueueOnMobileDataLoadsTheMissingTrackAtOnceAndEnds() = runBlocking {
        network.value = mobile
        val host = library()
        seed("track-1"); seed("track-2")
        assertFalse("Nothing waits: it starts at once", host.downloadQueue(listOf(track("track-1"), track("track-2"), track("track-3"))))
        assertFalse(host.downloads.value.waitingForWifi)
        eventually("the rest arrived and it ended") { cache.contains("track-3") && host.downloads.value.queue == null }
        assertEquals(listOf("track-3"), server.streamRequests.map { it.id })
    }

    /** Fix round 1: playback caches the missing track while the load can't run (no network); maintenance then ends it. */
    @Test fun aMixedQueueWithoutANetworkEndsWhenPlaybackCachesTheMissingTrack() = runBlocking {
        network.value = NetworkState(available = false, unmetered = false)
        val host = library()
        seed("track-1"); seed("track-2")
        assertTrue(host.downloadQueue(listOf(track("track-1"), track("track-2"), track("track-3"))))
        eventually("published") { host.downloads.value.waitingForWifi }
        val seen = mutableListOf<QueueDownload?>()
        val watcher = launch(Dispatchers.Unconfined) { host.downloads.collect { seen += it.queue } }
        seed("track-3")
        host.maintain()
        eventually("ended") { host.downloads.value.queue == null }
        watcher.cancel()
        assertFalse(host.downloads.value.waitingForWifi)
        assertNull(MemoryCacheIndex.at(cache.directory).load("test")!!.queueDownload)
        assertEquals("All 3 ready offline", DownloadLines.queueEntry(3, 3, host.downloads.value).line)
        // The queue state goes non-null -> null once; it never shows a full bar ("3 of 3") while still set.
        assertEquals(1, seen.zipWithNext().count { (a, b) -> a != null && b == null })
        assertTrue(seen.none { it == QueueDownload(3, 3) })
        assertTrue(server.streamRequests.isEmpty())
    }

    /**
     * On a metered network (mobile data, a hotspot) "Load for offline" starts at once with the mobile format rule:
     * a lossless or >320 kbit/s track as Opus 128, a lossy original of at most 320 kbit/s unchanged. Read-ahead, no kept set.
     */
    private fun aQueueLoadOnAMeteredNetworkStartsAtOnceWithTheMobileFormats(metered: NetworkState) = runBlocking {
        network.value = metered
        val host = library()
        seed("track-2")
        val lossy = KeptTrack("track-3", SongFormat("mp3", 256, 2), TrackMeta("track-3"))
        assertFalse(host.downloadQueue(listOf(track("track-1"), track("track-2"), lossy)))
        assertFalse(host.downloads.value.waitingForWifi)
        eventually("all on the phone and the one-shot ended") { cache.contains("track-1") && cache.contains("track-3") && host.downloads.value.queue == null }
        assertEquals(StreamFormat.OPUS_128, cache.kind("track-1"))
        assertEquals(StreamFormat.RAW, cache.kind("track-3"))
        assertEquals(listOf("track-1" to "opus", "track-3" to "raw"), server.streamRequests.map { it.id to it.format }.sortedBy { it.first })
        assertTrue(host.items.value.isEmpty())
        assertFalse(host.downloads.value.active)
        assertEquals(wall, cache.record("track-1")!!.addedAt)
        assertEquals(DownloadLines.QueueEntry("Cache queue ahead", "All 3 ready offline", enabled = false, running = false),
            DownloadLines.queueEntry(3, 3, host.downloads.value))
        assertNull(MemoryCacheIndex.at(cache.directory).load("test")!!.queueDownload)
    }
    @Test fun aQueueLoadOnMobileDataStartsAtOnceWithTheMobileFormats() = aQueueLoadOnAMeteredNetworkStartsAtOnceWithTheMobileFormats(mobile)
    @Test fun aQueueLoadOnAHotspotStartsAtOnceWithTheMobileFormats() = aQueueLoadOnAMeteredNetworkStartsAtOnceWithTheMobileFormats(hotspot)

    /** On proper Wi-Fi the load fetches originals, as before. */
    @Test fun aQueueLoadOnWifiFetchesOriginals() = runBlocking {
        val host = library()
        assertFalse(host.downloadQueue(listOf(track("track-1"), track("track-2"))))
        eventually("ended") { cache.contains("track-1") && cache.contains("track-2") && host.downloads.value.queue == null }
        assertEquals(listOf("raw", "raw"), server.streamRequests.map { it.format })
    }

    /** Each transfer takes the network's format when it starts; Wi-Fi coming back never fetches a finished song again. */
    @Test fun aNetworkChangeDuringAQueueLoadDoesNotRedoFinishedSongs() = runBlocking {
        network.value = mobile
        val release = java.util.concurrent.CountDownLatch(1)
        server.beforeStream = { id -> if (id == "track-2") release.await(10, java.util.concurrent.TimeUnit.SECONDS) }
        val host = library()
        host.downloadQueue(listOf(track("track-1"), track("track-2"), track("track-3")))
        eventually("track-1 done as Opus, track-2 requested") { cache.kind("track-1") == StreamFormat.OPUS_128 && server.streamRequests.any { it.id == "track-2" } }
        network.value = wifi; changes.tryEmit(Unit)
        release.countDown()
        eventually("ended") { cache.contains("track-3") && host.downloads.value.queue == null }
        assertEquals(listOf("opus"), server.streamRequests.filter { it.id == "track-1" }.map { it.format })
        assertEquals(StreamFormat.OPUS_128, cache.kind("track-1"))
        assertEquals(listOf("raw"), server.streamRequests.filter { it.id == "track-3" }.map { it.format })
    }

    /** Only the queue load runs on mobile data; a kept album keeps waiting for Wi-Fi meanwhile. */
    @Test fun onMobileDataTheQueueLoadRunsWhileAKeptAlbumStillWaitsForWifi() = runBlocking {
        network.value = mobile
        val host = library()
        host.add(album)
        assertTrue(host.waitingForWifi(album.key))
        host.downloadQueue(listOf(track("track-3")))
        eventually("the queue load ended") { cache.contains("track-3") && host.downloads.value.queue == null }
        assertEquals(listOf("track-3"), server.streamRequests.map { it.id })
        assertTrue(host.waitingForWifi(album.key))
        assertTrue(host.downloads.value.waitingForWifi)
    }

    /** No network at all: the load waits (nothing can transfer) and starts by itself when a network is back. */
    @Test fun theQueueLoadWaitsWithoutANetworkFetchesMissingTracksAsReadAheadAndEnds() = runBlocking {
        network.value = NetworkState(available = false, unmetered = false)
        val host = library()
        seed("track-2")
        val tracks = listOf(track("track-1"), track("track-2"), track("track-3"))
        assertTrue(host.downloadQueue(tracks))
        eventually("published") { host.downloads.value.queue != null }
        assertEquals(QueueDownload(3, 1), host.downloads.value.queue)
        assertEquals(2, host.downloads.value.pending)
        assertTrue(host.downloads.value.waitingForWifi)
        assertEquals(DownloadLines.QueueEntry("Waiting for a network", "1 of 3 ready offline", enabled = true, running = true),
            DownloadLines.queueEntry(3, 1, host.downloads.value))
        delay(1_200)
        assertTrue(server.streamRequests.isEmpty())
        network.value = mobile; changes.tryEmit(Unit)
        eventually("all on the phone and the one-shot ended") { cache.contains("track-1") && cache.contains("track-3") && host.downloads.value.queue == null }
    }

    /** The queue download survives process death (its file), and the × stops it. */
    @Test fun theQueueDownloadIsPersistedAndStopsWithoutDeletingAnything() = runBlocking {
        network.value = NetworkState(available = false, unmetered = false)
        val folder = temporary.newFolder()
        val first = library(folder)
        seed("track-1")
        cache.flush()
        first.downloadQueue(listOf(track("track-1"), track("track-2")))
        scope.coroutineContext.cancelChildren()
        val again = library(folder)
        val until = System.nanoTime() + 15_000_000_000L
        while (again.downloads.value.queue != QueueDownload(2, 1)) {
            assertTrue("restored: ${MemoryCacheIndex.at(cache.directory).load("test")!!.queueDownload?.trackIds} ${again.downloads.value} ${cache.contains("track-1")}", System.nanoTime() < until)
            delay(25)
        }
        again.stopQueueDownload()
        assertNull(again.downloads.value.queue)
        assertFalse(again.downloads.value.active)
        assertTrue(cache.contains("track-1"))
        assertNull(MemoryCacheIndex.at(cache.directory).load("test")!!.queueDownload)
    }

    // ---- CA-2/DL-1 integration scenarios, kept under the policy (1 month x 3, a 1 GiB line) ----

    private val trackBytes get() = SyntheticLibrary.albums.first().tracks.first().bytes("raw").size.toLong()

    @Test fun addingAgainRenewsTheSet() = runBlocking {
        val host = library()
        host.add(album)
        eventually("cached") { host.status(album.key, album.trackIds).complete }
        wall += 20 * day
        host.add(album)
        assertEquals(wall + 30 * day, host.status(album.key, album.trackIds).availableUntil)
    }

    /**
     * Switching Prefer offline off removes the set and deletes nothing. Its tracks that no other kept set holds
     * start a fresh Heard once from the switch-off (gentle); a track heard again keeps its own later date; a track
     * another kept set holds stays kept.
     */
    @Test fun stopKeepingRemovesTheSetKeepsTheFilesAndStartsAFreshHeardOnce() = runBlocking {
        policy = CachePolicy(Lifetime.SIX_MONTHS, Lifetime.MONTH, Lifetime.THREE_MONTHS, keepFreeBytes = 1L shl 30)
        val host = library()
        eventually("online") { cache.expiring }
        val set = KeptItem("album/a", listOf(track("track-1"), track("track-2"), track("track-3")))
        val other = KeptItem("playlist/p", listOf(track("track-3")))
        host.add(set); host.add(other)
        eventually("cached") { host.status(set.key, set.trackIds).complete }
        wall += 39 * day
        cache.markPlayed("track-1"); cache.markPlayed("track-1") // heard again on day 39: kept until day 129
        wall += day
        cache.housekeep()
        assertTrue("Kept: nothing ages out", set.trackIds.all(cache::contains))
        val held = cache.acquire("track-2")
        host.stopKeeping(set.key) // day 40
        assertTrue("No file deleted", set.trackIds.all(cache::contains))
        assertNotNull(held); assertTrue(held!!.exists())
        assertFalse(host.status(set.key, set.trackIds).added)
        assertEquals(listOf(other.key), host.items.value.map { it.key })
        assertEquals(listOf(other.key), MemoryCacheIndex.at(cache.directory).load("test")!!.sets.map { it.key })
        cache.retainLeases(emptyList())
        // track-2 (never played, arrived on day 0) does not expire at the next cleanup: Heard once starts at the switch-off.
        cache.housekeep()
        assertTrue("Fresh Heard once", set.trackIds.all(cache::contains))
        wall += 30 * day - 60_000
        cache.housekeep()
        assertTrue("Not before Heard once has passed", cache.contains("track-2"))
        wall += 60_000 // day 70
        cache.housekeep()
        assertFalse("Heard once from the switch-off", cache.contains("track-2"))
        assertTrue("Heard again keeps its own later date", cache.contains("track-1"))
        assertTrue("Still kept by the playlist", cache.contains("track-3"))
        wall += 60 * day // day 130
        cache.housekeep()
        assertFalse("Heard again from its last play", cache.contains("track-1"))
        assertTrue(cache.contains("track-3"))
    }

    /**
     * Prefer offline off while the index can't be saved: no crash (the failure reaches no coroutine
     * handler), the existing "Offline music" message, the set still kept; switching off again once it saves works.
     */
    @Test fun aFailedStopKeepingShowsTheMessageKeepsTheSetAndARetryWorks() = runBlocking {
        val failSaves = java.util.concurrent.atomic.AtomicBoolean(false)
        val host = library(failSaves = failSaves)
        host.add(album)
        eventually("cached") { host.status(album.key, album.trackIds).complete }
        val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val ui = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> uncaught += e })
        try {
            val prompt = dev.coda.android.ui.common.AddPrompt(ui) { host }
            failSaves.set(true)
            prompt.stop(album.key)
            eventually("the switch-off finished") { uncaught.isNotEmpty() || prompt.error != null }
            assertEquals("no crash", emptyList<Throwable>(), uncaught.toList())
            assertEquals("Couldn't save offline music. Try again.", prompt.error)
            assertTrue("still kept", host.status(album.key, album.trackIds).added)
            assertEquals(listOf(album.key), host.items.value.map { it.key })
            failSaves.set(false)
            prompt.error = null
            prompt.stop(album.key)
            eventually("removed") { !host.status(album.key, album.trackIds).added }
            assertNull(prompt.error)
            assertTrue(uncaught.isEmpty())
            assertTrue("no file deleted", album.trackIds.all(cache::contains))
        } finally { ui.cancel() }
    }

    /** Switching off stops the set's pending downloads; nothing of it downloads afterwards. */
    @Test fun stopKeepingStopsTheSetsPendingDownloads() = runBlocking {
        val host = library()
        val release = java.util.concurrent.CountDownLatch(1)
        server.beforeStream = { id -> if (id == "track-1") release.await(10, java.util.concurrent.TimeUnit.SECONDS) }
        host.add(album)
        eventually("first download stalled at the server") { server.streamRequests.any { it.id == "track-1" } }
        host.stopKeeping(album.key)
        release.countDown()
        delay(1_500); host.poke(); delay(800)
        assertFalse(host.downloads.value.active)
        assertEquals(0, host.status(album.key, album.trackIds).pendingTracks)
        assertTrue(host.items.value.isEmpty())
        assertFalse("The rest never downloads", server.streamRequests.any { it.id == "track-2" })
    }

    @Test fun aResyncNeverAsksAndDownloadsNewTracksInTheBackground() = runBlocking {
        val host = library()
        host.add(KeptItem("playlist/p", listOf(track("track-1"))))
        eventually("first") { cache.contains("track-1") }
        assertTrue(host.resync(KeptItem("playlist/p", listOf(track("track-1"), track("track-2")))))
        eventually("new track") { cache.contains("track-2") }
        assertFalse(host.resync(KeptItem("playlist/other", listOf(track("track-3")))))
        assertFalse(cache.contains("track-3"))
    }

    @Test fun openingAnAddedPlaylistRenewsItLikeAnAddAndNewTracksGetThatDate() = runBlocking {
        val host = library()
        val playlist = KeptItem("playlist/p", listOf(track("track-1")))
        val albumB = KeptItem("album/b", listOf(track("track-2")))
        host.add(playlist); host.add(albumB)
        eventually("cached") { cache.contains("track-1") && cache.contains("track-2") }
        val addedWall = wall
        wall += 20 * day
        // The page was opened with an unchanged list: the playlist and its tracks get 30 days from now.
        assertTrue(host.resync(playlist))
        assertEquals(wall + 30 * day, host.status(playlist.key, playlist.trackIds).availableUntil)
        // An album's page re-sync keeps its date.
        assertTrue(host.resync(albumB))
        assertEquals(addedWall + 30 * day, host.status(albumB.key, albumB.trackIds).availableUntil)
        // A track found on a later visit is dated with that renewal, like the rest of the playlist.
        wall += 5 * day
        val grown = KeptItem("playlist/p", listOf(track("track-1"), track("pl-new")))
        assertTrue(host.resync(grown))
        eventually("new track") { cache.contains("pl-new") }
        assertEquals(wall + 30 * day, host.status(grown.key, grown.trackIds).availableUntil)
        wall += 29 * day
        cache.housekeep()
        assertTrue(cache.contains("track-1") && cache.contains("pl-new"))
        assertFalse("The album was not renewed", cache.contains("track-2"))
    }

    /** The catalogue asks for album details on any network while the server answers (no Wi-Fi rule). */
    @Test fun catalogueFetchesOnMobileDataAndHotspotsWhileReachableOnly() = runBlocking {
        network.value = mobile
        val host = library()
        eventually("reachable on mobile data") { host.catalogueFetchAllowed() }
        network.value = hotspot; changes.tryEmit(Unit)
        delay(100)
        eventually("reachable on a hotspot") { host.catalogueFetchAllowed() }
        reachable = false
        network.value = NetworkState(available = false, unmetered = false); changes.tryEmit(Unit)
        eventually("unreachable: no fetch") { !host.catalogueFetchAllowed() }
    }

    @Test fun expiryPausesWhileTheServerIsUnreachable() = runBlocking {
        reachable = false
        val host = library()
        eventually("checked") { !host.serverReachable.value }
        assertFalse(cache.expiring)
        network.value = NetworkState(available = false, unmetered = false)
        changes.tryEmit(Unit)
        delay(200)
        assertFalse(cache.expiring)
        reachable = true
        network.value = wifi; changes.tryEmit(Unit)
        eventually("reachable again") { host.serverReachable.value }
        assertTrue(cache.expiring)
    }

    /** No ping without a network; expiry stops as for a failed ping, nothing expires; the ping is back online. */
    @Test fun noPingWhileOfflineAndExpiryTreatsItAsAFailedPing() = runBlocking {
        val pings = java.util.concurrent.atomic.AtomicInteger()
        val skipped = java.util.concurrent.CopyOnWriteArrayList<dev.coda.core.connectivity.ServerGate.Kind>()
        network.value = NetworkState(available = false, unmetered = false)
        val r = reach(ping = { pings.incrementAndGet() })
        r.gate = dev.coda.core.connectivity.ServerGate(r, { System.nanoTime() / 1_000_000 }) { skipped += it }
        val host = library(reachability = r)
        seed("track-1")
        eventually("checked") { !host.serverReachable.value && !cache.expiring }
        host.poke(); delay(300)
        assertEquals("no ping offline", 0, pings.get())
        assertEquals("counted once per ping interval", listOf(dev.coda.core.connectivity.ServerGate.Kind.PING), skipped.toList())
        assertTrue("nothing expired or deleted", cache.contains("track-1"))
        network.value = NetworkState(available = true, unmetered = true); changes.tryEmit(Unit)
        // Back after two answers 2 s apart (the first probe's success schedules the second).
        eventually("pinged online") { pings.get() == 2 && host.serverReachable.value }
        assertTrue(cache.expiring)
    }

    /** A server answer that just arrived settles reachability: no ping, nothing skipped, the clock runs. */
    @Test fun aRecentAnswerStandsInForThePing() = runBlocking {
        val pings = java.util.concurrent.atomic.AtomicInteger()
        val skipped = java.util.concurrent.CopyOnWriteArrayList<dev.coda.core.connectivity.ServerGate.Kind>()
        val r = reach(ping = { pings.incrementAndGet() })
        r.gate = dev.coda.core.connectivity.ServerGate(r, { System.nanoTime() / 1_000_000 }) { skipped += it }
        r.succeeded()
        val host = library(reachability = r)
        eventually("checked") { host.serverReachable.value && cache.expiring }
        delay(300)
        assertTrue("fresh contact: reachable", host.serverReachable.value)
        assertEquals("no ping", 0, pings.get())
        assertEquals("nothing skipped", emptyList<dev.coda.core.connectivity.ServerGate.Kind>(), skipped.toList())
    }

    /** Online but backing off, the ping still runs and decides reachability, as before the gate. */
    @Test fun aBackoffAloneDoesNotMakeTheServerUnreachable() = runBlocking {
        val pings = java.util.concurrent.atomic.AtomicInteger()
        val r = reach(ping = { pings.incrementAndGet() })
        val gate = dev.coda.core.connectivity.ServerGate(r, { System.nanoTime() / 1_000_000 })
        r.gate = gate
        gate.failed()
        val host = library(reachability = r)
        eventually("pinged during the backoff") { pings.get() == 1 && host.serverReachable.value }
        assertTrue(cache.expiring)
    }

    /** A server answer within the ping interval (on the current network) stands in for the reachability ping. */
    @Test fun freshServerContactReplacesThePingButNotAcrossANetworkChange() = runBlocking {
        val pings = java.util.concurrent.atomic.AtomicInteger()
        val r = reach(ping = { pings.incrementAndGet() })
        r.succeeded()
        val host = library(reachability = r)
        eventually("checked") { cache.expiring && host.serverReachable.value }
        delay(300)
        assertEquals("fresh contact: no ping", 0, pings.get())
        // A network change: an answer from before it does not count.
        network.value = NetworkState(available = true, unmetered = true); changes.tryEmit(Unit)
        eventually("pinged after the network change") { pings.get() == 1 }
    }

    @Test fun makeRoomDeletesOnlyWhatItListedAndAsksAgainWhenThatChanged() = runBlocking {
        val host = library()
        seed("oldest"); wall += day
        seed("older"); wall += day
        seed("recent")
        val item = KeptItem("album/new", listOf(KeptTrack("track-1", SongFormat("mp3", 320, sizeBytes = trackBytes))))
        space.setFree(policy.keepFreeBytes + trackBytes - 500)
        val asked = host.add(item) as AddResult.NeedsRoom
        assertEquals(listOf("oldest"), asked.victims.map { it.songId })
        // Before OK, another app wrote 1 000 bytes: the add now needs a second file.
        space.othersBytes += 1_000
        val again = host.add(item, makeRoom = asked)
        assertTrue("Asks again", again is AddResult.NeedsRoom)
        again as AddResult.NeedsRoom
        assertEquals(listOf("oldest", "older"), again.victims.map { it.songId }); assertEquals(2_000, again.bytes)
        assertTrue("Nothing deleted beyond the presented plan", cache.contains("oldest") && cache.contains("older"))
        assertFalse(host.items.value.any { it.key == "album/new" })
        // Space came back: the confirmed list covers it, and only what is needed now goes.
        space.othersBytes -= 1_000
        assertTrue(host.add(item, makeRoom = again) is AddResult.Started)
        assertFalse(cache.contains("oldest")); assertTrue(cache.contains("older")); assertTrue(cache.contains("recent"))
        eventually("downloaded") { cache.contains("track-1") }
    }

    @Test fun makeRoomAsksAgainWhenAListedFileWasPlayedSince() = runBlocking {
        val host = library()
        seed("oldest"); wall += day
        seed("older"); wall += day
        val item = KeptItem("album/new", listOf(KeptTrack("track-1", SongFormat("mp3", 320, sizeBytes = trackBytes))))
        space.setFree(policy.keepFreeBytes + trackBytes - 500)
        val asked = host.add(item) as AddResult.NeedsRoom
        cache.markPlayed("oldest") // now the newest: "older" would go instead
        val again = host.add(item, makeRoom = asked) as AddResult.NeedsRoom
        assertEquals(listOf("older"), again.victims.map { it.songId })
        assertTrue(cache.contains("oldest") && cache.contains("older"))
    }

    @Test fun tracksThatAgedOutOfAnAddedSetAreNotDownloadedAgainUntilItIsAddedAgain() = runBlocking {
        val host = library()
        host.add(album)
        eventually("cached") { host.status(album.key, album.trackIds).complete }
        wall += 29 * day
        cache.markPlayed("track-1") // one track played on day 29
        wall += day
        cache.housekeep()
        assertFalse("track-2 aged out", cache.contains("track-2")); assertTrue(cache.contains("track-1"))
        val requests = server.streamRequests.size
        repeat(3) { host.poke(); delay(800) }
        assertEquals("Not downloaded again with the set's old date", requests, server.streamRequests.size)
        assertFalse(cache.contains("track-2"))
        // The set looks not kept, so the mark is gone; track-1 is ordinary music.
        val status = host.status(album.key, album.trackIds)
        assertFalse(status.added); assertEquals(0, status.pendingTracks); assertFalse(status.downloading)
        // The same holds for a track evicted to make room.
        space.setFree(policy.keepFreeBytes - 1)
        wall += 60_000
        cache.housekeep()
        assertFalse(cache.contains("track-1"))
        space.setFree(64 * GiB)
        repeat(2) { host.poke(); delay(800) }
        assertEquals(requests, server.streamRequests.size)
        // An explicit add downloads the whole set again.
        assertTrue(host.add(album) is AddResult.Started)
        eventually("added again") { host.status(album.key, album.trackIds).complete }
    }

    @Test fun aBackgroundSetDownloadMakesRoomFromMusicNotKeptEarliestKeepUntilFirst() = runBlocking {
        network.value = NetworkState(available = false, unmetered = true)
        val host = library()
        // An MP3 of ≤ 320 kbit/s stays an original in lossy mode too, so the size is exact.
        val item = KeptItem("album/s", listOf(KeptTrack("track-1", SongFormat("mp3", 320, sizeBytes = trackBytes))))
        assertTrue(host.add(item) is AddResult.Started) // offline: nothing downloads yet
        reachable = true
        wall += day
        seed("newer") // read ahead or played after the add
        space.setFree(policy.keepFreeBytes + trackBytes - 500)
        network.value = wifi; changes.tryEmit(Unit)
        eventually("downloaded") { cache.contains("track-1") }
        assertFalse("The only file that may go went", cache.contains("newer"))
    }

    // ---- CA-3 §7, §7.1, §10 ----

    @Test fun aConfirmedChangeDeletesInsideApplyPolicyWhileATransferIsStalledAndTheTickerKeepsRunning() = runBlocking {
        val host = library(housekeepingMs = 100)
        // A date written before the first verdict moves to the caught-up time; the verdict comes first here, so
        // the track really is 5 days old.
        cache.serverReachable(true)
        seed("heard-once")
        wall += 5 * day
        val release = java.util.concurrent.CountDownLatch(1)
        server.beforeStream = { id -> if (id == "track-1") release.await(10, java.util.concurrent.TimeUnit.SECONDS) }
        host.add(album)
        eventually("download stalled at the server") { server.streamRequests.any { it.id == "track-1" } }
        // The ticker is its own coroutine: it runs while the pass awaits the stalled transfer.
        val runs = host.maintenanceRuns.value
        eventually("ticks during the stalled pass") { host.maintenanceRuns.value >= runs + 2 }
        // Use less space: the track heard once 5 days ago goes, in the caller's coroutine, not after the transfer.
        val less = policy.with(StoragePreset.LESS_SPACE)
        val preview = host.preview(less)
        assertEquals(listOf("heard-once"), preview.evict.map { it.songId })
        assertNull(withTimeout(5_000) { host.applyPolicy(less, preview) })
        assertFalse(cache.contains("heard-once"))
        assertEquals(less, policy)
        release.countDown()
    }

    /** While degraded (the start's save failed), a new Keep free space is still saved, nothing is deleted. */
    @Test fun aPolicyChangeWhileDegradedIsSavedAndDeletesNothing() = runBlocking {
        val folder = temporary.newFolder()
        memoryTrackCache(folder).apply {
            listOf("a", "b").forEach { id -> begin(TrackKey(id, StreamFormat.RAW))!!.use { it.output.write("fLaC".toByteArray() + ByteArray(996)); it.commit() } }
            flush()
        }
        val host = library(folder, active = MutableStateFlow(false), savesFail = true)
        eventually("first pass") { host.revision.value > 0 }
        assertTrue(cache.contains("a") && cache.contains("b"))
        val line = policy.copy(keepFreeBytes = 2 * GiB)
        space.setFree(2 * GiB - 1_500)
        val shown = host.preview(line)
        assertTrue("the sheet lists no deletion", shown.isEmpty)
        assertNull(host.applyPolicy(line, shown))
        assertEquals("saved", line, policy)
        assertTrue("nothing deleted", cache.contains("a") && cache.contains("b"))
    }

    @Test fun aShownPreviewThatNoLongerMatchesSavesNothingAndReturnsTheNewPlan() = runBlocking {
        val host = library(active = MutableStateFlow(false))
        eventually("first pass") { host.revision.value > 0 }
        seed("a", size = 1_000); seed("b", size = 1_000)
        val saved = policy
        val line = policy.copy(keepFreeBytes = 2 * GiB)
        space.setFree(2 * GiB - 1_500)
        val shown = host.preview(line)
        assertEquals(2_000, shown.bytes)
        // A new kept set since the sheet opened: refused, nothing saved or deleted.
        host.add(KeptItem("album/new", listOf(KeptTrack("a"), KeptTrack("b")), "New"))
        space.setFree(2 * GiB - 1_500)
        val changed = host.applyPolicy(line, shown)!!
        assertEquals(listOf("album/new"), changed.keptSetsGone.map { it.key })
        assertTrue(OfflineLibrary.changedMaterially(shown, changed))
        assertEquals(saved, policy); assertTrue(cache.contains("a") && cache.contains("b"))
        // Confirming the new numbers saves and deletes; no notice, the sheet named the set.
        assertNull(host.applyPolicy(line, changed))
        assertEquals(line, policy); assertFalse(cache.contains("a")); assertFalse(cache.isAdded("album/new"))
        assertNull(host.removedForSpace.value)
        // 6 % more than shown (and over 100 MB) is material; less is fine.
        val base = CachePlanner.Plan(emptyList(), listOf(Evictable("x", 2_000_000_000, 0, 0, "x")), emptyList(), emptyList(), 0, emptySet())
        val more = base.copy(notKept = listOf(Evictable("x", 2_120_000_000, 0, 0, "x")))
        assertTrue(OfflineLibrary.changedMaterially(base, more))
        assertFalse(OfflineLibrary.changedMaterially(base, base.copy(notKept = listOf(Evictable("x", 2_090_000_000, 0, 0, "x")))))
        assertFalse(OfflineLibrary.changedMaterially(more, base))
    }

    /**
     * Rex round 7: raising Keep free space while a download streams must not save without the sheet. A measurement that
     * overlaps a write used to count as "no space need", so the change saved silently and the ticker then deleted the
     * kept album nobody had confirmed.
     */
    @Test fun aLineRaisedWhileADownloadStreamsIsNeverSavedWithoutTheSheet() = runBlocking {
        val host = library(active = MutableStateFlow(false))
        eventually("first pass") { host.revision.value > 0 }
        seed("a", size = 1_000)
        host.add(KeptItem("album/k", listOf(KeptTrack("a")), "Keep"))
        val saved = policy
        val line = policy.copy(keepFreeBytes = 2 * GiB)
        space.setFree(2 * GiB - 1_500)
        val write = cache.begin(TrackKey("streaming", StreamFormat.RAW))!!
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val streamer = Thread { while (!stop.get()) { write.output.write(ByteArray(1)) } }.also { it.start() }
        try {
            repeat(5) {
                val plan = host.preview(line)
                assertEquals("the sheet names the album", listOf("album/k"), plan.removedSets.map { it.key })
                val refused = host.applyPolicy(line, null)
                assertEquals("nothing is saved silently", listOf("album/k"), refused!!.removedSets.map { it.key })
                assertEquals(saved, policy); assertTrue(cache.contains("a") && cache.isAdded("album/k"))
            }
        } finally { stop.set(true); streamer.join(); write.close() }
        assertNull(host.removedForSpace.value)
    }

    @Test fun aChangeThatDeletesNothingSavesAtOnce() = runBlocking {
        val host = library(active = MutableStateFlow(false))
        seed("fresh")
        val stream = policy.with(StoragePreset.STREAM_LESS)
        assertTrue(host.preview(stream).isEmpty)
        assertNull(host.applyPolicy(stream, null))
        assertEquals(stream, policy); assertTrue(cache.contains("fresh"))
    }

    /**
     * With nothing due the ticker waits for the slow fallback; a song written and a track change run a pass at
     * once, and an expiry that falls between events is caught by the fallback.
     */
    @Test fun eventsRunAPassAndTheFallbackCatchesAnExpiryBetweenEvents() = runBlocking {
        val host = library(housekeepingMs = 100, fallbackMs = 6_000)
        eventually("start-up pass") { host.maintenanceRuns.value >= 1 }
        delay(300)
        val start = host.maintenanceRuns.value
        delay(1_000)
        assertEquals("no 100 ms tick while nothing is due", start, host.maintenanceRuns.value)
        assertEquals(0, host.tickerWakeups.value)
        seed("track-1")
        eventually("a pass after the song was written") { host.maintenanceRuns.value > start }
        val afterWrite = host.maintenanceRuns.value
        cache.markPlayed("track-1")
        eventually("a pass after the track change") { host.maintenanceRuns.value > afterWrite }
        assertEquals("events, not the timer", 0, host.tickerWakeups.value)
        // No event: the expiry is caught by the fallback.
        wall += 31 * day
        eventually("expired by the fallback pass") { !cache.contains("track-1") }
        assertTrue(host.tickerWakeups.value >= 1)
    }

    /** On screen the ticker keeps the short interval (other apps' files are noticed as before); off screen the fallback. */
    @Test fun onScreenKeepsTheShortIntervalAndOffScreenTheFallback() = runBlocking {
        val visible = MutableStateFlow(true)
        val host = library(housekeepingMs = 100, fallbackMs = 60_000, visible = visible)
        eventually("start-up pass") { host.maintenanceRuns.value >= 1 }
        eventually("short ticks on screen") { host.tickerWakeups.value >= 3 }
        visible.value = false
        delay(200)
        val wakeups = host.tickerWakeups.value
        delay(800)
        assertEquals("no short tick off screen with nothing due", wakeups, host.tickerWakeups.value)
    }

    /** While the last pass left something due (below the line), the ticker keeps the short interval. */
    @Test fun somethingStillDueKeepsTheShortInterval() = runBlocking {
        val host = library(housekeepingMs = 100, fallbackMs = 60_000)
        eventually("start-up pass") { host.maintenanceRuns.value >= 1 }
        space.setFree(policy.keepFreeBytes - 10)
        host.maintain()
        // Nothing removable below the line is remembered, not due; being below the line is near it (the short interval).
        assertTrue(cache.nearLine)
        val wakeups = host.tickerWakeups.value
        eventually("short ticks while due") { host.tickerWakeups.value >= wakeups + 3 }
    }

    private fun tickerNearLine(freeAboveLine: Long, lit: Boolean, shown: Boolean = false): Pair<Int, Int> = runBlocking {
        val host = library(housekeepingMs = 100, fallbackMs = 60_000, screenOn = MutableStateFlow(lit), screenOffMs = 800,
            visible = MutableStateFlow(shown))
        eventually("start-up pass") { host.maintenanceRuns.value >= 1 }
        space.setFree(policy.keepFreeBytes + freeAboveLine)
        host.maintain()
        val runs = host.maintenanceRuns.value
        delay(500)
        val early = host.maintenanceRuns.value - runs
        delay(900)
        early to (host.maintenanceRuns.value - runs)
    }

    @Test fun nearTheLineWithTheScreenOnKeepsTheShortInterval() {
        val (early, _) = tickerNearLine(10, lit = true)   // 10 bytes above the line: inside the margin
        assertTrue("short ticks near the line, screen on: $early", early >= 3)
    }

    @Test fun nearTheLineWithTheScreenOffUsesTheFiveMinuteInterval() {
        val (early, later) = tickerNearLine(10, lit = false)
        assertEquals("no pass before the screen-off interval", 0, early)
        assertTrue("then one", later >= 1)
    }

    @Test fun belowTheLineForGoodWithTheScreenOffIsCappedAtTheScreenOffInterval() {
        val (early, later) = tickerNearLine(-10, lit = false)
        assertEquals("no 60 s passes with the screen off", 0, early)
        assertTrue("then one", later in 1..2)
    }

    @Test fun belowTheLineOnScreenKeepsTheShortInterval() {
        val (early, _) = tickerNearLine(-10, lit = false, shown = true)
        assertTrue("on screen: short ticks: $early", early >= 3)
    }

    @Test fun farFromTheLineOnlyTheSlowFallbackRuns() {
        val (_, later) = tickerNearLine(10L * GiB, lit = false)
        assertEquals("nothing near the line: no timer pass", 0, later)
    }

    @Test fun becomingIdleCancelsThePendingTimerSoNoWakeupSurvives() = runBlocking {
        val active = MutableStateFlow(true)
        val host = library(housekeepingMs = 300, active = active)
        eventually("start-up pass") { host.maintenanceRuns.value >= 1 }
        eventually("first tick") { host.tickerWakeups.value >= 1 }
        // Just after a tick the next delay is pending; going idle must cancel it, not let it wake up and skip.
        active.value = false
        delay(50)
        val wakeups = host.tickerWakeups.value
        delay(900)
        assertEquals("No timer wake-up while idle", wakeups, host.tickerWakeups.value)
        active.value = true
        eventually("ticking again") { host.tickerWakeups.value >= wakeups + 1 }
    }

    @Test fun theTickerRunsOnlyWhileCodaIsActive() = runBlocking {
        val foreground = MutableStateFlow(false)
        val playing = MutableStateFlow(false)
        val job = MutableStateFlow(false)
        val active = kotlinx.coroutines.flow.combine(foreground, playing, job) { a, b, c -> a || b || c }
            .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)
        val host = library(housekeepingMs = 50, active = active)
        eventually("start-up pass") { host.maintenanceRuns.value >= 1 }
        // Background, paused (the service may still be alive), no download job: no ticks.
        val idle = host.maintenanceRuns.value
        delay(400)
        assertEquals("No ticks while idle", idle, host.maintenanceRuns.value)
        playing.value = true
        eventually("ticks while playing") { host.maintenanceRuns.value >= idle + 2 }
        playing.value = false
        delay(120)
        val paused = host.maintenanceRuns.value
        delay(400)
        assertEquals("No ticks once paused again", paused, host.maintenanceRuns.value)
        foreground.value = true
        eventually("ticks in the foreground") { host.maintenanceRuns.value >= paused + 2 }
        foreground.value = false; job.value = true
        val withJob = host.maintenanceRuns.value
        eventually("ticks while the download job runs") { host.maintenanceRuns.value >= withJob + 2 }
    }

    @Test fun maintenanceRemovesAKeptAlbumForSpaceRecordsTheNoticeAndUnpinsNothingElse() = runBlocking {
        val host = library(active = MutableStateFlow(false))
        host.add(album)
        eventually("downloaded") { host.status(album.key, album.trackIds).complete }
        space.setFree(policy.keepFreeBytes - 10)
        host.maintain()
        assertFalse(host.items.value.any { it.key == album.key })
        assertEquals(1, host.removedForSpace.value!!.titles.size)
        host.snackbarShown()
        assertNotNull(host.removedForSpace.value!!.snackbarAt)
        host.acknowledgeRemoved()
        assertNull(host.removedForSpace.value)
    }

    @Test fun downloadWholeQueueRenewsTracksAlreadyOnThePhone() = runBlocking {
        policy = CachePolicy.DEFAULT
        val host = library(active = MutableStateFlow(false))
        cache.serverReachable(true)
        seed("track-1")
        wall += 2 * day
        host.downloadQueue(listOf(track("track-1"), track("track-2")))
        val now = cache.now()
        assertEquals(now, cache.record("track-1")!!.addedAt)
        eventually("downloaded") { cache.contains("track-2") }
        assertEquals(now + 3 * day, cache.keepUntil(cache.record("track-1")!!))
    }

    @Test fun theTickerChecksReachabilityItselfSoAnOutageWithNoDownloadWorkPausesExpiry() = runBlocking {
        var monotonic = 0L
        // The shared state's confirmation probe needs real time to pass as well (3 s), so the clock moves with it.
        val clock = { monotonic + System.nanoTime() / 1_000_000 }
        val host = library(housekeepingMs = 100, nowMs = clock, reachability = reach(nowMs = clock))
        eventually("first pass") { host.revision.value > 0 }
        assertTrue(host.serverReachable.value); assertTrue(cache.expiring)
        // The server goes away; the network state does not change and nothing is pending, so the download loop sleeps.
        reachable = false
        monotonic += 6 * 60_000L   // the next check is due
        eventually("the ticker noticed") { !host.serverReachable.value && !cache.expiring }
    }

    /** Fake covers: [hold] keeps every fetch in flight until it is cancelled (a slow request). */
    private inner class Covers(@Volatile var hold: Boolean = false) : CoverStore.Source {
        val fetched = java.util.concurrent.CopyOnWriteArrayList<String>()
        val cancelled = java.util.concurrent.atomic.AtomicInteger()
        @Volatile var audioDoneAtFirstFetch: Boolean? = null
        @Volatile var albums: KeptItem? = null
        override fun cached(id: String, size: Int): ByteArray? = null
        override suspend fun fetch(id: String, size: Int): ByteArray? {
            if (audioDoneAtFirstFetch == null) audioDoneAtFirstFetch = albums?.trackIds?.all(cache::contains)
            fetched += id
            if (hold) try { awaitCancellation() } finally { cancelled.incrementAndGet() }
            return ByteArray(64) { 1 }
        }
    }
    private val covered = KeptItem("album/album-1", listOf("track-1", "track-2", "track-3").mapIndexed { n, id ->
        KeptTrack(id, SongFormat("flac", 900, 2), TrackMeta(id, coverArtId = "cover-$n")) }, coverArt = "cover-0")

    /** Covers never hold up the audio: the first cover fetch starts once the set's audio is on the phone. */
    @Test fun keptCoversAreFetchedAfterTheAudio() = runBlocking {
        val covers = Covers().also { it.albums = covered }
        val host = library(coverSource = covers)
        host.add(covered)
        eventually("covers stored") { (0..2).all { n -> CoverStore.WANTED.all { cache.covers.has("cover-$n", it) } } }
        assertEquals(true, covers.audioDoneAtFirstFetch)
        assertEquals("472 and 1440 of each", 6, covers.fetched.size)
    }

    /**
     * The job stops during the covers-only phase (after the last audio download). The kept covers stay
     * due, so the job is rescheduled (DownloadJobService.onStopJob, the scheduler), and the next run stores them.
     */
    @Test fun aCoverFillCutShortByAJobStopStaysDueAndCompletesOnTheNextRun() = runBlocking {
        val allowed = MutableStateFlow(true)
        val covers = Covers(hold = true).also { it.albums = covered }
        val host = library(allowed = allowed, coverSource = covers)
        host.add(covered)
        eventually("audio stored, a cover fetch in flight") { covers.fetched.isNotEmpty() && host.downloads.value.pending == 0 }
        allowed.value = false
        eventually("the fetch cancelled") { covers.cancelled.get() == 1 }
        eventually("covers still due after the stop") { host.downloads.value.let { !it.active && !it.coversPending && it.coversDue } }
        covers.hold = false; allowed.value = true
        eventually("the next run stores them") { (0..2).all { n -> CoverStore.WANTED.all { cache.covers.has("cover-$n", it) } } }
        eventually("nothing due any more") { !host.downloads.value.coversDue }
    }

    /**
     * The process restarts with the kept audio complete and its covers missing. The fresh library publishes
     * the covers as pending from its constructor on (the reachability check still running), so the restarted job keeps going.
     */
    @Test fun aFreshLibraryWithOnlyKeptCoversMissingPublishesThemAsPendingAtOnce() = runBlocking {
        val folder = temporary.newFolder()
        val before = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val covers = Covers(hold = true).also { it.albums = covered }
        val first = library(folder, coverSource = covers, runIn = before)
        first.add(covered)
        eventually("audio stored, a cover fetch in flight") { covers.fetched.isNotEmpty() && first.downloads.value.pending == 0 }
        cache.flush()
        before.cancel() // the process dies

        val restarted = library(folder, ping = { awaitCancellation() }, coverSource = Covers(hold = true))
        assertTrue("audio complete", covered.trackIds.all(cache::contains))
        val state = restarted.downloads.value
        assertEquals(0, state.pending)
        assertTrue("covers due", state.coversDue)
        assertTrue("covers pending: the job does not finish", state.coversPending)
    }

    /** Kept covers left due off Wi-Fi are no longer due once their album is removed there. */
    @Test fun keptCoversStopBeingDueWhenTheAlbumIsRemovedOffWifi() = runBlocking {
        val allowed = MutableStateFlow(true)
        val covers = Covers(hold = true).also { it.albums = covered }
        val host = library(allowed = allowed, coverSource = covers)
        host.add(covered)
        eventually("audio stored, a cover fetch in flight") { covers.fetched.isNotEmpty() && host.downloads.value.pending == 0 }
        allowed.value = false
        eventually("covers due off Wi-Fi") { host.downloads.value.let { !it.coversPending && it.coversDue } }
        host.stopKeeping(covered.key)
        eventually("no longer due") { !host.downloads.value.coversDue }
    }

    /**
     * A keep finishing in the background, through the job/gate transition: the job ends (closing the
     * transfer gate) as soon as the state says nothing is left, as DownloadJobService does. The covers are stored first.
     */
    @Test fun aBackgroundKeepStoresItsCoversBeforeTheJobEnds() = runBlocking {
        val allowed = MutableStateFlow(true)
        val covers = Covers().also { it.albums = covered }
        val host = library(allowed = allowed, coverSource = covers)
        val job = scope.launch {
            // The job's rule (DownloadJob.kt): finish when neither audio nor the pass's kept covers are pending.
            host.downloads.collect { state -> if (state.pending == 0 && !state.coversPending && covered.trackIds.all(cache::contains)) allowed.value = false }
        }
        host.add(covered)
        eventually("the job ended") { !allowed.value }
        job.cancel()
        assertTrue("every kept cover stored before the job ended", (0..2).all { n -> CoverStore.WANTED.all { cache.covers.has("cover-$n", it) } })
    }

    /** A played album's missing sizes are fetched on Wi-Fi only with the screen on; nothing with the screen off. */
    @Test fun aPlayedAlbumGetsItsCoversOnlyWithTheScreenOn() = runBlocking {
        val screen = MutableStateFlow(false)
        val covers = Covers()
        val host = library(screenOn = screen, coverSource = covers)
        cache.markPlayed("track-9", TrackMeta("track-9", coverArtId = "played-1"))
        host.poke(); delay(1_000)
        assertEquals("no artwork fetch with the screen off", emptyList<String>(), covers.fetched.toList())
        screen.value = true
        host.poke()
        eventually("both sizes stored") { CoverStore.WANTED.all { cache.covers.has("played-1", it) } }
        assertEquals(listOf("played-1", "played-1"), covers.fetched.toList())
    }

    /** Leaving proper Wi-Fi cancels the running cover fetch and stops the rest; Wi-Fi back resumes them. */
    @Test fun leavingWifiStopsTheCoverFillMidway() = runBlocking {
        val covers = Covers(hold = true).also { it.albums = covered }
        val host = library(coverSource = covers)
        host.add(covered)
        eventually("a cover fetch in flight") { covers.fetched.isNotEmpty() }
        network.value = mobile; changes.tryEmit(Unit)
        eventually("the fetch cancelled") { covers.cancelled.get() == 1 }
        host.poke(); delay(1_000)
        assertEquals("Nothing more off Wi-Fi", 1, covers.fetched.size)
        network.value = wifi; changes.tryEmit(Unit)
        eventually("fetching again on Wi-Fi") { covers.fetched.size >= 2 }
    }

    /** The transfer job stopping cancels the running cover fetch; nothing is fetched after the stop. */
    @Test fun theJobStoppingStopsTheCoverFill() = runBlocking {
        val allowed = MutableStateFlow(true)
        val covers = Covers(hold = true).also { it.albums = covered }
        val host = library(allowed = allowed, coverSource = covers)
        host.add(covered)
        eventually("a cover fetch in flight") { covers.fetched.isNotEmpty() }
        allowed.value = false
        eventually("the fetch cancelled") { covers.cancelled.get() == 1 }
        host.poke(); delay(1_000)
        assertEquals("Nothing after the job stopped", 1, covers.fetched.size)
        assertFalse(cache.covers.has(covers.fetched.first(), CoverStore.SMALL))
    }

    /**
     * The successor's complete copy comes from "Load for offline", not the read-ahead (whose callback never
     * fires for it): the streaming successor is prepared again from that file, on the player's thread.
     */
    @Test fun aStreamingSuccessorThatALoadForOfflineCompletedPlaysFromTheFile() = runBlocking {
        val host = library()
        val main = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val engine = dev.coda.core.playback.FakeAudioEngine()
        val player = withContext(main) {
            dev.coda.core.playback.PlaybackController(engine, sourceResolver = dev.coda.core.connectivity.CachedSourceResolver({ cache }, { wifi }))
        }
        try {
            withContext(main) {
                player.replaceQueue(listOf("track-1", "track-2").map { dev.coda.core.playback.QueueEntry(it, formatName = "FLAC", bitRate = 900, durationMs = 120_000) })
            }
            assertEquals(dev.coda.core.playback.TrackSource.Remote("track-2", StreamFormat.RAW), withContext(main) { engine.next!!.source })
            val follower = CoroutineScope(scope.coroutineContext + main).launch {
                QueueCacheWorker.followPublishedCopies(MutableStateFlow(host)) { player.cacheChanged() }
            }
            host.add(KeptItem("album/album-2", listOf(track("track-2"))))
            eventually("the successor plays from the file") {
                runBlocking(main) { engine.next?.source is dev.coda.core.playback.TrackSource.CachedFile }
            }
            follower.cancel()
        } finally { main.close() }
    }
}
