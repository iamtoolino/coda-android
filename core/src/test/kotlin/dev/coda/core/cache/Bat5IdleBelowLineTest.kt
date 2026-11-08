package dev.coda.core.cache

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Below the Keep free space line, a full pass that found nothing to remove is remembered, and the ticks after it
 * skip the full pass while its inputs (free space within 50 MB, policy, index revision, reachability, pins, leases,
 * writers, Coda's own last delete) stay the same. Any change, any deletion and a fresh start run the full pass again.
 */
class Bat5IdleBelowLineTest : CacheFixture() {
    private val line = 10 * GiB
    private val below = line - GiB
    private class CountingDir(path: String) : File(path) {
        var listings = 0
        override fun listFiles(): Array<File>? { listings++; return super.listFiles() }
    }
    private lateinit var dir: CountingDir

    @Before fun setUp() {
        policy = CachePolicy(Lifetime.MONTH, Lifetime.THREE_DAYS, Lifetime.TWO_WEEKS, keepFreeBytes = line)
        dir = CountingDir(root.path)
    }

    /** Below the line with nothing removable (both songs are in the read-ahead window); the first pass ran in full. */
    private fun idleBelow(): TrackCache = open(dir).also { cache ->
        cache.put("a"); cache.put("b")
        cache.pin(listOf("a", "b"))
        space.setFree(below)
        cache.housekeep()
        assertTrue(cache.contains("a") && cache.contains("b"))
        dir.listings = 0
    }

    private fun fullPass(cache: TrackCache): Boolean { dir.listings = 0; cache.housekeep(); return dir.listings > 0 }

    @Test fun aSecondTickWithNothingRemovableDoesNoFullPass() {
        val cache = idleBelow()
        assertFalse(cache.maintenanceDue())
        repeat(3) { assertFalse("tick ${it + 1}", fullPass(cache)) }
        assertFalse(cache.dueAfterPass)
        assertTrue(cache.contains("a") && cache.contains("b"))
    }

    @Test fun freeSpaceThatMovesMoreThan50MbRunsTheNextTick() {
        val cache = idleBelow()
        space.setFree(below - 10_000_000)
        assertFalse("10 MB: still the same", fullPass(cache))
        space.setFree(below - 60_000_000)
        assertTrue(cache.maintenanceDue())
        assertTrue(fullPass(cache))
        assertFalse("remembered again", fullPass(cache))
        space.setFree(below + 60_000_000)
        assertTrue("more free space counts too", fullPass(cache))
    }

    @Test fun aChangedPolicyRunsTheNextTick() {
        val cache = idleBelow()
        policy = policy.copy(heardOnce = Lifetime.HALF_DAY)
        assertTrue(cache.maintenanceDue())
        assertTrue(fullPass(cache))
    }

    @Test fun aNewRevisionRunsTheNextTickAndRemovesWhatBecameRemovable() {
        val cache = idleBelow()
        cache.put("c")   // a commit: the index revision moves, and "c" is neither kept nor pinned
        assertTrue(cache.maintenanceDue())
        assertTrue(fullPass(cache))
        assertFalse(cache.contains("c"))
    }

    @Test fun aChangedReachabilityRunsTheNextTick() {
        val cache = idleBelow()
        cache.serverReachable(false)
        assertTrue(cache.maintenanceDue())
        assertTrue(fullPass(cache))
        assertFalse(fullPass(cache))
        cache.serverReachable(true)
        assertTrue(fullPass(cache))
    }

    @Test fun changedPinsLeasesOrWritersRunTheNextTick() {
        val cache = idleBelow()
        cache.pin(listOf("a"))
        assertTrue(cache.maintenanceDue())
        assertTrue(fullPass(cache))
        assertFalse("b left the window: it went for space", cache.contains("b"))
        assertTrue("the pass after a delete", fullPass(cache))
        assertFalse(fullPass(cache))
        cache.acquire("a")!!
        assertTrue("a lease", fullPass(cache))
        assertFalse(fullPass(cache))
        val write = cache.begin(dev.coda.core.cache.TrackKey("w", dev.coda.core.playback.StreamFormat.RAW))!!
        assertTrue("a writer", fullPass(cache))
        write.close()
    }

    @Test fun aPassThatDeletesSomethingSetsNoMemo() {
        val cache = idleBelow()
        cache.put("c")
        assertTrue(fullPass(cache))
        assertFalse(cache.contains("c"))
        assertTrue("the pass after a delete runs in full", fullPass(cache))
        assertFalse("then nothing changed", fullPass(cache))
    }

    // No "fresh start" test. The memo is a field of the TrackCache object, and a new one always runs its first pass
    // in full because the orphan check and the stale-part sweep are due at start, memo or not; a test could not
    // tell a kept memo apart from a dropped one.

    @Test fun keptMusicWaitingForTheHoldIsNotRemembered() {
        val cache = open(dir)
        cache.put("a"); cache.put("b"); cache.put("c")
        cache.renew(KeptItem("album/k", listOf(KeptTrack("b"), KeptTrack("c"))))
        cache.pin(listOf("x"))
        space.setFree(below)
        cache.housekeep()
        assertFalse("the not-kept song went first", cache.contains("a"))
        assertTrue("the kept set waits for the hold", cache.isAdded("album/k"))
        wall += 30_000
        assertTrue("still within the hold: no memo", fullPass(cache))
        assertTrue(cache.dueAfterPass)
        wall += 30_000
        assertTrue(fullPass(cache))
        assertFalse(cache.isAdded("album/k"))
    }

    @Test fun anExpiryOrADeadSetWhileRememberedIsNotSkipped() {
        val cache = idleBelow()
        val keptAt = wall
        cache.renew(KeptItem("album/gone", listOf(KeptTrack("never-cached"))))
        cache.housekeep(); cache.housekeep()
        assertFalse(fullPass(cache))
        // A pass just before the set's time runs out (it also does the 6 h stale-part sweep), then a step of a few
        // ms past it, so the expiry and not the sweep is what makes the next tick due.
        val until = policy.setUntil(keptAt)
        wall = until - 5
        cache.housekeep()
        assertTrue(cache.isAdded("album/gone"))
        assertFalse("just before: nothing due", cache.maintenanceDue())
        wall = until + 5
        assertTrue(cache.maintenanceDue())
        assertTrue("album/gone" in cache.housekeep())
    }

    @Test fun normalExpiryAboveTheLineIsUnaffected() {
        val cache = idleBelow()
        space.setFree(line + GiB)
        cache.pin(emptyList())
        cache.housekeep()
        assertTrue(cache.contains("a"))
        // As above, a pass just before the first keep-until, then a few ms past it.
        val until = listOf("a", "b").minOf { cache.keepUntil(cache.record(it)!!) }
        wall = until - 5
        cache.housekeep()
        assertTrue(cache.contains("a") && cache.contains("b"))
        assertFalse("just before: nothing due", cache.maintenanceDue())
        wall = until + 5
        assertTrue(cache.maintenanceDue())
        cache.housekeep()
        assertFalse(cache.contains("a")); assertFalse(cache.contains("b"))
    }
}
