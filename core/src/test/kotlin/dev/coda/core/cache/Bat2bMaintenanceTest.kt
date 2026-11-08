package dev.coda.core.cache

import dev.coda.core.playback.StreamFormat.RAW
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * A maintenance pass with nothing due returns before any folder walk, and the early-out never skips a deletion
 * that is due: an expiry, the line, a dead kept set, a file that stops being protected. Events bump on commits and plays.
 */
class Bat2bMaintenanceTest : CacheFixture() {
    private val line = 10_000L
    private class CountingDir(path: String) : File(path) {
        var listings = 0
        override fun listFiles(): Array<File>? { listings++; return super.listFiles() }
    }
    private lateinit var dir: CountingDir

    @Before fun setUp() {
        policy = CachePolicy(Lifetime.MONTH, Lifetime.THREE_DAYS, Lifetime.TWO_WEEKS, keepFreeBytes = line)
        dir = CountingDir(root.path)
    }

    private fun steady(): TrackCache = open(dir).also { cache ->
        cache.put("a"); cache.put("b")
        cache.housekeep()   // the start pass: the full walk (orphans, stale parts)
        assertFalse(cache.dueAfterPass)
        dir.listings = 0
    }

    @Test fun aPassWithNothingDueListsNoFolderAndDeletesNothing() {
        val cache = steady()
        repeat(5) { assertEquals(emptyList<String>(), cache.housekeep()) }
        assertEquals("no folder walk while nothing is due", 0, dir.listings)
        assertTrue(cache.contains("a") && cache.contains("b"))
        assertFalse(cache.maintenanceDue())
    }

    @Test fun anExpiryBetweenPassesIsNotSkipped() {
        val cache = steady()
        cache.housekeep()
        wall += 3 * day + 1
        assertTrue(cache.maintenanceDue())
        cache.housekeep()
        assertFalse(cache.contains("a")); assertFalse(cache.contains("b"))
    }

    @Test fun freeSpaceBelowTheLineIsNotSkippedAndStaysDueUntilFixed() {
        val cache = steady()
        cache.put("c")
        space.setFree(line - 50)
        assertTrue(cache.maintenanceDue())
        cache.housekeep()
        assertEquals("the earliest not-kept file went", 2, cache.records().size)
        // A kept set below the line waits for the 60 s hold: the pass reports it is still due (the ticker's short retry).
        cache.renew(KeptItem("album/k", listOf(KeptTrack("b"), KeptTrack("c"))))
        space.setFree(line - 500)
        cache.housekeep()
        assertTrue(cache.dueAfterPass)
        wall += 60_000
        assertEquals(listOf("album/k"), cache.housekeep())
    }

    @Test fun nearLineIsTheLinePlusTheMarginAndNeverChangesWhatIsDue() {
        val cache = steady()
        space.setFree(line + 1_000_000_000)   // 1 GB above: far (margin 500 MB)
        cache.housekeep(); assertFalse(cache.nearLine); assertFalse(cache.dueAfterPass)
        space.setFree(line + 100_000_000)     // inside the margin: near, but nothing is due
        cache.housekeep(); assertTrue(cache.nearLine); assertFalse(cache.dueAfterPass); assertFalse(cache.maintenanceDue())
        space.setFree(line - 1)               // below: near and due
        assertTrue(cache.maintenanceDue()); assertTrue(cache.nearLine)
    }

    @Test fun aDeadKeptSetIsNotSkipped() {
        val cache = steady()
        cache.renew(KeptItem("album/gone", listOf(KeptTrack("never-cached"))))
        cache.housekeep()
        assertTrue(cache.isAdded("album/gone"))
        wall += 31 * day
        assertTrue(cache.maintenanceDue())
        assertTrue("album/gone" in cache.housekeep())
    }

    @Test fun anExpiredFileThatIsUnpinnedIsNotSkipped() {
        val cache = steady()
        cache.pin(listOf("a"))
        wall += 3 * day + 1
        cache.housekeep()
        assertTrue("pinned: protected", cache.contains("a")); assertFalse(cache.contains("b"))
        assertTrue("a protected expired file is not due", !cache.maintenanceDue())
        cache.pin(emptyList())
        assertTrue(cache.maintenanceDue())
        cache.housekeep()
        assertFalse(cache.contains("a"))
    }

    @Test fun aWriteThatEndsWithoutItsCommitAsksForTheOrphanCheck() {
        val cache = steady()
        cache.begin(TrackKey("partial", RAW))!!.use { it.output.write(flac) }
        assertTrue(cache.maintenanceDue())
        cache.housekeep()
        assertTrue("the orphan check listed the folder", dir.listings > 0)
        // Resumable and above the line: it stays (unchanged), and the next pass is quiet again.
        assertTrue(root.listFiles()!!.any { it.name.endsWith(".part") })
        dir.listings = 0
        cache.housekeep()
        assertEquals(0, dir.listings)
        // Below the line the orphan goes (unchanged rule), on the next pass.
        space.setFree(line - 10)
        cache.housekeep()
        assertFalse(root.listFiles()!!.any { it.name.endsWith(".part") })
    }

    @Test fun stalePartialFilesAreSweptAtStartAndThenEverySixHours() {
        val stale = File(root, songFileBase("old") + ".orig.part").apply { writeBytes(ByteArray(10)); setLastModified(System.currentTimeMillis() - 8 * day) }
        val cache = steady()
        assertFalse("swept at start", stale.exists())
        val later = File(root, songFileBase("later") + ".orig.part").apply { writeBytes(ByteArray(10)); setLastModified(System.currentTimeMillis() - 8 * day) }
        cache.housekeep()
        assertTrue("not before the next sweep", later.exists())
        wall += 6 * 60 * 60_000L
        cache.housekeep()
        assertFalse(later.exists())
    }

    @Test fun eventsBumpOnEachCommitAndEachPlay() {
        val cache = open(dir)
        val start = cache.maintenanceEvents.value
        cache.put("a")
        assertEquals(start + 1, cache.maintenanceEvents.value)
        cache.markPlayed("a")
        assertEquals(start + 2, cache.maintenanceEvents.value)
        cache.begin(TrackKey("x", RAW))!!.use { it.output.write(flac) }
        assertEquals("a write without its commit is not a song written", start + 2, cache.maintenanceEvents.value)
    }

    @Test fun aChangedPolicyIsNeverAnEarlyOut() {
        val cache = steady()
        val less = policy.copy(heardOnce = Lifetime.HALF_DAY, heardAgain = Lifetime.HALF_DAY)
        wall += day
        assertTrue(cache.preview(less).evict.isNotEmpty())
        cache.housekeep(less)
        assertFalse(cache.contains("a"))
    }
}
