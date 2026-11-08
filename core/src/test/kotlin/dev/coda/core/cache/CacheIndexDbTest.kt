package dev.coda.core.cache

import dev.coda.core.playback.StreamFormat.OPUS_128
import dev.coda.core.playback.StreamFormat.RAW
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.coda.core.testing.TestCacheIndex
import dev.coda.store.CacheSetRow
import dev.coda.store.CacheStateRow
import dev.coda.store.CacheTrackRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

/** The index in library.db when it can't be read, can't be written, was lost, or holds bad rows. */
class CacheIndexDbTest : CacheFixture() {
    private class Hooked(val inner: CacheIndexStore) : CacheIndexStore {
        var failLoad = false
        var failSave = false
        var lostOnce = false
        var unclaimed = false
        val bases = mutableListOf<CacheSnapshot?>()
        override fun load(writer: String) = if (failLoad) throw IllegalStateException("A migration from 1 to 2 was required")
            else inner.load(writer)?.let { if (unclaimed) it.copy(claimed = false) else it }
        override fun save(writer: String, previous: CacheSnapshot?, next: CacheSnapshot) {
            if (failSave) throw IOException("disk full")
            bases += previous
            inner.save(writer, previous, next)
        }
        override fun lost() = lostOnce.also { lostOnce = false }
        override val waveforms get() = inner.waveforms
    }

    private fun hooked(): Hooked = Hooked(TestCacheIndex.at(root))
    private fun open(index: CacheIndexStore, logs: MutableList<String> = mutableListOf()) =
        TrackCache(root, index, space, { it.run() }, { policy }, logs::add) { wall }.also { it.serverReachable(true) }

    @Test fun anIndexThatCannotBeReadNowTouchesNoFileAndDoesNotCrash() {
        open().apply { put("a"); renew(KeptItem("album/x", listOf(KeptTrack("a"), KeptTrack("b")))) }
        File(root, TrackKey("loose", RAW).fileName).writeBytes(flac)
        File(root, "junk.tmp").writeText("x")
        val before = root.walkTopDown().onEnter { it.name != ".index" }.filter { it.isFile }.map { it.relativeTo(root).path to it.length() }.toSet()
        val index = hooked().apply { failLoad = true }
        val logs = mutableListOf<String>()
        val cache = open(index, logs)
        assertTrue(cache.indexUnavailable)
        assertEquals(1, logs.size)
        assertNull("no new downloads", cache.begin(TrackKey("c", RAW)))
        assertEquals(emptyList<String>(), cache.housekeep())
        cache.flush()
        assertEquals("not one file moved or deleted", before,
            root.walkTopDown().onEnter { it.name != ".index" }.filter { it.isFile }.map { it.relativeTo(root).path to it.length() }.toSet())
        assertTrue("nothing written", index.bases.isEmpty())
        // The next session that can read it has everything.
        val again = open()
        assertTrue(again.contains("a") && again.isAdded("album/x"))
    }

    /** Library.db fails Room's identity check. The cache stays inert: no quarantine, no file touched. */
    @Test fun anIdentityMismatchQuarantinesNothingAndTouchesNoFile() {
        open().apply { put("a"); renew(KeptItem("album/x", listOf(KeptTrack("a")))) }
        File(root, TrackKey("loose", RAW).fileName).writeBytes(flac)
        File(root, "waveforms").apply { mkdirs(); resolve("abc.waveform").writeBytes(ByteArray(8)) }
        val db = TestCacheIndex.file(root)
        TestCacheIndex.store(root).close()
        val hash = BundledSQLiteDriver().open(db.path).let { c ->
            val h = c.prepare("SELECT identity_hash FROM room_master_table").use { it.step(); it.getText(0) }
            c.execSQL("UPDATE room_master_table SET identity_hash = 'not-this-schema'")
            c.close(); h
        }
        // SQLite's own -wal/-shm files may appear when the file is opened; everything else stays as it is.
        val files = { root.walkTopDown().filter { it.isFile && !it.name.endsWith("-wal") && !it.name.endsWith("-shm") }
            .map { it.relativeTo(root).path to it.length() }.toSet() }
        val before = files()
        val logs = mutableListOf<String>()
        val cache = open(TestCacheIndex.at(root), logs)
        assertTrue(cache.indexUnavailable)
        assertNull("no new downloads", cache.begin(TrackKey("c", RAW)))
        assertEquals(emptyList<String>(), cache.housekeep())
        cache.flush()
        assertEquals(0, TestCacheIndex.store(root).rebuilds)
        assertEquals("not one file moved, deleted or set aside", before, files())
        // A build with the matching schema (here: the hash put back) has everything.
        TestCacheIndex.store(root).close()
        BundledSQLiteDriver().open(db.path).let { c -> c.execSQL("UPDATE room_master_table SET identity_hash = '$hash'"); c.close() }
        val again = open()
        assertTrue(again.contains("a") && again.isAdded("album/x"))
    }

    @Test fun aRowLostWithTheLastCommitKeepsItsFileAsLegacy() {
        // Rex (power loss): synchronous=NORMAL may lose the commit while the rename survives. The file is kept.
        open().put("a", OPUS_128, type = "audio/ogg")
        runBlocking { TestCacheIndex.store(root).transact { it.cache().deleteTracks(listOf("a")) } }
        val cache = open()
        assertNull(cache.record("a"))
        assertTrue(File(root, "legacy/${TrackKey("a", OPUS_128).fileName}").isFile)
        assertTrue(cache.adoptLegacy("a")); assertEquals(OPUS_128, cache.kind("a"))
    }

    /** Prefer offline off whose index save fails keeps the set as it was (in memory too); a retry works. */
    @Test fun aFailedStopKeepingSaveLeavesTheSetKeptAndARetryWorks() {
        val index = hooked()
        val cache = open(index)
        cache.put("a")
        cache.renew(KeptItem("album/x", listOf(KeptTrack("a"), KeptTrack("b"))))
        val addedAt = cache.record("a")!!.addedAt
        wall += 10 * day
        index.failSave = true
        assertThrows(IOException::class.java) { cache.stopKeeping("album/x") }
        assertTrue("still kept", cache.isAdded("album/x"))
        assertEquals("its pending track still waits", setOf("b"), cache.pending("album/x"))
        assertEquals("no fresh Heard once yet", addedAt, cache.record("a")!!.addedAt)
        index.failSave = false
        assertTrue("the retry removes it", cache.stopKeeping("album/x"))
        assertFalse(cache.isAdded("album/x"))
        assertEquals(wall, cache.record("a")!!.addedAt)
        assertFalse("saved", open().isAdded("album/x"))
    }

    @Test fun deletesSaveTheIndexBeforeAnyFileGoes() {
        val index = hooked()
        val cache = open(index)
        cache.put("a"); cache.put("b")
        val files = listOf(cache.resolve("a")!!, cache.resolve("b")!!)
        index.failSave = true
        assertThrows(IOException::class.java) { cache.deleteNow(null, listOf("a")) }
        assertTrue("delete now: the file stays when the index write failed", files[0].isFile)
        space.setFree(policy.keepFreeBytes - 1)
        assertThrows(IOException::class.java) { cache.makeRoom(10) }
        assertTrue("eviction: the file stays when the index write failed", files[1].isFile)
        index.failSave = false
        val reopened = open()
        assertTrue("both still listed", reopened.contains("a") && reopened.contains("b"))
        // The live instance did not forget them either: found, counted, through a retry, a flush and maintenance.
        assertTrue("same instance: both still found", cache.contains("a") && cache.contains("b"))
        assertEquals(files, listOf(cache.resolve("a"), cache.resolve("b")))
        assertEquals("both still counted", 200L, cache.sizeBytes())
        cache.put("c"); wall += 400 * day
        index.failSave = true
        assertThrows(IOException::class.java) { cache.deleteNow(null, listOf("a")) }
        space.setFree(policy.keepFreeBytes - 1)
        assertThrows(IOException::class.java) { cache.makeRoom(10) }
        assertThrows(IOException::class.java) { cache.housekeep() }
        assertTrue("a retry that failed again forgets nothing", listOf("a", "b", "c").all(cache::contains) && files.all { it.isFile })
        assertEquals(300L, cache.sizeBytes())
        assertEquals("sizeBytes counts what is on disk", audioFiles().sumOf { it.length() }, cache.sizeBytes())
        index.failSave = false
        cache.flush()
        val flushed = open()
        assertTrue("the flush saved them as still cached", listOf("a", "b", "c").all(flushed::contains))
        // A later pass that saves deletes them consistently: record and file together.
        assertEquals(1, cache.deleteNow(null, listOf("a")))
        assertFalse(cache.contains("a") || files[0].isFile)
        cache.housekeep()
        assertFalse(cache.contains("b") || cache.contains("c") || files[1].isFile)
        val last = open()
        assertTrue("nothing left behind as an unlisted file", audioFiles().isEmpty() && File(root, "legacy").listFiles().orEmpty().isEmpty())
        assertFalse(listOf("a", "b", "c").any(last::contains))
    }

    /**
     * RV2-2 #2 (S1 #2), as the PO decided (2026-11-08, "Prefer offline" is best effort): an unreadable kept-set row is
     * wiped at load with its pending rows; its tracks are ordinary cached music under the usual rules from then on.
     */
    @Test fun anUnreadableKeptSetRowIsWipedAndItsTracksFollowTheUsualRules() = runBlocking {
        open().apply { put("a"); put("b"); renew(KeptItem("album/x", listOf(KeptTrack("a"), KeptTrack("b"), KeptTrack("c")))); flush() }
        TestCacheIndex.store(root).transact { d ->
            assertEquals(listOf("c"), d.cache().pending().map { it.songId })
            d.cache().upsertSets(d.cache().sets().map { it.copy(item = "[broken") })
        }
        val logs = mutableListOf<String>()
        val cache = open(CacheIndexDb(logs::add) { TestCacheIndex.store(root) })
        assertEquals("logged once", 1, logs.size)
        TestCacheIndex.store(root).transact { d ->
            assertTrue("the row is wiped", d.cache().sets().isEmpty())
            assertTrue("with its pending rows", d.cache().pending().isEmpty())
        }
        assertFalse(cache.isAdded("album/x"))
        assertTrue("its tracks still play", cache.contains("a") && cache.contains("b"))
        assertEquals("an ordinary keep-until", policy.keepUntil(cache.record("a")!!, null), cache.keepUntil(cache.record("a")!!))
        wall += 400 * day // past an ordinary keep-until: expired music leaves as usual
        cache.housekeep()
        assertFalse(cache.contains("a") || cache.contains("b"))
        assertTrue(audioFiles().isEmpty())
    }

    /** A readable index whose first saves fail opens degraded: plays, deletes nothing, saves later. */
    @Test fun aCacheWhoseFirstSavesFailOpensPlaysDeletesNothingAndResumesWhenSavesWork() {
        open().apply { put("a"); put("b"); flush() }
        val before = audioFiles().map { it.name }.toSet()
        wall += 400 * day // both past their keep-until
        val index = hooked().apply { failSave = true }
        val cache = open(index)
        assertFalse(cache.indexUnavailable)
        assertTrue("plays its cached files", cache.acquire("a")!!.isFile && cache.resolve("b")!!.isFile)
        cache.retainLeases(emptyList())
        assertEquals(emptyList<String>(), cache.housekeep())
        space.setFree(policy.keepFreeBytes - 1)
        assertFalse(cache.makeRoom(10))
        assertThrows("a flush tries the save again", IOException::class.java) { cache.flush() }
        assertEquals("nothing deleted while it cannot save", before, audioFiles().map { it.name }.toSet())
        assertTrue(cache.contains("a") && cache.contains("b"))
        assertTrue("nothing saved", index.bases.isEmpty())
        // Saves work again: the next pass persists the index and cleans up as usual.
        space.setFree(policy.keepFreeBytes * 2)
        index.failSave = false
        cache.housekeep()
        assertTrue("saved", index.bases.isNotEmpty())
        assertFalse("expired music leaves again", cache.contains("a") || cache.contains("b"))
        assertTrue(audioFiles().isEmpty())
    }

    /** An unreadable state column is kept on its own; the other column's clear stays a clear. */
    @Test fun anUnreadableStateColumnNeverRevivesTheOtherAndGoesOnceReplaced() = runBlocking {
        val queue = """{"key":"download/queue","tracks":[{"id":"q1"}],"title":"Queue","addedAt":7}"""
        val notice = """{"at":5,"titles":["Glass Orchard"],"bytes":9}"""
        suspend fun write(removed: String?, queued: String?) =
            TestCacheIndex.store(root).transact { it.cache().upsertState(CacheStateRow(0, "old", removed, queued)) }
        suspend fun saved() = TestCacheIndex.store(root).transact { it.cache().state()!! }
        val db = CacheIndexDb { TestCacheIndex.store(root) }
        // The notice is unreadable, the queue is fine and then ends: it stays ended.
        write("{bad", queue)
        db.load("me")!!.let { loaded -> assertNotNull(loaded.queueDownload); db.save("me", loaded, loaded.copy(queueDownload = null)) }
        assertNull("the cleared queue stays cleared", saved().queueDownload)
        assertNull("PO 2026-11-08: the unreadable notice is wiped", saved().removedForSpace)
        assertNull(CacheIndexDb { TestCacheIndex.store(root) }.load("other")!!.queueDownload)
        // The other way round: an acknowledged notice stays acknowledged.
        write(notice, "{bad")
        db.load("me")!!.let { loaded -> assertNotNull(loaded.removedForSpace); db.save("me", loaded, loaded.copy(removedForSpace = null)) }
        assertNull(saved().removedForSpace)
        assertNull("and the unreadable queue too", saved().queueDownload)
        // Replacing the unreadable column drops its fallback: clearing it later clears it.
        write("{bad", null)
        val loaded = db.load("me")!!
        val replaced = loaded.copy(removedForSpace = RemovedForSpace(8, listOf("Paper Lanterns"), 3))
        db.save("me", loaded, replaced)
        db.save("me", replaced, replaced.copy(removedForSpace = null))
        assertNull("no fallback after a replacement", saved().removedForSpace)
    }

    @Test fun aRecreatedDatabaseGetsTheWholeIndexAtTheNextFlush() {
        val index = hooked()
        val cache = open(index)
        cache.put("a")
        val saves = index.bases.size
        cache.flush()
        assertEquals("nothing changed: no write", saves, index.bases.size)
        index.lostOnce = true
        cache.flush()
        assertEquals(saves + 1, index.bases.size)
    }

    @Test fun aStartWritesOnlyWhatChangedNotTheWholeIndex() {
        open().apply { put("a"); put("b"); flush() }
        val index = hooked()
        open(index).flush()
        assertTrue("the start's save is a diff against what it loaded", index.bases.isNotEmpty() && index.bases.all { it != null })
        assertEquals(setOf("a", "b"), index.bases.first()!!.tracks.keys)
    }

    @Test fun anUnclaimedLoadStillOpensTheIndexAndTheNextSaveWritesItAll() {
        open().apply { put("a"); flush() }
        val index = hooked().apply { unclaimed = true }
        val cache = open(index)
        assertTrue("not inert", cache.contains("a") && !cache.indexUnavailable)
        assertNull("no base: every row is written", index.bases.first())
    }

    @Test fun theJsonColumnsDecodeAndAnUnreadableRowIsKeptNotDeleted() = runBlocking {
        // v1 rows as written by this version (the decode half of the migration scaffold: later versions must read these).
        val logs = mutableListOf<String>()
        val db = CacheIndexDb(logs::add) { TestCacheIndex.store(root) }
        TestCacheIndex.store(root).transact { d ->
            val dao = d.cache()
            dao.upsertState(CacheStateRow(0, "old",
                """{"at":5,"titles":["Glass Orchard"],"bytes":9}""",
                """{"key":"download/queue","tracks":[{"id":"q1"}],"title":"Queue","addedAt":7}"""))
            dao.upsertTracks(listOf(
                CacheTrackRow("t1", "f.orig", "RAW", 4, 1, 2, 3, "audio/flac", """{"suffix":"flac","bitRateKbps":900}""",
                    """{"title":"T","artist":"A","album":"B","albumId":"al-1","track":2}"""),
                CacheTrackRow("bad", "g.opus", "OPUS_128", 4, 1, null, 0, null, "{}", "{not json")))
            dao.upsertSets(listOf(
                CacheSetRow("album/al-1", 0, 11, """{"key":"album/al-1","tracks":[{"id":"t1","meta":{"title":"T"}}],"title":"B","coverArt":"c"}"""),
                CacheSetRow("album/bad", 1, 12, "[broken")))
        }
        val loaded = db.load("me")!!
        assertEquals(RemovedForSpace(5, listOf("Glass Orchard"), 9), loaded.removedForSpace)
        assertEquals(listOf("q1"), loaded.queueDownload!!.trackIds)
        val t1 = loaded.tracks.getValue("t1")
        assertEquals(RAW, t1.kind); assertEquals(3, t1.playCount); assertEquals(2L, t1.lastPlayedAt)
        assertEquals("flac", t1.format.suffix); assertEquals(TrackMeta("T", "A", "B", "al-1", track = 2), t1.meta)
        assertEquals(listOf("album/al-1"), loaded.sets.map { it.key })
        assertEquals(11L, loaded.sets.single().addedAt); assertEquals("c", loaded.sets.single().coverArt)
        assertFalse(loaded.tracks.containsKey("bad"))
        assertEquals("logged once", 1, logs.size)
        // A full rewrite (another writer's base) keeps the unreadable track rows. The unreadable
        // set row was wiped at load already.
        db.save("me", null, loaded.copy(tracks = emptyMap(), sets = emptyList()))
        TestCacheIndex.store(root).transact { d ->
            assertEquals(listOf("bad"), d.cache().trackIds())
            assertEquals(emptyList<String>(), d.cache().setKeys())
        }
    }

    /**
     * While degraded a pass is not due (no 60 s loop), the preview names nothing, and
     * Confirm is still asked (an empty plan), so the settings are saved; nothing is deleted, no stale partial either.
     */
    @Test fun aDegradedCacheIsNotDueKeepsNoShortIntervalPreviewsNothingAndStillConfirms() {
        open().apply { put("a"); flush() }
        val part = File(root, TrackKey("b", RAW).fileName + ".part").apply { writeBytes(ByteArray(500)); setLastModified(System.currentTimeMillis() - 30 * day) }
        val index = hooked().apply { failSave = true }
        val cache = open(index)
        space.setFree(policy.keepFreeBytes - 1)
        cache.housekeep()
        assertFalse("nothing due while degraded", cache.maintenanceDue())
        assertFalse("no short interval", cache.dueAfterPass || cache.nearLine)
        val line = policy.copy(keepFreeBytes = policy.keepFreeBytes * 2)
        assertTrue("the sheet lists nothing", cache.preview(line).isEmpty)
        var seen: CachePlanner.Plan? = null
        assertEquals(emptyList<String>(), cache.housekeepIf(line, notice = false) { seen = it; true })
        assertTrue("Confirm is asked, with nothing to delete", seen!!.isEmpty)
        assertTrue(cache.contains("a") && part.isFile)
    }

    /** The start's adoption of a kept legacy file that the failed save stopped runs again once saves work. */
    @Test fun theStartAdoptionStoppedByAFailedSaveRunsAgainWhenSavesWork() {
        open().apply { put("a"); renew(KeptItem("album/x", listOf(KeptTrack("a")))); flush() }
        runBlocking { TestCacheIndex.store(root).transact { it.cache().deleteTracks(listOf("a")) } }
        val index = hooked().apply { failSave = true }
        val cache = open(index)
        assertFalse("not adopted while saves fail", cache.contains("a"))
        index.failSave = false
        cache.housekeep()
        assertTrue("adopted after the first save that works", cache.contains("a") && cache.resolve("a")!!.isFile)
    }
}
