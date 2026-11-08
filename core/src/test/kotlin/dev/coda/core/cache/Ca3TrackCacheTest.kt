package dev.coda.core.cache

import dev.coda.core.testing.testTrackCache
import dev.coda.core.playback.StreamFormat.OPUS_128
import dev.coda.core.playback.StreamFormat.RAW
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** "TrackCacheTest": the line, whole kept sets, the notice, the play counter, renewed arrivals, plain dates. */
class Ca3TrackCacheTest : CacheFixture() {
    private val line = 10_000L

    @Before fun balanced() { policy = CachePolicy.DEFAULT.copy(keepFreeBytes = line) }

    private fun TrackCache.keep(key: String, vararg ids: String, title: String? = key.substringAfter('/')) =
        renew(KeptItem(key, ids.map { KeptTrack(it) }, title))

    @Test fun maintenanceKeepsTheLineNotKeptFirstThenWholeKeptSetsAndTheWindowMayStayBelowIt() {
        val cache = open()
        cache.put("k1", size = 1_000); cache.put("k2", size = 1_000); cache.keep("album/k", "k1", "k2", title = "Glass Orchard")
        cache.put("loose", size = 1_000)
        cache.put("window", size = 1_000); cache.pin(listOf("window"))
        // The window's own 1000 bytes may sit below the line: 9000 free needs nothing.
        space.setFree(line - 1_000)
        assertTrue(cache.preview().isEmpty)
        // 1500 short of (line − window): the loose track, then the kept album as a whole.
        space.setFree(line - 2_500)
        val plan = cache.preview()
        assertEquals(listOf("loose"), plan.notKept.map { it.songId })
        assertEquals(listOf("album/k"), plan.removedSets.map { it.key })
        assertEquals(plan.evict.map { it.songId }.toSet(), setOf("loose", "k1", "k2"))
        // The preview equals what the following maintenance pass deletes on the same state.
        // Round 8: the pass that deleted the loose track leaves the kept album for the next tick.
        assertEquals(emptyList<String>(), cache.housekeep())
        assertFalse(cache.contains("loose")); assertTrue(cache.isAdded("album/k"))
        wall += 60_000
        assertEquals(listOf("album/k"), cache.housekeep())
        assertEquals(setOf("window"), cache.records().map { it.songId }.toSet())
        assertFalse(cache.isAdded("album/k"))
        // Recorded for the notice, once.
        val notice = cache.removedForSpace!!
        assertEquals(listOf("Glass Orchard"), notice.titles); assertEquals(2_000, notice.bytes); assertNull(notice.snackbarAt)
    }

    @Test fun aUnitRemovalIsAllOrNothingWhenAFileGetsLeasedBetweenPlanAndDelete() {
        val cache = open()
        cache.put("k1", size = 1_000); cache.put("k2", size = 1_000); cache.keep("album/k", "k1", "k2")
        space.setFree(line - 500)
        val left = cache.housekeepIf(policy, notice = true) { plan ->
            assertEquals(listOf("album/k"), plan.removedSets.map { it.key })
            cache.acquire("k2"); true
        }
        assertEquals(emptyList<String>(), left)
        assertTrue(cache.contains("k1") && cache.contains("k2") && cache.isAdded("album/k"))
        assertNull(cache.removedForSpace)
    }

    @Test fun aCrashBetweenIndexWriteAndFileDeleteLeavesNoKeptMarkAndTheFileGoesForSpace() {
        val cache = open()
        cache.put("k1", size = 1_000); cache.keep("album/k", "k1")
        val file = cache.resolve("k1")!!
        space.setFree(line - 500)
        cache.crashPoint = { if (it == "housekeep.persisted") throw IllegalStateException("crash") }
        assertThrows(IllegalStateException::class.java) { cache.housekeep() }
        assertTrue("The file was not deleted yet", file.exists())
        val reopened = open()
        assertFalse(reopened.isAdded("album/k")); assertFalse(reopened.contains("k1"))
        // A file without a row is never deleted at start; it is a legacy file and leaves for space first.
        assertFalse(file.exists()); assertTrue(File(root, "legacy/${file.name}").isFile)
        assertNotNull(reopened.removedForSpace)
        reopened.housekeep()
        assertFalse("The unkept leftover went for space", File(root, "legacy/${file.name}").exists())
    }

    @Test fun theNoticeMergesSurvivesAReopenAndOnlyOkRemovesIt() {
        val cache = open()
        cache.put("a", size = 1_000); cache.keep("album/a", "a", title = "Glass Orchard")
        space.setFree(line - 500); cache.housekeep()
        cache.put("b", size = 1_000); cache.keep("album/b", "b", title = "Paper Moons")
        wall += 60_000
        space.setFree(line - 500); cache.housekeep()
        val merged = cache.removedForSpace!!
        assertEquals(listOf("Glass Orchard", "Paper Moons"), merged.titles); assertEquals(2_000, merged.bytes); assertEquals(wall, merged.at)
        assertTrue(merged.snackbarDue(wall))
        // Showing the snackbar is delivery only: the record stays, across a reopen, and no second snackbar within 24 h.
        cache.snackbarShown()
        val reopened = open()
        val after = reopened.removedForSpace!!
        assertEquals(merged.titles, after.titles); assertEquals(wall, after.snackbarAt)
        assertFalse(after.snackbarDue(wall + day - 1))
        // A newer removal shows it again only once 24 h have passed since the last showing.
        assertFalse(after.copy(at = wall + 10).snackbarDue(wall + 20))
        assertTrue(after.copy(at = wall + 10).snackbarDue(wall + day))
        reopened.acknowledgeRemoved()
        assertNull(reopened.removedForSpace); assertNull(open().removedForSpace)
    }

    @Test fun cancelDropsTheMarkOfAnIncompleteSetAndKeepsItsAudioAsOrdinaryMusic() {
        val cache = open()
        cache.put("k1", size = 100); cache.keep("album/k", "k1", "k2", title = "Glass Orchard")
        cache.put("c1", size = 100); cache.keep("album/c", "c1")
        // Still downloading (k2 pending): the mark stays, maintenance does not touch it.
        assertEquals(emptyList<String>(), cache.housekeep())
        assertTrue(cache.isAdded("album/k"))
        assertEquals(listOf("album/k"), cache.clearPending())
        assertFalse("it looks not kept, so it is not kept", cache.isAdded("album/k"))
        assertTrue("a complete set keeps its mark", cache.isAdded("album/c"))
        assertTrue("no audio is deleted", cache.contains("k1"))
        // k1 is now ordinary music: Heard once (3 days), not as long as possible; c1 is still kept for ever.
        val left = cache.status(null, listOf("k1"))
        assertFalse(left.untilSpaceRunsLow); assertNotNull(left.availableUntil)
        assertTrue(cache.status("album/c", listOf("c1")).untilSpaceRunsLow)
        assertNull("no removal notice for a dropped mark", cache.removedForSpace)
        wall += 4 * day
        cache.housekeep()
        assertFalse("ages out with the normal Heard once rule", cache.contains("k1")); assertTrue(cache.contains("c1"))
    }

    @Test fun cancelOnAnOldKeptAlbumStartsItsTracksFreshAtCancelNotFromTheOldDate() {
        val cache = open()
        cache.put("a1", size = 100); cache.put("a2", size = 100); cache.put("s1", size = 100)
        cache.keep("album/a", "a1", "a2", "a3")      // a3 never arrived: pending
        cache.keep("album/s", "s1", "a2")             // a2 is shared with a still-kept, complete set
        cache.put("p1", size = 100); cache.keep("album/p", "p1", "p2")
        wall += 60 * day                               // two months later: the dates are old
        cache.markPlayed("p1")                         // played: its play-based date is later than the drop's
        wall += 20 * day
        val dropAt = wall
        assertTrue(cache.clearPending().containsAll(listOf("album/a", "album/p")))
        assertFalse(cache.isAdded("album/a")); assertTrue(cache.isAdded("album/s"))
        assertEquals("no deletion at the drop or the next pass", emptyList<String>(), cache.housekeep())
        assertTrue(cache.contains("a1") && cache.contains("a2") && cache.contains("p1"))
        assertEquals(dropAt + 3 * day, cache.keepUntil(cache.record("a1")!!))
        assertTrue("shared with a kept set: unchanged", cache.status("album/s", listOf("s1", "a2")).untilSpaceRunsLow)
        assertTrue("never shortened", cache.keepUntil(cache.record("p1")!!) >= dropAt + 3 * day)
        wall = dropAt + 3 * day - 60 * 60_000L          // day 2.96
        cache.housekeep()
        assertTrue(cache.contains("a1")); assertTrue(cache.contains("a2"))
        wall = dropAt + 3 * day + 60_000
        cache.housekeep()
        assertFalse("gone only after Heard once from the drop", cache.contains("a1"))
        assertTrue("still held by the kept set", cache.contains("a2") && cache.contains("s1"))
    }

    @Test fun aPlayedTrackKeepsItsLongerPlayBasedDateWhenTheMarkDrops() {
        val cache = open()
        cache.put("k1", size = 100); cache.keep("album/k", "k1", "k2")
        wall += 10 * day
        cache.markPlayed("k1"); cache.markPlayed("k1")   // played twice: Heard again applies
        val playBased = cache.record("k1")!!.lastPlayedAt!! + policy.heardAgain.ms!!
        wall += day
        cache.clearPending()
        assertEquals("the play-based date is longer than the drop's Heard once", playBased, cache.keepUntil(cache.record("k1")!!))
        assertTrue(playBased > wall + 3 * day)
    }

    @Test fun theExpiryPathDropAlsoRestartsTheTracksAndTheWaitForWifiPastThreeDaysIsSafe() {
        val cache = open()
        cache.put("w1", size = 100); cache.keep("album/w", "w1", "w2")   // w2 waits for Wi-Fi
        wall += 5 * day                                                    // longer than Heard once
        cache.clearPending()                                               // Cancel after the long wait
        val dropAt = wall
        assertFalse(cache.isAdded("album/w"))
        cache.housekeep()
        assertTrue("on the phone after the wait", cache.contains("w1"))
        assertEquals(dropAt + 3 * day, cache.keepUntil(cache.record("w1")!!))
        // Housekeep drop: a set that lost a track while idle, two months after it was added.
        cache.put("e1", size = 100); cache.put("e2", size = 100); cache.keep("album/e", "e1", "e2")
        wall += 60 * day
        val remove = TrackCache::class.java.getDeclaredMethod("removeTrack", String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
        remove.isAccessible = true
        remove.invoke(cache, "e2", null, true)
        val at = wall
        assertEquals(listOf("album/e"), cache.housekeep())
        assertTrue(cache.contains("e1"))
        assertEquals(at + 3 * day, cache.keepUntil(cache.record("e1")!!))
    }

    @Test fun aKeptSetThatLostTracksWhileIdleLosesItsMarkAtMaintenance() {
        val cache = open()
        cache.put("k1", size = 100); cache.put("k2", size = 100); cache.keep("album/k", "k1", "k2")
        assertTrue(cache.status("album/k", listOf("k1", "k2")).looksKept)
        val remove = TrackCache::class.java.getDeclaredMethod("removeTrack", String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
        remove.isAccessible = true
        remove.invoke(cache, "k2", null, true)
        assertEquals(listOf("album/k"), cache.housekeep())
        assertFalse(cache.isAdded("album/k")); assertTrue(cache.contains("k1"))
        // Its tracks coming back later do not make it kept again.
        cache.put("k2", size = 100)
        assertFalse(cache.status("album/k", listOf("k1", "k2")).looksKept)
    }

    @Test fun theDeadSetRuleNeverFiresForAsLongAsPossibleAndStatusSaysUntilSpaceRunsLow() {
        val cache = open()
        cache.put("e", size = 100); cache.keep("album/e", "e", "missing")
        wall += 200 * day; cache.markPlayed("e")
        wall += 200 * day
        cache.housekeep()
        assertTrue(cache.isAdded("album/e")); assertTrue(cache.contains("e"))
        // Example E: kept 400 days ago, played once 200 days ago → no date.
        val status = cache.status("album/e", listOf("e"))
        assertTrue(status.untilSpaceRunsLow); assertNull(status.availableUntil)
        assertNull("kept as long as possible: no line", DownloadLines.keepSheet(status.copy(pendingTracks = 0), SetPhase.IDLE, null))
        // With a finite Kept offline time the set's tracks get a date and, once gone, the set itself.
        policy = policy.copy(kept = Lifetime.YEAR)
        assertFalse(cache.status("album/e", listOf("e")).untilSpaceRunsLow)
        cache.housekeep()
        assertFalse(cache.contains("e")); assertFalse(cache.isAdded("album/e"))
        assertNull("Kept sets that ran out are not a removal for space", cache.removedForSpace)
    }

    @Test fun downloadWholeQueueRenewsTheArrivalOfEveryTarget() {
        val cache = open()
        cache.put("soon"); cache.put("kept"); cache.keep("album/k", "kept"); cache.put("again")
        cache.markPlayed("again"); cache.markPlayed("again")
        wall += 3 * day - 3_600_000
        val countBefore = cache.record("again")!!.playCount
        cache.renewArrival(listOf("soon", "kept", "again", "missing"))
        val now = cache.now()
        assertEquals(now + 3 * day, cache.keepUntil(cache.record("soon")!!))
        assertEquals(Long.MAX_VALUE, cache.keepUntil(cache.record("kept")!!))
        // Heard again two days and 23 hours ago: its 2 weeks are later than a fresh 3 days, and stay.
        assertEquals(cache.record("again")!!.lastPlayedAt!! + 14 * day, cache.keepUntil(cache.record("again")!!))
        assertEquals(countBefore, cache.record("again")!!.playCount)
        // A missing target gets its arrival when it commits.
        cache.put("missing")
        assertEquals(now + 3 * day, cache.keepUntil(cache.record("missing")!!))
        assertEquals(listOf("album/k"), cache.addedSets.map { it.key })
    }

    @Test fun plainDatesOfflineNothingLeavesForBeingOldOnlineItLeavesAtTheNextCleanup() {
        // Real dates; offline only Keep free space removes music, expired first.
        val cache = open()
        cache.put("old", size = 1_000)
        val arrived = cache.record("old")!!.addedAt
        cache.serverReachable(false)
        wall += 400 * day
        cache.put("fresh", size = 1_000)
        cache.housekeep()
        assertTrue("offline: old music stays", cache.contains("old"))
        assertEquals("the date is the real one", arrived, cache.record("old")!!.addedAt)
        // A restart before the first verdict is offline too.
        val reopened = testTrackCache(root, space, { it.run() }, { policy }) { wall }
        reopened.housekeep()
        assertTrue(reopened.contains("old") && reopened.contains("fresh"))
        // Short of the line while offline: the expired track goes first, the fresh one stays.
        space.setFree(line - 500)
        reopened.housekeep()
        assertFalse(reopened.contains("old")); assertTrue(reopened.contains("fresh"))
        space.setFree(64 * GiB)
        reopened.put("old2", size = 1_000)
        wall += 30 * day
        reopened.housekeep()
        assertTrue(reopened.contains("old2") && reopened.contains("fresh"))
        // Online: everything expired leaves at the next cleanup.
        reopened.serverReachable(true)
        reopened.housekeep()
        assertFalse(reopened.contains("old2")); assertFalse(reopened.contains("fresh"))
    }

    @Test fun thePlayCounterMovesATrackFromHeardOnceToHeardAgain() {
        val cache = open()
        cache.put("t")
        cache.markPlayed("t")
        assertEquals(1, cache.record("t")!!.playCount)
        assertEquals(cache.now() + 3 * day, cache.keepUntil(cache.record("t")!!))
        cache.markPlayed("t")
        assertEquals(2, cache.record("t")!!.playCount)
        assertEquals(cache.now() + 14 * day, cache.keepUntil(cache.record("t")!!))
        cache.flush()
        assertEquals(2, open().record("t")!!.playCount)
        // An early play (streamed before the file arrived) counts at commit; one more play after it makes 2.
        cache.markPlayed("early")
        cache.put("early")
        assertEquals(1, cache.record("early")!!.playCount)
        cache.markPlayed("early")
        assertEquals(2, cache.record("early")!!.playCount)
        // A re-download after eviction starts at 0.
        cache.deleteNow(null, listOf("t"))
        cache.put("t")
        assertEquals(0, cache.record("t")!!.playCount)
    }

    @Test fun onANearlyFullPhoneTheWindowNeverDeletesKeptMusicAndStopsAtAndroidsFloor() {
        val cache = open()
        cache.put("kept", size = 1_000); cache.keep("album/k", "kept")
        cache.put("loose", size = 1_000)
        space.setFree(line + 500)
        // The window may delete music that isn't kept to stay above the line, never kept music.
        assertTrue(cache.makeRoom(1_400, window = true))
        assertFalse(cache.contains("loose")); assertTrue(cache.contains("kept"))
        // Then it may dip below the line down to Android's floor; a track that doesn't fit at all is not fetched.
        space.setFree(900)
        assertTrue(cache.makeRoom(800, window = true))
        assertFalse(cache.makeRoom(1_000, window = true))
        assertFalse("Background downloads stop at the line", cache.makeRoom(800, window = false))
        assertTrue(cache.contains("kept"))
        // An Opus copy is never fetched for lack of space: the format is the network's only.
        assertEquals(RAW, CacheFormatPolicy.download(null, wifi, SongFormat("flac", 900, 10)))
        assertEquals(OPUS_128, CacheFormatPolicy.download(null, mobile, SongFormat("flac", 900, 10)))
    }

    @Test fun makeRoomNamesKeptSetsAndRemovesThemWholeOrNotAtAll() {
        val cache = open()
        cache.put("a1", size = 1_000); cache.put("a2", size = 1_000); cache.keep("album/a", "a1", "a2", title = "Glass Orchard")
        space.setFree(line + 200)
        val add = KeptItem("album/new", listOf(KeptTrack("n", SongFormat("mp3", 320, sizeBytes = 1_000))))
        val plan = cache.planAdd(add, wifi)
        assertEquals(listOf("album/a"), plan.affectedSets); assertEquals(listOf("Glass Orchard"), plan.titles)
        assertEquals(2_000, plan.evictBytes)
        cache.acquire("a2")
        assertFalse(cache.evictExactly(plan.evict, plan.affectedSets))
        assertTrue(cache.isAdded("album/a") && cache.contains("a1"))
        cache.retainLeases(emptyList())
        assertTrue(cache.evictExactly(plan.evict, plan.affectedSets))
        assertFalse(cache.isAdded("album/a")); assertTrue(cache.records().isEmpty())
        assertNull("Make room? named them: no notice", cache.removedForSpace)
    }

    // ---- Fix round 1 (review findings) ----

    @Test fun aReservedReadAheadDownloadIsWindowBytesNotAMaintenanceDeficit() {
        val cache = open()
        cache.put("k1", size = 1_000); cache.keep("album/k", "k1")
        space.setFree(line)
        cache.pin(listOf("w"))
        cache.begin(TrackKey("w", RAW))!!.use { write ->
            // The window's allowed download reserves its file below the line: 800 bytes of free space are gone.
            assertTrue(write.reserve(800))
            assertEquals(line - 800, space.freeBytes())
            assertTrue("In-flight window bytes count like committed ones", cache.preview().isEmpty)
            assertEquals(emptyList<String>(), cache.housekeep())
            assertTrue(cache.contains("k1") && cache.isAdded("album/k"))
        }
    }

    @Test fun aReplacementInFlightCountsOnTopOfTheCopyItReplaces() {
        val cache = open()
        cache.put("k1", size = 1_000); cache.keep("album/k", "k1")
        cache.put("w", OPUS_128, size = 500); cache.pin(listOf("w"))
        space.setFree(line - 500)
        assertTrue(cache.preview().isEmpty)
        cache.begin(TrackKey("w", RAW))!!.use { write ->
            assertTrue(write.reserve(800))
            assertTrue(cache.preview().isEmpty)
            assertEquals(emptyList<String>(), cache.housekeep())
            assertTrue(cache.contains("k1") && cache.isAdded("album/k"))
        }
    }

    @Test fun skippingAnOverlapUnitNeverLetsALaterUnitDeleteTheRetainedSetsFiles() {
        val cache = open()
        cache.put("x", size = 1_000); cache.put("shared", size = 1_000); cache.put("y", size = 1_000)
        cache.keep("album/a", "x", "shared"); wall += day; cache.keep("album/b", "y", "shared")
        space.setFree(line - 3_000)
        var planned = emptyList<String>()
        val left = cache.housekeepIf(policy, notice = true) { plan ->
            planned = plan.removedSets.map { it.key }
            cache.acquire("x"); true
        }
        assertEquals("The planner had both going, A first", listOf("album/a", "album/b"), planned)
        // A stays (x is leased); B must not take `shared` away from it.
        assertTrue("shared stays for album/a", cache.contains("shared") && cache.contains("x"))
        for (set in cache.addedSets) assertTrue("${set.key} is whole", set.trackIds.all { cache.contains(it) })
        assertTrue(cache.isAdded("album/a"))
        assertFalse("B left whole or stayed whole", cache.isAdded("album/b") && !cache.contains("y"))
        assertTrue(left!!.none { it == "album/a" })
    }

    @Test fun makeRoomNeverRemovesASetWhoseOtherMemberHasBecomeHeldByAnotherSet() {
        val cache = open()
        cache.put("a1", size = 1_000); cache.put("a2", size = 1_000); cache.keep("album/a", "a1", "a2")
        space.setFree(line + 200)
        val add = KeptItem("album/new", listOf(KeptTrack("n", SongFormat("mp3", 320, sizeBytes = 1_000))))
        val plan = cache.planAdd(add, wifi)
        assertEquals(listOf("album/a"), plan.affectedSets)
        // A playlist keeps a2 after the plan: removing album/a would cut the playlist.
        cache.keep("playlist/p", "a2")
        assertFalse(cache.evictExactly(plan.evict, plan.affectedSets))
        assertTrue(cache.contains("a1") && cache.contains("a2") && cache.isAdded("album/a") && cache.isAdded("playlist/p"))
    }

    @Test fun aKeptSetWithAMemberDownloadingRightNowIsNotCutEvenWhenTheMemberHasNoRecordYet() {
        val cache = open()
        cache.put("x", size = 1_000); cache.keep("album/xy", "x", "y")
        space.setFree(line - 500)
        cache.begin(TrackKey("y", RAW))!!.use {
            assertTrue(cache.preview().removedSets.isEmpty())
            assertEquals(emptyList<String>(), cache.housekeep())
            assertTrue(cache.contains("x") && cache.isAdded("album/xy"))
            assertEquals(emptyList<String>(), cache.housekeepIf(policy, notice = true) { true })
            assertFalse(cache.evictExactly(emptyList(), listOf("album/xy")))
        }
        // Once the download is over the set may go again.
        assertEquals(listOf("album/xy"), cache.housekeep())
    }

    // ---- Fix round 2 (review findings) ----

    @Test fun anInterruptedReadAheadDownloadIsStillWindowBytesAfterTheWriterClosed() {
        val cache = open()
        cache.put("k1", size = 1_000); cache.keep("album/k", "k1")
        space.setFree(line)
        cache.pin(listOf("w"))
        cache.begin(TrackKey("w", RAW))!!.use { write ->
            assertTrue(write.reserve(800)); write.output.write(ByteArray(200))   // connection lost after 200 of 800 bytes
        }
        assertTrue("The partial is kept to resume", audioParts().isNotEmpty())
        assertTrue(space.freeBytes() < line)   // the 200 received bytes (the rest of the reservation went back at close)
        assertTrue("An interrupted window download is no deficit", cache.preview().isEmpty)
        assertEquals(emptyList<String>(), cache.housekeep())
        assertTrue(cache.contains("k1") && cache.isAdded("album/k"))
        // Cancelled before the first byte: the up-front reservation is still on the phone.
        cache.pin(listOf("w", "w2"))
        cache.begin(TrackKey("w2", RAW))!!.use { assertTrue(it.reserve(900)) }
        assertTrue(space.freeBytes() < line - 900)
        assertTrue(cache.preview().isEmpty)
        assertEquals(emptyList<String>(), cache.housekeep())
        assertTrue(cache.contains("k1") && cache.isAdded("album/k"))
    }

    @Test fun anInterruptedPartialOfASongThatLeftTheWindowIsCleanedNotChargedToKeptMusic() {
        val cache = open()
        cache.put("k1", size = 1_000); cache.keep("album/k", "k1")
        space.setFree(line)
        cache.pin(listOf("w"))
        cache.begin(TrackKey("w", RAW))!!.use { assertTrue(it.reserve(800)); it.output.write(ByteArray(200)) }
        cache.pin(emptyList())   // the window moved on
        assertTrue("Freed once it is cleaned, so not a deficit", cache.preview().isEmpty)
        assertEquals(emptyList<String>(), cache.housekeep())
        assertTrue(cache.contains("k1") && cache.isAdded("album/k"))
        assertTrue("The orphan partial is gone", audioParts().isEmpty())
        assertEquals(line, space.freeBytes())
        // A partial of a song a kept set holds is that set's download: resumable, left alone.
        cache.keep("album/m", "m")
        cache.begin(TrackKey("m", RAW))!!.use { it.output.write(ByteArray(50)) }
        cache.housekeep()
        assertTrue(audioParts().isNotEmpty())
    }

    // ---- Fix round 3: a read-ahead writer that left the window before it closed ----

    /** A kept album and a read-ahead download of "w" that reserved 800 at the line; "w" leaves the window, writer still open. */
    private fun unpinnedWhileWriting(cache: TrackCache): TrackCache.Write {
        cache.put("k1", size = 1_000); cache.keep("album/k", "k1")
        space.setFree(line)
        cache.pin(listOf("w"))
        val write = cache.begin(TrackKey("w", RAW))!!
        assertTrue(write.reserve(800)); write.output.write(ByteArray(200))
        assertTrue(space.freeBytes() < line)
        cache.pin(emptyList())   // PlaybackService updates the pins at once; the download ends later
        return write
    }

    private fun assertKeptMusicUntouched(cache: TrackCache) {
        assertTrue(cache.contains("k1") && cache.isAdded("album/k"))
        assertNull(cache.removedForSpace)
    }

    @Test fun aReadAheadWriterThatLeftTheWindowBeforeItClosedIsNotADeficitForKeptMusic() {
        val cache = open()
        val write = unpinnedWhileWriting(cache)
        assertTrue("Open writer: its bytes are allowance", cache.preview().isEmpty)
        assertEquals(emptyList<String>(), cache.housekeep())
        assertKeptMusicUntouched(cache)
        write.close()   // closed, not committed: an orphan partial now
        assertTrue(cache.preview().isEmpty)
        assertEquals(emptyList<String>(), cache.housekeep())
        assertKeptMusicUntouched(cache)
        assertTrue("The orphan is reclaimed", audioParts().isEmpty())
        assertEquals(line, space.freeBytes())
    }

    @Test fun aReadAheadWriterCancelledAfterLeavingTheWindowIsReclaimedNotChargedToKeptMusic() {
        val cache = open()
        cache.put("k1", size = 1_000); cache.keep("album/k", "k1")
        space.setFree(line)
        cache.pin(listOf("w"))
        val write = cache.begin(TrackKey("w", RAW))!!
        assertTrue(write.reserve(900))   // nothing received yet
        cache.pin(emptyList())
        assertEquals(emptyList<String>(), cache.housekeep())
        write.close()   // cancelled: the whole reservation is still on the phone, and nothing holds it
        assertEquals(emptyList<String>(), cache.housekeep())
        assertKeptMusicUntouched(cache)
        assertTrue(audioParts().isEmpty())
        assertEquals(line, space.freeBytes())
    }

    @Test fun aReadAheadWriterThatFailedAfterLeavingTheWindowIsReclaimedNotChargedToKeptMusic() {
        val cache = open()
        val write = unpinnedWhileWriting(cache)
        try {
            write.use {
                // Maintenance runs between the unpin and the error handler closing the writer.
                assertEquals(emptyList<String>(), cache.housekeepIf(policy, notice = true) { true })
                assertKeptMusicUntouched(cache)
                throw java.io.IOException("connection lost")
            }
        } catch (_: java.io.IOException) {}
        assertEquals(emptyList<String>(), cache.housekeepIf(policy, notice = true) { true })
        assertKeptMusicUntouched(cache)
        assertTrue(audioParts().isEmpty())
        assertEquals(line, space.freeBytes())
    }

    @Test fun aReadAheadWriterThatCommitsAfterLeavingTheWindowBecomesRemovableMusicNotAKeptMusicDeficit() {
        val cache = open()
        val write = unpinnedWhileWriting(cache)
        assertEquals(emptyList<String>(), cache.housekeep())
        assertTrue(write.commit())
        write.close()
        assertTrue(cache.contains("w"))
        // The committed song is loose cached music below the line: it goes first, never the kept album.
        assertEquals(emptyList<String>(), cache.housekeep())
        assertKeptMusicUntouched(cache)
        assertFalse("The loose song covered the deficit", cache.contains("w"))
    }

    @Test fun anAllocationBetweenTheTwoMeasurementsIsNotADeficit() {
        var hook: (() -> Unit)? = null
        val racing = object : FreeSpace {
            override fun allocatableBytes() = space.allocatableBytes()
            override fun freeBytes(): Long { hook?.also { hook = null }?.invoke(); return space.freeBytes() }
            override fun allocate(fd: java.io.FileDescriptor, bytes: Long) = space.allocate(fd, bytes)
        }
        val cache = testTrackCache(root, racing, { it.run() }, { policy }) { wall }.also { it.serverReachable(true) }
        cache.put("k1", size = 1_000); cache.keep("album/k", "k1")
        space.setFree(line)
        cache.pin(listOf("w"))
        cache.begin(TrackKey("w", RAW))!!.use { write ->
            assertTrue(write.reserve(300))
            space.setFree(line - 300)
            // The window download reserves its next 500 bytes while maintenance is between its two measurements.
            hook = { assertTrue(write.reserve(800)) }
            assertEquals(emptyList<String>(), cache.housekeep())
            assertEquals(line - 800, space.freeBytes())
            assertTrue(cache.contains("k1") && cache.isAdded("album/k"))
        }
    }

    @Test fun aKeptSetIsNotRemovedWhenTheDeficitWasGoneByTheTimeItsTurnComes() {
        val cache = open()
        cache.put("loose", size = 1_000)
        cache.put("k1", size = 1_000); cache.keep("album/k", "k1")
        space.setFree(line - 1_500)
        val left = cache.housekeepIf(policy, notice = true) { plan ->
            assertEquals(listOf("album/k"), plan.removedSets.map { it.key })
            space.othersBytes -= 5_000; true   // another app freed space after the plan was made
        }
        assertEquals(emptyList<String>(), left)
        assertTrue(cache.contains("k1") && cache.isAdded("album/k"))
    }

    @Test fun anExpiredFileThatGetsLeasedAfterPlanningKeepsItsKeptSetWhole() {
        policy = policy.copy(kept = Lifetime.MONTH, heardOnce = Lifetime.THREE_DAYS, heardAgain = Lifetime.THREE_DAYS)
        val cache = open()
        cache.put("x", size = 1_000); cache.put("y", size = 1_000); cache.keep("album/k", "x", "y")
        wall += 39 * day; cache.markPlayed("y")
        wall += day   // x ran out (kept 40 days ago, Kept offline 1 month); y was heard yesterday
        space.setFree(line - 1_500)
        var planned: CachePlanner.Plan? = null
        val left = cache.housekeepIf(policy, notice = true) { plan ->
            planned = plan; cache.acquire("x"); true
        }
        assertEquals(listOf("x"), planned!!.expired.map { it.songId })
        assertEquals(listOf("album/k"), planned!!.removedSets.map { it.key })
        assertEquals(emptyList<String>(), left)
        assertTrue("x is leased and stays; so does the rest of its set", cache.contains("x") && cache.contains("y"))
        assertTrue(cache.isAdded("album/k"))
        assertNull(cache.removedForSpace)
    }

    // ---- Fix round 4: partial bytes never decide the removal of kept music ----

    /** A [FreeSpace] over [space] that runs [hook] on its [nth] `freeBytes` call: pins change without any lock. */
    private fun racing(nth: () -> Int, afterRead: Boolean = false, hook: () -> Unit) = object : FreeSpace {
        var calls = 0
        var armed = false
        /** The read the hook ran at (0: none yet). */
        var firedAt = 0
        /** Every free-space read since the test last reset it. */
        var reads = 0
        /** Sees every free-space value maintenance reads (after the hook ran). */
        var trace: ((Long) -> Unit)? = null
        override fun allocatableBytes() = space.allocatableBytes()
        override fun freeBytes(): Long {
            reads++
            val fire = armed && ++calls == nth()
            if (fire && !afterRead) { armed = false; firedAt = calls; hook() }
            return space.freeBytes().also { trace?.invoke(it); if (fire && afterRead) { armed = false; firedAt = calls; hook() } }
        }
        override fun allocate(fd: java.io.FileDescriptor, bytes: Long) = space.allocate(fd, bytes)
    }

    @Test fun aPinnedPartialUnpinnedAfterOrphanCleanupAndAnIndependentDeficitThatClearedIsNotAKeptMusicDeficit() {
        lateinit var cache: TrackCache
        val phone = racing({ 1 }) { cache.pin(emptyList()); space.othersBytes -= 500 }
        cache = testTrackCache(root, phone, { it.run() }, { policy }) { wall }.also { it.serverReachable(true) }
        cache.put("k1", size = 1_000); cache.keep("album/k", "k1")
        space.setFree(line)
        cache.pin(listOf("w"))
        cache.begin(TrackKey("w", RAW))!!.use { assertTrue(it.reserve(800)); it.output.write(ByteArray(200)) }   // interrupted: a closed partial
        space.othersBytes += 500   // another app fills the phone: an independent deficit
        val left = cache.housekeepIf(policy, notice = true) { plan ->
            assertEquals("planned while the deficit is real", listOf("album/k"), plan.removedSets.map { it.key })
            // After the orphan cleanup skipped the still pinned partial, and before apply measures again: the other app
            // frees its space and playback unpins the song.
            phone.armed = true; true
        }
        assertEquals(emptyList<String>(), left)
        assertKeptMusicUntouched(cache)
        assertEquals("The partial is an orphan now and goes with the next pass", emptyList<String>(), cache.housekeep())
        assertTrue(audioParts().isEmpty())
        assertKeptMusicUntouched(cache)
    }

    // ---- Fix round 5: a writer epoch instead of comparing samples ----

    /** The finding's interleaving: a resumed partial is read, then truncated to zero, then regrown bigger, around the measurement. */
    @Test fun aPartialThatShrinksAndRegrowsAroundTheMeasurementIsNotADeficitForKeptMusic() {
        lateinit var write: TrackCache.Write
        var armed = false
        var reads = 0
        val hooked = object : FreeSpace {
            override fun allocatableBytes() = space.allocatableBytes()
            override fun allocate(fd: java.io.FileDescriptor, bytes: Long) = space.allocate(fd, bytes)
            override fun freeBytes(): Long {
                if (!armed) return space.freeBytes()
                // A sample-comparing measure reads twice: the truncate lands after the first sample (before the partial
                // files are measured), the regrow before the second one.
                if (++reads == 2) write.reserve(800)
                return space.freeBytes().also { if (reads == 1) write.restart() }
            }
        }
        val cache = testTrackCache(root, hooked, { it.run() }, { policy }) { wall }.also { it.serverReachable(true) }
        cache.put("k1", size = 1_000); cache.keep("album/k", "k1")
        space.setFree(line)
        cache.pin(listOf("w"))
        write = cache.begin(TrackKey("w", RAW))!!
        assertTrue(write.reserve(200)); write.output.write(ByteArray(200))   // a resumed partial of 200
        space.setFree(line - 200)   // truth: no deficit at all
        space.othersBytes += 500    // another app fills the phone: the independent deficit that plans the kept album
        val left = cache.housekeepIf(policy, notice = true) { plan ->
            assertEquals(listOf("album/k"), plan.removedSets.map { it.key })
            space.othersBytes -= 500   // the independent deficit clears after planning
            armed = true; true
        }
        write.close()
        assertEquals(emptyList<String>(), left)
        assertKeptMusicUntouched(cache)
    }

    /** A writer change between the measurement and the removal withdraws the decision; the next pass decides again. */
    @Test fun aWriterChangeBetweenTheMeasurementAndTheRemovalWithdrawsTheDecision() {
        val cache = open()
        cache.put("k1", size = 1_000); cache.keep("album/k", "k1")
        val write = cache.begin(TrackKey("w", RAW))!!
        space.setFree(line - 500)   // a real deficit, nothing in flight but an empty writer
        cache.crashPoint = { if (it == "housekeep.measured") write.output.write(ByteArray(10)) }
        assertEquals(emptyList<String>(), cache.housekeep())
        assertKeptMusicUntouched(cache)
        cache.crashPoint = {}
        space.setFree(line - 500)
        write.close()
        // The orphan partial's reclaim is an own delete: this pass reclaims it and holds the album one tick.
        assertEquals(emptyList<String>(), cache.housekeep())
        wall += 60_000
        assertEquals("quiet now: the real deficit removes the album", listOf("album/k"), cache.housekeep())
    }

    /** A writer change that is still running when maintenance starts to measure is an unstable measurement too. */
    @Test fun aWriterChangeRunningDuringTheMeasurementSkipsKeptRemovalButNotTheNextPass() {
        lateinit var cache: TrackCache
        lateinit var write: TrackCache.Write
        val phone = racing({ 2 }, afterRead = true) { write.restart() }
        cache = testTrackCache(root, phone, { it.run() }, { policy }) { wall }.also { it.serverReachable(true) }
        cache.put("k1", size = 1_000); cache.keep("album/k", "k1")
        write = cache.begin(TrackKey("w", RAW))!!
        space.setFree(line - 500)
        phone.armed = true
        assertEquals(emptyList<String>(), cache.housekeep())
        assertKeptMusicUntouched(cache)
        phone.armed = false
        assertEquals(listOf("album/k"), cache.housekeep())
        write.close()
    }

    // ---- Fix round 6: the window is the union of everything pinned or written during the whole pass ----

    /**
     * The finding's interleaving: a loose, committed read-ahead song is pinned before planning measures the window, so
     * the plan subtracts its allowance and selects the kept album for the remaining deficit; the independent deficit
     * clears and playback unpins the song before apply. Apply's window must still contain the song (it is pinned or
     * written at some time during the pass), otherwise its bytes read as a deficit and the album goes while the
     * removable loose song stays.
     */
    @Test fun aSongPinnedDuringPlanningAndUnpinnedBeforeApplyKeepsItsAllowanceToTheEndOfThePass() {
        lateinit var cache: TrackCache
        val phone = racing({ 1 }) { cache.pin(listOf("loose")) }   // playback pins the song as planning starts to measure
        cache = testTrackCache(root, phone, { it.run() }, { policy }) { wall }.also { it.serverReachable(true) }
        cache.put("loose", size = 800)
        cache.put("k1", size = 1_000); cache.keep("album/k", "k1")
        space.setFree(line - 1_300)   // the independent 500 deficit plus the song's 800
        phone.armed = true
        val left = cache.housekeepIf(policy, notice = true) { plan ->
            assertEquals("planned: the song is in the window, the deficit is 500", listOf("album/k"), plan.removedSets.map { it.key })
            space.othersBytes -= 500   // the independent deficit clears
            cache.pin(emptyList())     // playback moves on
            true
        }
        assertEquals(emptyList<String>(), left)
        assertKeptMusicUntouched(cache)
        assertTrue("the loose song was never the deficit", cache.contains("loose"))
    }

    // ---- Fix round 7: the window's kept-set writes, doomed files, and settings changes while a download streams ----

    /** Two kept albums (K1 has one member, K2 1000 bytes), no loose music, the phone exactly at the line. */
    private fun twoKeptAlbums(cache: TrackCache) {
        cache.put("k1a", size = 1_000); cache.keep("album/k1", "k1a", "k1b")
        cache.put("k2", size = 1_000); cache.keep("album/k2", "k2")
        space.setFree(line)
    }

    /**
     * Round 8: the window writes a kept album's missing member only with room above the line (paid for by unkept music);
     * otherwise the song streams, the member stays missing, and no kept album is ever deleted in that flow.
     */
    @Test fun aKeptMemberTheWindowCannotPayForAboveTheLineStreamsAndNoKeptAlbumIsEverDeleted() {
        val cache = open()
        twoKeptAlbums(cache)
        cache.pin(listOf("k1a", "k1b"))   // the user plays K1
        val protect = setOf("k1a", "k1b")
        assertFalse("no room above the line and only kept music to pay: it streams",
            cache.makeRoom(200, protect, window = true, songId = "k1b"))
        assertFalse(cache.contains("k1b"))
        assertTrue(space.freeBytes() >= line)
        // The window moves on: nothing below the line was ever written, so no album is short.
        cache.pin(emptyList())
        assertTrue(cache.preview().isEmpty)
        assertEquals(emptyList<String>(), cache.housekeep())
        assertTrue(cache.contains("k2") && cache.isAdded("album/k2") && cache.isAdded("album/k1"))
        assertNull(cache.removedForSpace)
    }

    @Test fun aKeptMemberTheWindowCanPayForWithUnkeptMusicIsWrittenAboveTheLine() {
        val cache = open()
        twoKeptAlbums(cache)
        cache.put("loose", size = 500)
        space.setFree(line)
        assertTrue(cache.makeRoom(200, setOf("k1b"), window = true, songId = "k1b"))
        assertFalse("the loose music paid", cache.contains("loose"))
        assertTrue(space.freeBytes() - 200 >= line)
        assertTrue(cache.contains("k2") && cache.isAdded("album/k2"))
    }

    /** Music no kept set holds may still be read ahead below the line, down to Android's own floor. */
    @Test fun unkeptMusicIsStillReadAheadBelowTheLine() {
        val cache = open()
        twoKeptAlbums(cache)
        cache.pin(listOf("loose"))
        assertTrue(cache.makeRoom(200, setOf("loose"), window = true, songId = "loose"))
        assertTrue(cache.put("loose", size = 200))
        assertTrue(space.freeBytes() < line)
        space.setFree(line - 200)   // the index file is not part of the arithmetic here
        assertTrue("allowance, no deficit", cache.preview().isEmpty)
        assertTrue(cache.isAdded("album/k1") && cache.isAdded("album/k2"))
    }

    /** The Wi-Fi replacement of a kept track (Opus to original) by the window is not written below the line either. */
    @Test fun anOpusToOriginalReplacementOfAKeptTrackIsNotWrittenBelowTheLineAndNoKeptAlbumIsDeleted() {
        val cache = open()
        cache.put("k1a", OPUS_128, size = 300, type = "audio/ogg"); cache.keep("album/k1", "k1a")
        cache.put("k2", size = 1_000); cache.keep("album/k2", "k2")
        space.setFree(line)
        cache.pin(listOf("k1a"))
        assertFalse(cache.makeRoom(900, setOf("k1a"), window = true, songId = "k1a"))
        assertEquals(OPUS_128, cache.kind("k1a"))   // the Opus copy plays on
        cache.pin(emptyList())
        assertEquals(emptyList<String>(), cache.housekeep())
        assertTrue(cache.contains("k2") && cache.isAdded("album/k2") && cache.isAdded("album/k1"))
        assertNull(cache.removedForSpace)
    }

    /** Playing through a kept album with the phone at the line over many window steps never deletes another kept album. */
    @Test fun aWindowMovingThroughAKeptAlbumAtTheLineNeverDeletesAnotherKeptAlbum() {
        val cache = open()
        val tracks = (1..8).map { "k1t$it" }
        cache.keep("album/k1", *tracks.toTypedArray())
        cache.put("k2", size = 1_000); cache.keep("album/k2", "k2")
        space.setFree(line)
        for (index in tracks.indices) {
            val window = tracks.subList(maxOf(0, index - 1), minOf(tracks.size, index + 5))
            cache.pin(window)
            window.filterNot { cache.contains(it) }.forEach { id ->
                if (cache.makeRoom(200, window.toSet(), window = true, songId = id)) cache.put(id, size = 200)
            }
            assertEquals("step $index", emptyList<String>(), cache.housekeep())
            wall += 60_000
            assertTrue("step $index", cache.isAdded("album/k2") && cache.contains("k2"))
        }
    }

    /** A leased file that lost its record stays on disk until released: its bytes are allowance, not a deficit. */
    @Test fun aDeletedWhileHeldPlayingFileIsAllowanceNotADeficitForKeptMusic() {
        val cache = open()
        twoKeptAlbums(cache)
        cache.put("playing", size = 800); cache.pin(listOf("playing"))
        val held = cache.acquire("playing")!!
        assertEquals(1, cache.deleteNow(null, listOf("playing")))   // "Delete from this phone" on the playing track
        space.setFree(line - 100)   // a little below the line; the held file's 800 bytes are still on the phone
        assertTrue("the bytes are still on disk", held.exists())
        assertTrue(cache.preview().isEmpty)
        assertEquals(emptyList<String>(), cache.housekeep())
        assertTrue(cache.contains("k2") && cache.isAdded("album/k2") && cache.isAdded("album/k1"))
        cache.retainLeases(emptyList())
        assertFalse(held.exists())
    }

    @Test fun anOpusCopyReplacedWhileThePlayerHoldsItIsAllowanceNotADeficitForKeptMusic() {
        val cache = open()
        twoKeptAlbums(cache)
        cache.put("o", OPUS_128, size = 300, type = "audio/ogg")
        val held = cache.acquire("o")!!
        cache.pin(listOf("o"))
        space.setFree(line)
        cache.put("o", RAW, size = 900)   // the Opus copy is doomed, the original is the window's
        assertTrue(held.exists())
        space.setFree(line - 1_000)   // the window's 900 and the doomed 300 cover it; the window alone does not
        assertEquals(emptyList<String>(), cache.housekeep())
        assertTrue(cache.contains("k2") && cache.isAdded("album/k2"))
        cache.retainLeases(emptyList())
    }

    /** A phone whose every free-space read lands a write of a streaming download: no single measurement is coherent. */
    private fun streaming(write: () -> TrackCache.Write) = object : FreeSpace {
        var streaming = true
        override fun allocatableBytes() = space.allocatableBytes()
        override fun allocate(fd: java.io.FileDescriptor, bytes: Long) = space.allocate(fd, bytes)
        override fun freeBytes(): Long = space.freeBytes().also { if (streaming) write().output.write(ByteArray(1)) }
    }

    /**
     * Raising Keep free space while a download streams: the measurement is never coherent, yet the change must not be
     * decided as "nothing to delete": the plan names the kept album (upper bound), so the sheet shows and nothing is
     * saved without it. Confirming then saves it, and kept music is not removed on a measurement that is not coherent.
     */
    @Test fun aLineRaisedWhileADownloadStreamsNeverPlansNothingSoItIsNeverSavedWithoutTheSheet() {
        lateinit var write: TrackCache.Write
        val phone = streaming { write }
        val cache = testTrackCache(root, phone, { it.run() }, { policy }) { wall }.also { it.serverReachable(true) }
        cache.put("k1", size = 1_000); cache.keep("album/k", "k1")
        write = cache.begin(TrackKey("w", RAW))!!
        space.setFree(line + 500)
        val raised = policy.copy(keepFreeBytes = line + 2_000)
        val shown = cache.preview(raised)
        assertEquals("the sheet names the kept album", listOf("album/k"), shown.removedSets.map { it.key })
        // The silent path of the UI (applyPolicy with nothing shown): the accept callback must see a plan that is not empty.
        var saved = false
        cache.housekeepIf(raised, notice = false) { plan -> assertFalse(plan.isEmpty); saved = false; false }
        assertFalse(saved)
        // Confirm: saved (the sheet named it), but the album is not removed on a number no writer-free measurement backs.
        assertEquals(emptyList<String>(), cache.housekeepIf(raised, notice = false, shown = shown) { true })
        assertTrue(cache.contains("k1") && cache.isAdded("album/k"))
        assertNull(cache.removedForSpace)
        phone.streaming = false; write.close()
        // Quiet again: under the confirmed setting the next pass removes it, without the automatic notice: the user confirmed it.
        policy = raised
        // The orphan partial's reclaim is an own delete: this pass reclaims it and holds the album one tick.
        assertEquals(emptyList<String>(), cache.housekeep())
        wall += 60_000
        assertEquals(listOf("album/k"), cache.housekeep())
        assertNull("a set a confirmed plan named gets no removal notice", cache.removedForSpace)
    }

    /** Without the sheet's confirmation the same removal does get the notice (control for the test above). */
    @Test fun aKeptSetRemovedByAnUnconfirmedPassStillGetsTheNotice() {
        val cache = open()
        cache.put("k1", size = 1_000); cache.keep("album/k", "k1")
        space.setFree(line - 500)
        assertEquals(listOf("album/k"), cache.housekeep())
        assertNotNull(cache.removedForSpace)
    }

    /**
     * Confirm applies the plan that was shown, never more: while a download streams the re-plan uses the upper bound
     * (line minus free space), which is larger than the exact need the sheet showed; the extra loose music stays.
     */
    @Test fun confirmWhileADownloadStreamsDeletesNoMoreThanTheShownPlan() {
        lateinit var write: TrackCache.Write
        val phone = streaming { write }
        phone.streaming = false
        val cache = testTrackCache(root, phone, { it.run() }, { policy }) { wall }.also { it.serverReachable(true) }
        cache.put("l1", size = 500); wall += day; cache.put("l2", size = 500)
        cache.put("w", size = 600); cache.pin(listOf("w"))
        write = cache.begin(TrackKey("x", RAW))!!
        space.setFree(line + 200)
        val raised = policy.copy(keepFreeBytes = line + 1_000)
        val shown = cache.preview(raised)   // exact: 800 short minus the window's 600 = 200: one loose song
        assertEquals(listOf("l1"), shown.notKept.map { it.songId })
        phone.streaming = true   // now no measurement is coherent: the re-plan needs the full 800
        assertTrue("the re-plan is larger", cache.preview(raised).notKept.size == 2)
        assertEquals(emptyList<String>(), cache.housekeepIf(raised, notice = false, shown = shown) { true })
        assertFalse(cache.contains("l1"))
        assertTrue("not in the confirmed plan", cache.contains("l2"))
        phone.streaming = false; write.close()
    }

    /** Round 8: after Coda's own delete, kept music waits one tick (60 s); unkept music is still removed at once. */
    @Test fun keptMusicWaitsOneTickAfterCodasOwnDeleteButUnkeptMusicDoesNot() {
        val cache = open()
        cache.put("k1", size = 1_000); cache.keep("album/k", "k1")
        cache.put("loose", size = 300)
        space.setFree(line - 500)
        assertEquals("the loose music goes, the album waits", emptyList<String>(), cache.housekeep())
        assertFalse(cache.contains("loose")); assertTrue(cache.isAdded("album/k"))
        wall += 59_999
        assertEquals(emptyList<String>(), cache.housekeep())
        assertTrue(cache.isAdded("album/k"))
        wall += 1
        assertEquals(listOf("album/k"), cache.housekeep())
        assertNotNull(cache.removedForSpace)
    }

    /**
     * Artwork is never evicted; songs give way. Covers stay when their kept album leaves for space,
     * so they free nothing and only audio pays the deficit: 1100 short needs both albums' 1000 audio bytes.
     */
    @Test fun keptAlbumsLeaveForSpaceButTheirCoversStayAndCountForNothing() {
        val cache = open()
        cache.put("a1", size = 1_000); cache.renew(KeptItem("album/a", listOf(KeptTrack("a1")), "A", coverArt = "al-a"))
        cache.put("b1", size = 1_000); cache.renew(KeptItem("album/b", listOf(KeptTrack("b1")), "B", coverArt = "al-b"))
        assertTrue(cache.putCover("al-a", 1440, ByteArray(200))); assertTrue(cache.putCover("al-b", 1440, ByteArray(200)))
        space.setFree(line - 1_100)
        val left = cache.housekeep()
        assertEquals("both kept albums leave", 2, left.size)
        assertTrue(cache.covers.has("al-a", 1440) && cache.covers.has("al-b", 1440))
        assertEquals(400L, cache.sizeBytes())
    }

    @Test fun aUserDeleteAlsoHoldsKeptMusicForATick() {
        val cache = open()
        cache.put("k1", size = 1_000); cache.keep("album/k", "k1")
        cache.put("gone", size = 100)
        assertEquals(1, cache.deleteNow(null, listOf("gone")))
        space.setFree(line - 500)
        assertEquals(emptyList<String>(), cache.housekeep())
        wall += 60_000
        assertEquals(listOf("album/k"), cache.housekeep())
    }

    /** Reclaiming an orphan partial is Coda's own delete too: kept music waits the 60 s tick after it. */
    @Test fun anOrphanPartReclaimHoldsKeptMusicForATick() {
        val cache = open()
        val write = unpinnedWhileWriting(cache)
        write.close()
        assertEquals(emptyList<String>(), cache.housekeep())
        assertTrue("The orphan is reclaimed", audioParts().isEmpty())
        space.setFree(line - 500)
        assertEquals("held after the orphan delete", emptyList<String>(), cache.housekeep())
        assertTrue(cache.isAdded("album/k"))
        wall += 60_000
        assertEquals(listOf("album/k"), cache.housekeep())
    }

    /** Round 1: an orphan part with no bytes frees nothing, so reclaiming it does not renew the hold on kept music. */
    @Test fun anEmptyOrphanPartDoesNotHoldKeptMusic() {
        val cache = open()
        cache.put("k1", size = 1_000); cache.keep("album/k", "k1")
        space.setFree(line)
        cache.pin(listOf("w"))
        val write = cache.begin(TrackKey("w", RAW))!!
        cache.pin(emptyList())
        write.close()
        assertEquals(emptyList<String>(), cache.housekeep())
        assertTrue("The orphan is reclaimed", audioParts().isEmpty())
        space.setFree(line - 500)
        assertEquals("not held: the delete freed nothing", listOf("album/k"), cache.housekeep())
    }

    /** The settling loop holds the cache lock for a bounded time, not 25 reads of a slow free-space call. */
    @Test fun aSettingsChangeStopsRetryingAfterItsTimeBudget() {
        lateinit var write: TrackCache.Write
        var reads = 0
        val slow = object : FreeSpace {
            override fun allocatableBytes() = space.allocatableBytes()
            override fun allocate(fd: java.io.FileDescriptor, bytes: Long) = space.allocate(fd, bytes)
            override fun freeBytes(): Long = space.freeBytes().also { reads++; Thread.sleep(40); write.output.write(ByteArray(1)) }
        }
        val cache = testTrackCache(root, slow, { it.run() }, { policy }) { wall }.also { it.serverReachable(true) }
        write = cache.begin(TrackKey("w", RAW))!!
        space.setFree(line + 500)
        cache.preview(policy.copy(keepFreeBytes = line + 2_000))
        assertTrue("reads=$reads", reads in 2..8)
        write.close()
    }

    /** A measurement that only overlapped a write once is retried for a settings change: exact, not the upper bound. */
    @Test fun aSettingsChangeRetriesAMeasurementThatOverlappedAWriteAndUsesTheExactNeed() {
        lateinit var cache: TrackCache
        lateinit var write: TrackCache.Write
        val phone = racing({ 1 }) { write.output.write(ByteArray(1)) }
        cache = testTrackCache(root, phone, { it.run() }, { policy }) { wall }.also { it.serverReachable(true) }
        cache.put("k1", size = 1_000); cache.keep("album/k", "k1")
        cache.put("window", size = 800); cache.pin(listOf("window"))
        write = cache.begin(TrackKey("w", RAW))!!
        space.setFree(line + 500)
        phone.armed = true
        // Need under the raised line: 2000 - 500 = 1500, minus the window's 800 = 700 -> the album goes (1000 bytes).
        val raisedSmall = policy.copy(keepFreeBytes = line + 1_000)   // exact need 500 - 800 < 0: nothing; upper bound 500: the album
        assertTrue("exact after the retry: the window covers it", cache.preview(raisedSmall).isEmpty)
        assertTrue("it did retry", phone.reads >= 2)
        write.close()
    }

    /**
     * Random pin / unpin / write / close / commit / external-space interleavings, with maintenance passes in which pins
     * and reservations change between the measurements. Writer changes (reserve, truncate and regrow, writes) land inside the measurements too. A kept album is never removed on a measurement a writer change overlapped, and never unless the committed music
     * alone (free space plus partials as the phone has them, minus the window's committed copies) is short of the line
     * by more than all removable loose music can cover.
     */
    @Test fun keptMusicIsNeverRemovedForPartialBytesInAnyInterleaving() {
        var forced = 0
        var passes = 0
        for (seed in 1..150) {
            val random = kotlin.random.Random(seed)
            val ids = (0 until 6).map { "s$it" }
            val writers = HashMap<String, TrackCache.Write>()
            var cache: TrackCache? = null
            var hookPins = emptyList<String>()
            var pinned = emptyList<String>()
            var hookReserve = 0L
            var hookFrees = 0L
            var hookOp = 0
            var hookWrote = false
            val phone = racing({ 1 + random.nextInt(3) }, afterRead = random.nextBoolean()) {
                cache!!.pin(hookPins); pinned = hookPins
                writers.values.firstOrNull()?.let { w ->
                    when (hookOp) {
                        1 -> { w.reserve(hookReserve + 1); hookWrote = true }
                        2 -> { w.restart(); w.reserve(hookReserve); hookWrote = true }   // truncate, regrow
                        3 -> { w.output.write(ByteArray(hookReserve.toInt())); hookWrote = true }
                        4 -> { w.restart(); hookWrote = true }   // shrink only
                    }
                }
                space.othersBytes -= hookFrees   // another app frees space
            }
            cache = testTrackCache(root, phone, { it.run() }, { policy }) { wall }.also { it.serverReachable(true) }
            val c = cache
            fun seedKept() { c.put("k1", size = 1_000); c.put("k2", size = 1_000); c.keep("album/k", "k1", "k2") }
            seedKept()
            space.setFree(line + 2_000)
            repeat(120) {
                when (random.nextInt(7)) {
                    0 -> ids.random(random).takeIf { it !in writers && !c.contains(it) }?.let { c.put(it, size = 100 + random.nextInt(1_400)) }
                    1 -> ids.filter { random.nextBoolean() }.let { pinned = it; c.pin(it) }
                    2 -> ids.random(random).takeIf { it !in writers && !c.contains(it) }?.let { id ->
                        c.begin(TrackKey(id, RAW))?.let { write ->
                            writers[id] = write
                            if (random.nextBoolean()) write.reserve(200L + random.nextInt(1_200))
                            write.output.write(ByteArray(random.nextInt(300)))
                        }
                    }
                    3 -> writers.keys.toList().randomOrNull(random)?.let { writers.remove(it)!!.close() }
                    4 -> writers.keys.toList().randomOrNull(random)?.let { id -> writers.remove(id)!!.use { w -> w.output.write(ByteArray(0)); w.commit() } }
                    5 -> space.othersBytes += random.nextInt(2_001) - 1_000
                    else -> {
                        space.setFree(line + random.nextInt(-1_500, 1_500))   // a phone that hovers around the line
                        val flight = (pinned + writers.keys).toSet()
                        val kept = setOf("k1", "k2")
                        val window = c.records().filter { it.songId in flight && it.songId !in kept }.sumOf { it.bytes }
                        val loose = c.records().filter { it.songId !in flight && it.songId !in kept }.sumOf { it.bytes }
                        val parts = root.listFiles()!!.filter { it.name.contains(".part") }.sumOf { it.length() }
                        val need = line - space.freeBytes() - parts - window
                        hookPins = ids.filter { random.nextBoolean() }; hookReserve = random.nextInt(400).toLong()
                        hookFrees = if (random.nextBoolean()) random.nextInt(1_500).toLong() else 0L
                        hookOp = random.nextInt(5); hookWrote = false; phone.firedAt = 0; phone.reads = 0
                        // What the committed music alone says at each measurement: the line minus free space minus every
                        // partial byte minus the committed copies of what was or is in flight, as the files are right then.
                        val base = line - space.freeBytes() - parts
                        val records = c.records()   // committed copies only change by this pass's own removals
                        val acceptPins = if (random.nextBoolean()) emptyList() else ids.filter { random.nextBoolean() }
                        val acceptFrees = if (random.nextBoolean()) random.nextInt(1_500).toLong() else 0L
                        // Everything pinned or written at any time of the pass: the start, the hook's pin, the plan-to-apply change.
                        val union = mutableSetOf<String>().also { it += flight }
                        val seen = flight
                        val needs = mutableListOf<Long>()
                        phone.trace = { free ->
                            val inFlight = seen + pinned + writers.keys
                            val committed = c.records().filter { it.songId in inFlight && it.songId !in kept }.sumOf { it.bytes }
                            val partial = root.listFiles()!!.filter { it.name.contains(".part") }.sumOf { it.length() }
                            needs += line - free - partial - committed
                        }
                        phone.calls = 0; phone.armed = true
                        var readsAtPlan = 0
                        c.housekeepIf(policy, notice = true) { readsAtPlan = phone.reads; c.pin(acceptPins); pinned = acceptPins; space.othersBytes -= acceptFrees; true }
                        phone.armed = false; phone.trace = null
                        passes++
                        if (phone.firedAt > 0) union += hookPins
                        union += acceptPins
                        // An upper bound of the real need: free space the pass itself gives back (the index file shrinking, hook frees after the read) is left out.
                        val looseAll = records.filter { it.songId !in union && it.songId !in kept }.sumOf { it.bytes }
                        val needAll = base - acceptFrees - records.filter { it.songId in union && it.songId !in kept }.sumOf { it.bytes }
                        assertTrue("seed $seed: kept album removed with need $needAll <= loose $looseAll over the whole pass; flight=$flight union=$union hookPins=$hookPins acceptPins=$acceptPins needs=$needs",
                            needAll > looseAll || (c.isAdded("album/k") && c.contains("k1") && c.contains("k2")))
                        if (need <= loose) assertTrue("seed $seed: kept album removed with need $need <= loose $loose",
                            c.isAdded("album/k") && c.contains("k1") && c.contains("k2"))
                        if (!c.isAdded("album/k")) {
                            forced++
                            // Kept music goes only on a measurement no writer change overlapped (the reads after the plan's are the final measurement's).
                            assertFalse("seed $seed: kept album removed though a writer changed during the final measurement",
                                hookWrote && phone.firedAt > readsAtPlan)
                            assertTrue("seed $seed: kept album removed though the last measurements were $needs",
                                needs.size >= 2 && needs.takeLast(2).all { it > 0 })
                            seedKept()
                        }
                    }
                }
            }
            writers.values.forEach { it.close() }
            root.listFiles()!!.forEach { it.delete() }
        }
        assertTrue("$passes passes, $forced forced", passes > 500 && forced > 5)
    }

    // ---- Rebase onto BAT-1b: a resumable partial survives a restart and maintenance while the phone is above the line ----

    /** An interrupted Opus read-ahead copy of "w" (500 confirmed bytes, marker written), the cache then closed. */
    private fun interruptedOpusPartial(): TrackCache {
        val first = open()
        first.pin(listOf("w"))
        first.begin(TrackKey("w", OPUS_128))!!.use { it.output.write(ByteArray(500)); it.checkpoint() }
        first.pin(emptyList())
        return first
    }

    @Test fun aResumableOpusPartialOfNoOwnerSurvivesARestartAndMaintenanceAboveTheLine() {
        interruptedOpusPartial()
        val cache = open()                       // The restart keeps the .part and its marker
        space.setFree(line + 5_000)
        assertEquals(emptyList<String>(), cache.housekeep())
        assertEquals(2, root.listFiles()!!.count { it.name.contains(".opus.part") })
        cache.pin(listOf("w"))
        cache.begin(TrackKey("w", OPUS_128))!!.use { assertEquals("The resume point is kept", 500L, it.resumeFrom) }
    }

    @Test fun aResumableOpusPartialOfNoOwnerIsReclaimedWhenThePhoneIsShortOfTheLineAndNeverCountsAsAKeptDeficit() {
        val cache = interruptedOpusPartial().let { open() }
        cache.put("k1", size = 1_000); cache.keep("album/k", "k1")
        space.setFree(line - 100)                // short of the line: unkept partials are reclaimed, the resume restarts from zero
        assertEquals("The partial is not a kept-music deficit", emptyList<String>(), cache.housekeep())
        assertTrue(cache.contains("k1") && cache.isAdded("album/k"))
        assertTrue("Reclaimed with its marker", root.listFiles()!!.none { it.name.contains(".part") })
        cache.begin(TrackKey("w", OPUS_128))!!.use { assertEquals(0L, it.resumeFrom) }
    }

    @Test fun aPartialWithoutAConfirmedLengthIsAnOrphanAtOnceEvenAboveTheLine() {
        val cache = open()
        cache.pin(listOf("w"))
        cache.begin(TrackKey("w", OPUS_128))!!.use { assertTrue(it.reserve(800)) }   // reserved, nothing confirmed
        cache.pin(emptyList())
        space.setFree(line + 5_000)
        cache.housekeep()
        assertTrue(root.listFiles()!!.none { it.name.contains(".part") })
    }

    private fun audioParts() = root.listFiles()!!.filter { it.name.endsWith(".part") }
}
