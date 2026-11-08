package dev.coda.core.cache

import dev.coda.core.playback.StreamFormat
import dev.coda.core.playback.StreamFormat.OPUS_128
import dev.coda.core.playback.StreamFormat.RAW
import dev.coda.core.testing.TestCacheIndex
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * "no index" (a new, lost or recreated `library.db`) never deletes music. The audio files found are kept as legacy
 * files, counted, aged like any track and adopted when their song is next seen.
 */
class OfflineCacheTest : CacheFixture() {
    /** A complete audio file in the cache folder that no index lists. */
    private fun unindexed(id: String, format: StreamFormat, bytes: ByteArray): File =
        File(root, TrackKey(id, format).fileName).apply { writeBytes(bytes) }

    private fun legacyAudio() = File(root, "legacy").listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{40}\\.(orig|opus)")) }

    @Test fun aLostDatabaseIsNoIndexAndDeletesNoMusic() {
        // Rex defect 1 (files-vs-db review): an unreadable index must mean "no index", never "an index with no tracks".
        val cache = open()
        cache.put("a", OPUS_128, type = "audio/ogg")
        cache.put("b", RAW)
        cache.renew(KeptItem("album/x", listOf(KeptTrack("a"), KeptTrack("b"))))
        val bytes = cache.sizeBytes()
        TestCacheIndex.wipe(root)
        val reopened = open()
        assertEquals("every file is kept and counted", bytes, reopened.sizeBytes())
        assertEquals(2, legacyAudio().size)
        assertNull(reopened.record("a"))
        assertTrue(reopened.adoptLegacy("a")); assertEquals(OPUS_128, reopened.kind("a"))
        assertTrue(reopened.adoptLegacy("b")); assertEquals(RAW, reopened.kind("b"))
        assertTrue("the adoptions are in the new index", open().let { it.contains("a") && it.contains("b") })
    }

    @Test fun aDamagedDatabaseIsRecreatedAndTheMusicIsKept() {
        val cache = open()
        cache.put("a", RAW)
        val file = TestCacheIndex.file(root)
        TestCacheIndex.store(root).close()
        for (suffix in listOf("-wal", "-shm")) File(file.path + suffix).delete()
        file.writeText("not a database")
        val reopened = open()
        assertTrue("set aside as a last resort", File(file.path + ".broken").isFile)
        assertEquals(1, legacyAudio().size)
        assertTrue(reopened.adoptLegacy("a"))
        assertTrue(reopened.resolve("a")!!.isFile)
    }

    @Test fun withAnIndexUnlistedAudioIsKeptAsLegacyAndOtherLeftoversGo() {
        // A complete audio file without a row is kept, not deleted as an orphan.
        open().flush()
        val unlisted = unindexed("gone", RAW, flac)
        val junk = File(root, "leftover.tmp").apply { writeText("x") }
        open()
        assertFalse(unlisted.exists()); assertEquals(1, legacyAudio().size)
        assertFalse(junk.exists())
    }

    @Test fun unadoptedFilesAgeOutLikeAnyTrackWhileTheServerIsReachable() {
        unindexed("unknown", RAW, flac)
        val cache = open()
        assertEquals(flac.size.toLong(), cache.sizeBytes())
        wall += 30 * day
        cache.serverReachable(false)
        cache.housekeep()
        assertEquals("offline nothing leaves for being old", flac.size.toLong(), cache.sizeBytes())
        cache.serverReachable(true)
        cache.housekeep()
        assertEquals(0L, cache.sizeBytes())
        assertTrue(legacyAudio().isEmpty())
    }

    @Test fun keptTracksWithLegacyFilesAreAdoptedWhenTheCacheOpens() {
        unindexed("k1", RAW, flac)
        open().renew(KeptItem("album/a", listOf(KeptTrack("k1"), KeptTrack("k2"))))
        val cache = open()
        assertEquals(RAW, cache.kind("k1"))
        assertEquals(setOf("k2"), cache.pending("album/a"))
    }

    @Test fun anInterruptedAdoptionKeepsTheMusicAndFinishesWhenTheCacheOpens() {
        unindexed("w1", RAW, flac)
        unindexed("w2", OPUS_128, flac)                // the FLAC fallback in an ".opus" file
        unindexed("w2", RAW, flac)                     // and the original: one copy is kept
        open()
        for ((id, point) in listOf("w1" to "adopt.persisted", "w2" to "adopt.renamed")) {
            val cache = open()
            cache.crashPoint = { if (it == point) throw IllegalStateException(point) }
            assertThrows(IllegalStateException::class.java) { cache.adoptLegacy(id, meta = TrackMeta(id)) }
            // The process dies here; the next start reads only what is on disk.
            val reopened = open()
            assertEquals(point, RAW, reopened.kind(id))
            assertTrue(point, reopened.resolve(id)!!.isFile)
            assertEquals(TrackMeta(id), reopened.record(id)!!.meta)
            assertEquals(wall, reopened.record(id)!!.addedAt)
        }
        val cache = open()
        assertEquals("One copy per song, no leftovers", 2L * flac.size, cache.sizeBytes())
        assertTrue(legacyAudio().isEmpty())
    }

    @Test fun aHandOffDuringAnAdoptionNeverGetsAFileThatIsNotThere() {
        unindexed("w1", RAW, flac)
        val cache = open()
        val seen = mutableListOf<String>()
        cache.crashPoint = { point ->
            // The player's hand-off on another thread while the adoption is between its steps.
            kotlin.concurrent.thread {
                val acquired = cache.acquire("w1")
                val resolved = cache.resolve("w1")
                if (acquired != null && !acquired.isFile) seen += "$point: acquire() gave a missing file"
                if (resolved != null && !resolved.isFile) seen += "$point: resolve() gave a missing file"
                cache.retainLeases(emptyList())
            }.join()
        }
        assertTrue(cache.adoptLegacy("w1"))
        assertEquals(emptyList<String>(), seen)
        assertTrue(cache.acquire("w1")!!.isFile)
    }

    @Test fun aPollDuringAnAdoptionSeesANewRevisionOnceTheTrackIsPublished() {
        unindexed("w1", RAW, flac)
        val cache = open()
        // PlaybackService's poll: re-index only when the revision changed since what it last saw.
        var polled: Pair<Long, Boolean>? = null
        cache.crashPoint = { point ->
            if (point == "adopt.persisted") kotlin.concurrent.thread { polled = cache.revision to cache.contains("w1") }.join()
        }
        assertTrue(cache.adoptLegacy("w1"))
        val (revision, cached) = polled!!
        assertFalse("Not a track yet while its file moves", cached)
        assertNotEquals("Published under a revision the poll hasn't seen", revision, cache.revision)
        assertTrue(cache.contains("w1"))
    }
}
