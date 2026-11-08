package dev.coda.core.cache

import dev.coda.core.testing.testTrackCache
import dev.coda.core.connectivity.CachedSourceResolver
import dev.coda.core.connectivity.NetworkState
import dev.coda.core.playback.QueueEntry
import dev.coda.core.playback.StreamFormat
import dev.coda.core.playback.StreamFormat.OPUS_128
import dev.coda.core.playback.StreamFormat.RAW
import dev.coda.core.playback.TrackSource
import dev.coda.core.testing.FakeFreeSpace
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Shared fixtures: a fake phone, a fake wall clock and synthetic audio heads. */
abstract class CacheFixture {
    @get:Rule val temporary = TemporaryFolder()
    protected val day = CacheRules.DAY_MS
    protected val GiB = 1L shl 30
    protected var wall = 1_000 * CacheRules.DAY_MS
    protected lateinit var root: File
    protected lateinit var space: FakeFreeSpace
    protected val wifi = NetworkState(true, true, false)
    /** The CA-2 line, for the tests (they run with 1 month × 3). */
    protected val RESERVE = 1L shl 30
    /** the fixed 30 days as CA-3 settings: T1 = T2 = T3 = 1 month equals `max(addedAt, lastPlayedAt) + 30 days` (§1.1). */
    protected var policy = CachePolicy(Lifetime.MONTH, Lifetime.MONTH, Lifetime.MONTH, keepFreeBytes = 1L shl 30)
    protected val mobile = NetworkState(true, false, true)

    @Before fun setUpFixture() {
        root = temporary.newFolder("music")
        space = FakeFreeSpace(temporary.root, 64 * GiB)
    }

    /** Released files are deleted on [deletes]; it runs them at once unless a test holds them back. */
    protected val deletes = mutableListOf<Runnable>()
    protected var holdDeletes = false
    protected fun open(directory: File = root) =
        testTrackCache(directory, space, { task -> if (holdDeletes) deletes += task else task.run() }, { policy }) { wall }.also { it.serverReachable(true) }

    protected val flac = "fLaC".toByteArray() + ByteArray(60)
    protected val opus = "OggS".toByteArray() + ByteArray(24) + "OpusHead".toByteArray() + ByteArray(28)

    protected fun TrackCache.put(id: String, format: StreamFormat = RAW, size: Int = 100, type: String? = null,
        song: SongFormat = SongFormat(), meta: TrackMeta? = null, addedAt: Long? = null): Boolean {
        val head = if (format == RAW || type == "audio/flac") flac else opus
        return begin(TrackKey(id, format))!!.use { it.output.write(head + ByteArray((size - head.size).coerceAtLeast(0))); it.commit(type, song, meta, addedAt) }
    }

    protected fun audioFiles(directory: File = root) = directory.listFiles()!!.filter { it.isFile && it.name.matches(Regex("[a-f0-9]{40}\\.(orig|opus)")) }
}

class TrackCacheTest : CacheFixture() {
    @Test fun recordsWhatArrivedNotWhatWasRequested() {
        val cache = open()
        cache.put("fallback", OPUS_128, type = "audio/flac", song = SongFormat("flac", 900))
        cache.put("transcode", OPUS_128, type = "audio/ogg", song = SongFormat("mp3", 320), meta = TrackMeta("T", "A", "Album"))
        cache.put("original", RAW, type = "audio/mpeg")
        val fallback = cache.record("fallback")!!
        assertEquals(RAW, fallback.kind); assertTrue(fallback.file.endsWith(".orig")); assertEquals(true, fallback.above320)
        assertEquals("audio/flac", fallback.contentType); assertEquals(100L, fallback.bytes)
        val transcode = cache.record("transcode")!!
        assertEquals(OPUS_128, transcode.kind); assertTrue(transcode.file.endsWith(".opus")); assertEquals(false, transcode.above320)
        assertEquals(TrackMeta("T", "A", "Album"), transcode.meta)
        assertEquals(RAW, cache.kind("original")); assertNull(cache.record("original")!!.above320)
        // The file name now says what arrived, and the cached-source label reads it.
        assertNull(TrackSource.CachedFile(cache.resolve("fallback")!!.path).transcodedFormat)
        assertEquals(OPUS_128, TrackSource.CachedFile(cache.resolve("transcode")!!.path).transcodedFormat)
        // Stable, credential-free names: one per song and kind.
        assertTrue(TrackKey("42", RAW).fileName.matches(Regex("[a-f0-9]{40}\\.orig")))
        assertNotEquals(TrackKey("42", RAW).fileName, TrackKey("42", OPUS_128).fileName)
    }

    @Test fun theIndexSurvivesRestartOrphansGoAndPartsOfBothFormatsStay() {
        val cache = open()
        cache.put("a", meta = TrackMeta("Title"))
        cache.markPlayed("a")
        cache.flush()
        val orphan = File(root, TrackKey("orphan", RAW).fileName).apply { writeBytes(flac) }
        val opusPart = File(root, TrackKey("b", OPUS_128).fileName + ".part").apply { writeBytes(opus) }
        val origPart = File(root, TrackKey("c", RAW).fileName + ".part").apply { writeBytes(flac) }
        val reopened = open()
        assertEquals(cache.record("a"), reopened.record("a"))
        assertFalse(orphan.exists()); assertTrue("BAT-1b: transcodes resume too", opusPart.exists()); assertTrue(origPart.exists())
        assertNull(reopened.record("orphan"))
    }

    @Test fun aCachedOriginalIsNeverDownloadedAgainAndOpusOnlyGivesWayToTheOriginal() {
        val cache = open()
        cache.put("original", RAW)
        assertNull(cache.begin(TrackKey("original", RAW)))
        assertNull(cache.begin(TrackKey("original", OPUS_128)))
        cache.put("o", OPUS_128, type = "audio/ogg")
        cache.markPlayed("o")
        val played = cache.record("o")!!.lastPlayedAt
        assertNull(cache.begin(TrackKey("o", OPUS_128)))
        val opusFile = cache.resolve("o")!!
        assertTrue(cache.put("o", RAW))
        assertEquals(RAW, cache.kind("o")); assertEquals(played, cache.record("o")!!.lastPlayedAt)
        assertFalse("The Opus copy goes once the original is complete", opusFile.exists())
        assertEquals(1, audioFiles().count { it.name.startsWith(songFileBase("o")) })
    }

    @Test fun anOpusCopyThePlayerHoldsGoesWhenReleased() {
        val cache = open()
        cache.put("o", OPUS_128, type = "audio/ogg")
        val held = cache.acquire("o")!!
        cache.put("o", RAW)
        assertTrue(held.exists()); assertEquals(RAW, cache.kind("o"))
        cache.retainLeases(listOf(held.path))
        assertTrue(held.exists())
        cache.retainLeases(emptyList())
        assertFalse(held.exists())
    }

    @Test fun tracksAgeOutThirtyDaysAfterTheLaterOfLastPlayedAndAdded() {
        val cache = open()
        cache.put("played"); cache.put("idle")
        wall += 29 * day
        cache.housekeep()
        assertTrue(cache.contains("played") && cache.contains("idle"))
        cache.markPlayed("played") // became current
        wall += day
        cache.housekeep()
        assertFalse("Aged out at 30 days", cache.contains("idle"))
        assertTrue(cache.contains("played"))
        wall += 29 * day - 1
        cache.housekeep(); assertTrue(cache.contains("played"))
        wall += 1
        cache.housekeep(); assertFalse(cache.contains("played"))
        assertTrue(audioFiles().isEmpty())
    }

    @Test fun nothingExpiresWhileTheServerIsUnreachableAndExpiredMusicLeavesOnceItIsBack() {
        // Plain dates; offline nothing leaves for being old.
        val cache = open()
        cache.put("trip")
        val arrived = cache.record("trip")!!.addedAt
        cache.serverReachable(false)
        wall += 45 * day
        cache.housekeep()
        assertTrue("Nothing vanishes during a trip", cache.contains("trip"))
        assertEquals("The real date", arrived + 30 * day, cache.status(null, listOf("trip")).availableUntil)
        cache.serverReachable(true)
        cache.housekeep(); assertFalse("Expired music leaves at the next cleanup", cache.contains("trip"))
    }

    @Test fun windowAndPlayerFilesNeverExpireUntilReleased() {
        val cache = open()
        cache.put("window"); cache.put("playing")
        cache.pin(listOf("window"))
        val held = cache.acquire("playing")!!
        wall += 40 * day
        cache.housekeep()
        assertTrue(cache.contains("window") && cache.contains("playing"))
        cache.pin(emptyList()); cache.retainLeases(listOf(held.path))
        cache.housekeep()
        assertFalse(cache.contains("window")); assertTrue(cache.contains("playing"))
        cache.retainLeases(emptyList()); cache.housekeep()
        assertFalse(cache.contains("playing"))
    }

    @Test fun belowTheReserveTheOldestFilesGoSilentlyWhateverTheirFormat() {
        val cache = open()
        cache.put("oldest-opus", OPUS_128, type = "audio/ogg"); wall += day
        cache.put("older-original"); wall += day
        cache.put("newest")
        space.setFree(RESERVE - 150)
        cache.housekeep()
        assertFalse(cache.contains("oldest-opus")); assertFalse(cache.contains("older-original")); assertTrue(cache.contains("newest"))
        assertTrue(space.allocatableBytes() >= RESERVE)
    }

    @Test fun downloadsMakeRoomOldestFirstButNeverFromTheirOwnAddOrWindowAndOnlyTheWindowUsesTheReserve() {
        val cache = open()
        cache.put("own"); wall += day
        cache.put("other"); wall += day
        cache.put("newer")
        space.setFree(RESERVE + 50)
        // Needs 100: "own" is protected, so the oldest other file goes.
        assertTrue(cache.makeRoom(100, protect = setOf("own")))
        assertTrue(cache.contains("own")); assertFalse(cache.contains("other")); assertTrue(cache.contains("newer"))
        // Strictly oldest first with no age limit of its own: a newer file goes when nothing older may.
        space.setFree(RESERVE)
        assertTrue(cache.makeRoom(50, protect = setOf("own")))
        assertFalse(cache.contains("newer")); assertTrue(cache.contains("own"))
        cache.put("newer")
        // The read-ahead window may go below the reserve, down to Android's own line (allocatable 0).
        space.setFree(RESERVE / 2)
        cache.pin(listOf("own", "newer"))
        assertFalse(cache.makeRoom(1_000, window = false))
        assertTrue(cache.makeRoom(1_000, window = true))
        assertFalse(cache.makeRoom(RESERVE, window = true))
        assertTrue("Pinned files are never evicted", cache.contains("own") && cache.contains("newer"))
    }

    private fun track(id: String, seconds: Int = 100) = KeptTrack(id, SongFormat("flac", 1000, seconds))

    @Test fun anAddNeedsItsMissingTracksInTodaysFormatAndOpusCountsAsMissingInLosslessWifi() {
        val cache = open()
        cache.put("cached-original"); cache.put("cached-opus", OPUS_128, type = "audio/ogg")
        val item = KeptItem("album/a", listOf(track("cached-original"), track("cached-opus"), track("missing"), track("missing")))
        val original = 100L * 1000 * 125
        val opus = (100 * 16_000 * 1.02).toLong()
        assertEquals(2 * original, cache.planAdd(item, wifi).needBytes)
        assertEquals(RAW, cache.planAdd(item, wifi).format)
        assertEquals(opus, cache.planAdd(item, mobile).needBytes)
        assertEquals(OPUS_128, cache.planAdd(item, mobile).format)
        // No lossy mode; free space never changes the format.
        space.setFree(6 * GiB)
        assertEquals(2 * original, cache.planAdd(item, wifi).needBytes)
    }

    @Test fun makeRoomListsOldestFilesAndTheAddedItemsTheyBelongToOrSaysWhatIsMissing() {
        val cache = open()
        cache.put("kept-old", size = 1_000); wall += day
        cache.put("loose", size = 1_000); wall += day
        cache.put("mine", size = 1_000)
        cache.renew(KeptItem("album/old", listOf(KeptTrack("kept-old"))))
        // Renewal made kept-old the newest; date it back for this scenario by letting time pass first.
        wall += day
        cache.put("loose-newer", size = 1_000)
        val item = KeptItem("album/new", listOf(KeptTrack("mine"), KeptTrack("new", SongFormat("mp3", 320, sizeBytes = 1_500))))
        space.setFree(RESERVE + 200)
        val plan = cache.planAdd(item, wifi)
        assertEquals(1_500, plan.needBytes)
        // Music not kept goes first, earliest keep-until first; the kept album stays.
        assertEquals(listOf("loose", "loose-newer"), plan.evict.map { it.songId })
        assertEquals(emptyList<String>(), plan.affectedSets); assertEquals(2, plan.otherTracks)
        assertTrue("Planning deletes nothing", cache.contains("loose"))
        assertTrue(cache.evictExactly(plan.evict, plan.affectedSets))
        assertFalse(cache.contains("loose")); assertTrue(cache.contains("kept-old")); assertTrue(cache.contains("mine"))
        // When that isn't enough, the kept album leaves whole, named, and its kept mark goes with it.
        space.setFree(RESERVE + 200)
        val more = cache.planAdd(KeptItem("album/new", listOf(KeptTrack("mine"), KeptTrack("new", SongFormat("mp3", 320, sizeBytes = 1_100)))), wifi)
        assertEquals(listOf("kept-old"), more.evict.map { it.songId })
        assertEquals(listOf("album/old"), more.affectedSets); assertEquals(listOf("Album"), more.titles)
        assertTrue(cache.evictExactly(more.evict, more.affectedSets))
        assertFalse(cache.contains("kept-old")); assertFalse(cache.isAdded("album/old"))
        // Too big: even deleting everything else that may go leaves bytes missing.
        space.setFree(RESERVE + 200)
        val huge = KeptItem("album/huge", listOf(KeptTrack("x", SongFormat("mp3", 256, sizeBytes = 10_000))))
        val tooBig = cache.planAdd(huge, wifi)
        assertEquals(10_000L - 200 - 1_000, tooBig.missingBytes)
        assertTrue(tooBig.evict.isEmpty())
    }

    @Test fun anAddRenewsThirtyDaysForEveryTrackAndStatusReportsAvailableUntil() {
        val cache = open()
        cache.put("a"); cache.put("b")
        wall += 20 * day
        val item = KeptItem("album/x", listOf(KeptTrack("a"), KeptTrack("b"), KeptTrack("c")))
        cache.renew(item)
        assertEquals(cache.now(), cache.record("a")!!.addedAt)
        val status = cache.status("album/x", item.trackIds)
        assertEquals(ItemStatus(2, 3, 200, wall + 30 * day, added = true, pendingTracks = 1), status)
        assertFalse(status.complete)
        wall += 29 * day
        cache.housekeep()
        assertTrue(cache.contains("a"))
        // A re-sync changes the list without renewing.
        val renewedAt = cache.addedSets.single().addedAt
        assertTrue(cache.resync(item.copy(tracks = listOf(KeptTrack("a")))))
        assertEquals(listOf("a"), cache.addedSets.single().trackIds)
        assertEquals(renewedAt, cache.addedSets.single().addedAt)
        assertFalse(cache.resync(KeptItem("playlist/never-added", emptyList())))
        // Once its tracks have aged out, the set itself goes.
        wall += day
        cache.housekeep()
        assertTrue(cache.addedSets.isEmpty())
    }

    @Test fun aRenewingResyncGivesTheSetAndItsTracksThirtyDaysFromNowButNeverRevivesAnAgedOutSet() {
        val cache = open()
        cache.put("a"); cache.put("b")
        val item = KeptItem("playlist/p", listOf(KeptTrack("a"), KeptTrack("b")))
        cache.renew(item)
        wall += 20 * day
        // Opening an added playlist's page renews it like an add, also with an unchanged list.
        assertTrue(cache.resync(item, renew = true))
        assertEquals(cache.now(), cache.addedSets.single().addedAt)
        assertEquals(cache.now(), cache.record("a")!!.addedAt)
        assertEquals(wall + 30 * day, cache.status(item.key, item.trackIds).availableUntil)
        // A new track is pending and only it: no refill of tracks that already left.
        cache.deleteNow(null, listOf("b"))
        assertTrue(cache.resync(item.copy(tracks = item.tracks + KeptTrack("c")), renew = true))
        assertEquals(setOf("c"), cache.pending(item.key))
        // Once every track has aged out, a visit doesn't bring the set back.
        wall += 31 * day
        assertFalse(cache.resync(item, renew = true))
        cache.housekeep()
        assertTrue(cache.addedSets.isEmpty())
    }

    @Test fun deleteNowKeepsTracksOfOtherAddedItemsAndHeldFilesGoWhenReleased() {
        val cache = open()
        listOf("1", "2", "3", "loose").forEach { cache.put(it) }
        cache.renew(KeptItem("album/a", listOf(KeptTrack("1"), KeptTrack("2"))))
        cache.renew(KeptItem("playlist/b", listOf(KeptTrack("2"), KeptTrack("3"))))
        val held = cache.acquire("1")!!
        cache.pin(listOf("1")) // read-ahead may fetch it again; it doesn't stop the delete
        cache.deleteNow("album/a", listOf("1", "2"))
        assertFalse(cache.contains("1")); assertTrue(held.exists())
        assertTrue("Shared with playlist/b", cache.contains("2"))
        assertEquals(listOf("playlist/b"), cache.addedSets.map { it.key })
        cache.retainLeases(emptyList()); assertFalse(held.exists())
        cache.deleteNow("track/loose", listOf("loose"))
        assertFalse(cache.contains("loose"))
    }

    @Test fun originalsAndTranscodesResumeFromTheConfirmedLengthEvenAfterAReservation() {
        var cache = open()
        val crashed = cache.begin(TrackKey("o", RAW))!!
        assertEquals(0, crashed.resumeFrom)
        assertTrue(crashed.reserve(1_000))
        assertEquals(listOf(1_000L), space.allocations)
        crashed.output.write(flac)
        crashed.checkpoint()
        crashed.output.write(ByteArray(36))
        // The process dies here: no close, and the reserved file is 1 000 bytes long.
        assertEquals(1_000L, File(root, TrackKey("o", RAW).fileName + ".part").length())
        cache = open()
        // The marker, not the file length, says how much arrived.
        val resumed = cache.begin(TrackKey("o", RAW))!!
        assertEquals(64L, resumed.resumeFrom)
        assertEquals(64L, resumed.written)
        resumed.output.write(ByteArray(36))
        resumed.close()
        val again = cache.begin(TrackKey("o", RAW))!!
        assertEquals(100, again.resumeFrom)
        again.output.write(ByteArray(20))
        assertTrue(again.commit())
        assertEquals(120L, cache.resolve("o")!!.length())
        assertArrayEquals(flac, cache.resolve("o")!!.readBytes().copyOf(64))
        assertTrue(root.listFiles()!!.none { it.name.contains(".part") })
        // An interrupted transcode keeps its partial file too (the Prefetcher checks an overlap before appending).
        cache.begin(TrackKey("t", OPUS_128))!!.use { it.output.write(opus) }
        cache.begin(TrackKey("t", OPUS_128))!!.use {
            assertEquals(opus.size.toLong(), it.resumeFrom)
            assertArrayEquals(opus.copyOfRange(opus.size - 16, opus.size), it.readBack(it.resumeFrom, 16))
        }
    }

    /** A transcode partial survives a new cache instance (process restart), with what the server said about its file. */
    @Test fun anInterruptedTranscodeAndItsIdentitySurviveANewCacheInstance() {
        var cache = open()
        cache.begin(TrackKey("t", OPUS_128))!!.use { it.output.write(opus); it.identity = "\"v1\"\t\t5000" }
        cache.begin(TrackKey("t", OPUS_128))!!.use { it.restart(); assertNull(it.identity) }
        cache.begin(TrackKey("t", OPUS_128))!!.use { it.output.write(opus); it.identity = "\"v1\"\t\t5000" }
        cache = open()
        assertTrue(File(root, TrackKey("t", OPUS_128).fileName + ".part").isFile)
        cache.begin(TrackKey("t", OPUS_128))!!.use {
            assertEquals(opus.size.toLong(), it.resumeFrom)
            assertEquals("\"v1\"\t\t5000", it.identity)
            it.restart()
        }
        cache.begin(TrackKey("t", OPUS_128))!!.use { assertEquals(0, it.resumeFrom); assertNull(it.identity) }
    }

    @Test fun concurrentWritersAndPartialFilesAreNeverCacheHits() {
        val cache = open()
        cache.begin(TrackKey("a", RAW))!!.use {
            it.output.write(ByteArray(12)); assertNull(cache.resolve("a")); assertNull(cache.begin(TrackKey("a", RAW)))
            assertNull("One download per song", cache.begin(TrackKey("a", OPUS_128)))
        }
        assertNull(cache.resolve("a"))
    }

    @Test fun storageErrorsReleaseTheWriterSlotAndAMissingDirectoryRecovers() {
        val directory = File(root, "cache")
        val cache = open(directory)
        directory.deleteRecursively(); directory.writeText("not a directory")
        assertThrows(java.io.IOException::class.java) { cache.begin(TrackKey("a", RAW)) }
        directory.delete()
        cache.begin(TrackKey("a", RAW))!!.use { it.output.write(ByteArray(3)); it.commit() }
        assertEquals(3, cache.resolve("a")!!.length())
    }

    @Test fun handOffFollowsWhatPlaysAndLeasesTheFile() {
        val cache = open()
        cache.put("original"); cache.put("opus", OPUS_128, type = "audio/ogg")
        fun resolve(id: String, network: NetworkState, reachable: Boolean = true) =
            CachedSourceResolver({ cache }, { network }, { reachable }).resolve(QueueEntry(id, formatName = "FLAC", bitRate = 900))
        assertEquals(TrackSource.CachedFile(cache.resolve("original")!!.path), resolve("original", mobile))
        assertEquals(TrackSource.Remote("opus", RAW), resolve("opus", wifi))
        assertEquals(TrackSource.CachedFile(cache.resolve("opus")!!.path), resolve("opus", wifi, reachable = false))
        assertEquals(TrackSource.CachedFile(cache.resolve("opus")!!.path), resolve("opus", mobile))
        assertEquals(TrackSource.Remote("none", OPUS_128), resolve("none", mobile))
        // The hand-off leased the file: a delete waits for the player to release it.
        val leased = cache.resolve("original")!!
        cache.deleteNow(null, listOf("original"))
        assertTrue(leased.exists())
        cache.retainLeases(emptyList()); assertFalse(leased.exists())
        // No lossy mode; a nearly full phone still streams and fetches originals on Wi-Fi.
        space.setFree(1 * GiB)
        assertEquals(TrackSource.Remote("opus", RAW), resolve("opus", wifi))
        assertEquals(TrackSource.Remote("none", RAW), resolve("none", wifi))
    }

    @Test fun serverScopesNeverContainCredentials() {
        val a = TrackCache.serverKey("https://example.invalid", "alice")
        assertEquals(16, a.length)
        assertNotEquals(a, TrackCache.serverKey("https://example.invalid", "bob"))
        assertNotEquals(a, TrackCache.serverKey("https://other.invalid", "alice"))
        assertFalse(TrackKey("42", RAW).fileName.contains("t="))
    }

    @Test fun serverSpellingsShareOneNamespace() {
        val key = TrackCache.serverKey("https://example.invalid/music", "alice")
        for (url in listOf("http://example.invalid/music/", "https://EXAMPLE.invalid:443/music/",
            "http://example.invalid:80/music", "https://example.invalid/music///")) {
            assertEquals(key, TrackCache.serverKey(url, "alice"))
        }
        assertNotEquals(key, TrackCache.serverKey("https://example.invalid:8443/music", "alice"))
    }

    // ---- Review fixes (2026-10-26) ----

    private class Crash : RuntimeException()

    @Test fun aCrashAnywhereInAnOpusToOriginalReplacementLeavesAPlayableCopy() {
        for (point in listOf("commit.renamed", "commit.persisted")) {
            root.deleteRecursively(); root.mkdirs()
            val cache = open()
            cache.put("o", OPUS_128, type = "audio/ogg")
            cache.markPlayed("o"); cache.flush()
            val played = cache.record("o")!!.lastPlayedAt
            cache.crashPoint = { if (it == point) throw Crash() }
            assertThrows(Crash::class.java) { cache.put("o", RAW) }
            // The process dies here; the next start reads only what is on disk.
            val reopened = open()
            val record = reopened.record("o")
            assertNotNull("$point: the song is still cached", record)
            assertTrue("$point: its file exists", reopened.resolve("o")!!.isFile)
            // The row is written before the original takes its name. Crashed before the rename, the Opus copy
            // plays (the original's part resumes); after it, the complete original wins.
            val kind = if (point == "commit.persisted") OPUS_128 else RAW
            assertEquals("$point: the copy that is there", kind, record!!.kind)
            assertEquals(played, record.lastPlayedAt)
            assertEquals("$point: one copy left", listOf(TrackKey("o", kind).fileName), audioFiles().map { it.name })
        }
    }

    @Test fun aCrashBetweenTheIndexWriteAndTheRenameNeverDeletesADownload() {
        // Rex defect 2 (files-vs-db review): a crash between moving a finished download into place and writing the index
        // threw the download away as an orphan at the next start. Now the row comes first.
        for (point in listOf("commit.persisted", "commit.renamed")) {
            root.deleteRecursively(); root.mkdirs()
            val cache = open()
            cache.renew(KeptItem("album/x", listOf(KeptTrack("n"))))
            cache.crashPoint = { if (it == point) throw Crash() }
            assertThrows(Crash::class.java) { cache.put("n", RAW, size = 500) }
            val reopened = open()
            if (point == "commit.renamed") {
                // Renamed: the complete file is listed and stays.
                assertEquals(point, RAW, reopened.kind("n"))
                assertTrue(point, reopened.resolve("n")!!.isFile)
                assertTrue("$point: settled in the same write", reopened.pending("album/x").isEmpty())
            } else {
                // Not renamed yet: no row without its file, and the partial download is still there to resume.
                assertNull(point, reopened.record("n"))
                assertTrue(point, root.listFiles()!!.any { it.name == TrackKey("n", RAW).fileName + ".part" })
                assertEquals(setOf("n"), reopened.pending("album/x"))
            }
        }
    }

    @Test fun aRetiredCacheNeverRecreatesItsFolderAfterTheAccountWipe() {
        // Sol P2: a read-ahead commit paused after its rename, the account wipe deletes the folder meanwhile.
        val cache = open()
        cache.put("a")
        val paused = java.util.concurrent.CountDownLatch(1); val resume = java.util.concurrent.CountDownLatch(1)
        cache.crashPoint = { if (it == "commit.renamed") { paused.countDown(); resume.await() } }
        val writer = Thread { runCatching { cache.put("b") } }.apply { start() }
        assertTrue(paused.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val wipe = Thread { cache.retire(); root.deleteRecursively() }.apply { start() }
        Thread.sleep(50) // the wipe is waiting for the commit (or, without the fence, already deleted)
        resume.countDown()
        writer.join(5_000); wipe.join(5_000)
        cache.crashPoint = {}
        assertFalse("the commit did not bring the folder back", root.exists())
        // Later writers of the retired cache write nothing either.
        assertNull(cache.begin(TrackKey("c", RAW)))
        cache.markPlayed("a"); cache.flush()
        cache.waveforms.store("a", List(WaveformStore.BARS) { 0.5f })
        cache.covers.put("al-1", CoverStore.KEPT, ByteArray(8) { 1 })
        assertFalse("nothing recreated the folder", root.exists())
    }

    @Test fun aWaveformLoadAcrossRetirementAndWipeRecreatesNoDatabaseAndKeepsTheCurrentStore() {
        // Playback dispatched a waveform load for account A; the switch to B retires A and wipes it meanwhile.
        val libraries = temporary.newFolder("library")
        var current: dev.coda.core.library.LibraryStore? = null
        // CodaApp.libraryStore: the same store while the key stays, otherwise a new one that replaces the current one.
        val app = Any()
        fun libraryStore(key: String) = synchronized(app) {
            current?.takeIf { it.file.parentFile.name == key && !it.isClosed } ?: dev.coda.core.library.LibraryStore(
                File(libraries, "$key/library.db"),
                { path -> dev.coda.store.LibraryDatabase.open(androidx.room.Room.databaseBuilder<dev.coda.store.LibraryDatabase>(path),
                    androidx.sqlite.driver.bundled.BundledSQLiteDriver(), kotlinx.coroutines.Dispatchers.IO) },
            ).also { current = it }
        }
        val paused = java.util.concurrent.CountDownLatch(1); val resume = java.util.concurrent.CountDownLatch(1)
        val hold = java.util.concurrent.atomic.AtomicBoolean(false)
        var owner: TrackCache? = null
        val index = CacheIndexDb(retired = { owner?.retired == true }) {
            if (hold.compareAndSet(true, false)) { paused.countDown(); resume.await() }
            libraryStore("A")
        }
        val cache = TrackCache(root, index, space, { it.run() }, { policy }, wall = { wall++ }).also { owner = it }
        // More waveforms than memory holds: the oldest is read from the database again.
        repeat(60) { cache.waveforms.store("w$it", List(WaveformStore.BARS) { 0.5f }) }
        assertNull(cache.waveforms.peaks("w0"))

        hold.set(true)
        val loader = Thread { runCatching { cache.waveforms.load(listOf("w0")) } }.apply { start() }
        assertTrue(paused.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val wipe = Thread {
            cache.retire()
            synchronized(app) { current?.close(); current = null }
            File(libraries, "A").deleteRecursively(); root.deleteRecursively()
            libraryStore("B")
        }.apply { start() }
        Thread.sleep(50) // the wipe waits for the running load (without the fence it has already deleted A)
        resume.countDown()
        loader.join(5_000); wipe.join(5_000)

        // Later reads of the retired cache ask nothing of A's database either.
        cache.waveforms.load(listOf("w1"))
        index.covers().load()
        assertNull(index.waveforms.dates())
        assertFalse("A's library.db was not recreated", File(libraries, "A").exists())
        assertEquals(File(libraries, "B/library.db"), current!!.file)
        assertFalse(current!!.isClosed)
    }

    @Test fun releasedFilesAreDeletedOffTheCallingThreadAndANewerCopyWithTheSameNameStays() {
        holdDeletes = true
        val cache = open()
        cache.put("o", OPUS_128, type = "audio/ogg")
        val held = cache.acquire("o")!!
        cache.put("o", RAW)
        cache.retainLeases(emptyList())
        assertTrue("retainLeases (main thread) does no file I/O", held.exists())
        assertEquals(1, deletes.size)
        deletes.removeAt(0).run()
        assertFalse(held.exists())
        // Released and queued; meanwhile the same song is cached again under the same name.
        cache.deleteNow(null, listOf("o"))
        cache.put("o", OPUS_128, type = "audio/ogg")
        val first = cache.acquire("o")!!
        cache.deleteNow(null, listOf("o"))
        assertTrue("Held: it goes when released", first.exists())
        cache.retainLeases(emptyList())
        assertEquals(1, deletes.size)
        cache.put("o", OPUS_128, type = "audio/ogg")
        assertEquals(first.name, cache.resolve("o")!!.name)
        deletes.removeAt(0).run()
        assertTrue("The newer copy stays", cache.resolve("o")!!.isFile)
    }

    @Test fun aPlayBeforeTheDownloadFinishesIsKeptWhenItCommits() {
        val cache = open()
        val setDate = wall - 25 * day
        val writer = cache.begin(TrackKey("streamed", RAW))!!
        cache.markPlayed("streamed") // became current while it streamed and downloaded
        val playedAt = cache.now()
        writer.use { it.output.write(flac); it.commit(addedAt = setDate) }
        assertEquals(playedAt, cache.record("streamed")!!.lastPlayedAt)
        wall += 6 * day
        cache.housekeep()
        assertTrue("Dated by the play, not the older add", cache.contains("streamed"))
        // A later play still wins over an older early one.
        cache.markPlayed("later"); wall += day
        cache.markPlayed("later")
        cache.put("later")
        assertEquals(cache.now(), cache.record("later")!!.lastPlayedAt)
    }

    @Test fun tracksThatLeftAnAddedSetAreNotPendingUntilItIsAddedAgain() {
        val cache = open()
        cache.put("a"); cache.put("b")
        val album = KeptItem("album/x", listOf(KeptTrack("a"), KeptTrack("b"), KeptTrack("c")))
        cache.renew(album)
        assertEquals(setOf("c"), cache.pending("album/x"))
        cache.put("c")
        assertTrue(cache.pending("album/x").isEmpty())
        wall += 29 * day
        cache.markPlayed("a")
        wall += day
        cache.housekeep()
        assertFalse(cache.contains("b")); assertTrue(cache.contains("a"))
        assertTrue("Aged out, not pending", cache.pending("album/x").isEmpty())
        // The set then looks not kept, so its mark is dropped; its remaining track is ordinary music.
        assertFalse(cache.isAdded("album/x")); assertEquals(0, cache.status("album/x", album.trackIds).pendingTracks)
        assertTrue("Survives a restart", open().pending("album/x").isEmpty() && !open().isAdded("album/x"))
        // An explicit add downloads the whole set again.
        cache.renew(album)
        assertEquals(setOf("b", "c"), cache.pending("album/x"))
        cache.deleteNow("album/x", album.trackIds)
        assertTrue(cache.pending("album/x").isEmpty())
    }

    @Test fun aConfirmedMakeRoomDeletesNothingWhenAListedFileChangedSincePlanning() {
        fun scenario(change: (TrackCache) -> Unit): Pair<TrackCache, Boolean> {
            root.deleteRecursively(); root.mkdirs()
            val cache = open()
            cache.put("a", size = 1_000); wall += day
            cache.put("b", size = 1_000); wall += day
            space.setFree(RESERVE + 200)
            val plan = cache.planAdd(KeptItem("album/new", listOf(KeptTrack("n", SongFormat("mp3", 320, sizeBytes = 1_500)))), wifi)
            assertEquals(listOf("a", "b"), plan.evict.map { it.songId })
            change(cache)
            return cache to cache.evictExactly(plan.evict)
        }
        val changes = mapOf<String, (TrackCache) -> Unit>(
            "played" to { it.markPlayed("b") },
            "renewed" to { it.renew(KeptItem("album/b", listOf(KeptTrack("b")))) },
            "cached again" to { it.deleteNow(null, listOf("a")); it.put("a", size = 1_200) },
            "pinned" to { it.pin(listOf("a")) },
            "held" to { it.acquire("b") },
            "deleted" to { it.deleteNow(null, listOf("a")) },
        )
        for ((what, change) in changes) {
            val (cache, deleted) = scenario(change)
            assertFalse("$what: nothing deleted", deleted)
            assertEquals("$what: the other file stays", what != "deleted", cache.contains("a"))
            assertTrue("$what: b stays", cache.contains("b"))
        }
        val (cache, deleted) = scenario {}
        assertTrue(deleted); assertFalse(cache.contains("a")); assertFalse(cache.contains("b"))
        assertTrue(audioFiles().isEmpty())
    }

    @Test fun earlyPlayDatesOfOutstandingDownloadsSurviveTheCap() {
        val cache = open()
        val writer = cache.begin(TrackKey("downloading", RAW))!!
        cache.markPlayed("downloading")
        val playedAt = cache.now()
        wall += day
        cache.renew(KeptItem("album/p", listOf(KeptTrack("pending-in-set"))))
        cache.markPlayed("pending-in-set")
        cache.markPlayed("streamed")
        (1..253).forEach { cache.markPlayed("s$it") }
        // Exactly at the cap (256 entries): nothing is dropped.
        cache.put("streamed")
        assertNotNull("At the cap", cache.record("streamed")!!.lastPlayedAt)
        cache.markPlayed("streamed-2")
        (254..255).forEach { cache.markPlayed("s$it") }
        // Over the cap: the oldest entries go, but not the running download or the set's pending track.
        cache.put("s1"); assertNull("Oldest plain stream dropped", cache.record("s1")!!.lastPlayedAt)
        cache.put("s3"); assertNotNull(cache.record("s3")!!.lastPlayedAt)
        writer.use { it.output.write(flac); it.commit(addedAt = wall - 40 * day) }
        assertEquals(playedAt, cache.record("downloading")!!.lastPlayedAt)
        cache.put("pending-in-set")
        assertNotNull(cache.record("pending-in-set")!!.lastPlayedAt)
    }

}
