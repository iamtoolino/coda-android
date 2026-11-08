package dev.coda.core.cache

import dev.coda.core.cache.CachePlanner.KeptSet
import dev.coda.core.cache.Lifetime.*
import org.junit.Assert.*
import org.junit.Test

/** CA-3 §3, §3.2, §7: the one pure planner (deletion order, whole kept sets, overlap units, protection). */
class CachePlannerTest {
    private val now = 1_000L
    private val policy = CachePolicy.DEFAULT
    private fun f(name: String, bytes: Long = 100, until: Long = now + 10, used: Long = 0, removable: Boolean = true, played: Long? = null) =
        Evictable(name, bytes, until, used, name, played, removable = removable)
    private fun set(key: String, vararg ids: String, keptAt: Long = 0) = KeptSet(key, key.substringAfter('/'), keptAt, ids.toSet())
    private fun plan(files: List<Evictable>, sets: List<KeptSet> = emptyList(), need: Long, keptToo: Boolean = true,
        expire: Boolean = true, holder: Set<String> = emptySet(), with: CachePolicy = policy) =
        CachePlanner.plan(files, sets, now, with, need, keptToo, expire, holder)

    @Test fun expiryComesFirstAndCountsTowardsTheLine() {
        val files = listOf(f("past", until = now), f("soon", until = now + 1), f("later", until = now + 5))
        val p = plan(files, need = 0)
        assertEquals(listOf("past"), p.expired.map { it.name }); assertTrue(p.notKept.isEmpty())
        // Expired bytes count towards what the line needs.
        assertTrue(plan(files, need = 100).notKept.isEmpty())
        assertEquals(listOf("soon"), plan(files, need = 150).notKept.map { it.name })
        // Without expiry (makeRoom, planAdd) the order still takes the past one first.
        assertEquals(listOf("past"), plan(files, need = 50, expire = false).notKept.map { it.name })
    }

    @Test fun notKeptGoesEarliestKeepUntilFirstAndInfiniteTiesGoLeastRecentlyUsed() {
        val files = listOf(f("b", until = now + 3), f("a", until = now + 2), f("c", until = now + 9))
        assertEquals(listOf("a", "b"), plan(files, need = 150).notKept.map { it.name })
        val forever = listOf(f("new", until = Long.MAX_VALUE, used = 50), f("old", until = Long.MAX_VALUE, used = 10),
            f("mid", until = Long.MAX_VALUE, used = 30))
        assertEquals(listOf("old", "mid"), plan(forever, need = 200).notKept.map { it.name })
        // Then the order key, so the result is deterministic.
        val tied = listOf(Evictable("x", 100, now + 5, 5, "x", order = "b"), Evictable("y", 100, now + 5, 5, "y", order = "a"))
        assertEquals(listOf("y"), plan(tied, need = 1).notKept.map { it.name })
    }

    @Test fun keptMusicGoesLastAndWhole() {
        val files = listOf(f("loose", until = now + 50), f("k1", until = now + 1), f("k2", until = now + 2))
        val p = plan(files, listOf(set("album/k", "k1", "k2", "missing")), need = 150)
        assertEquals(listOf("loose"), p.notKept.map { it.name })
        assertEquals(listOf("album/k"), p.removedSets.map { it.key })
        assertEquals(setOf("k1", "k2"), p.units.single().files.map { it.name }.toSet())
        assertEquals(300, p.bytes); assertTrue(p.enough)
        assertEquals(listOf("k1", "k2"), p.keptFiles.map { it.name }.sorted()); assertEquals(listOf("loose"), p.notKeptFiles.map { it.name })
        // keptToo = false (read-ahead, background downloads) never returns kept tracks.
        val window = plan(files, listOf(set("album/k", "k1", "k2")), need = 1_000, keptToo = false)
        assertEquals(listOf("loose"), window.evict.map { it.name }); assertEquals(900, window.missingBytes); assertFalse(window.enough)
    }

    @Test fun aSetWithAProtectedOwnFileIsSkippedAndTheNextSetGoes() {
        for (protectedFile in listOf("a1", "a2")) {
            val files = listOf(f("a1", until = Long.MAX_VALUE), f("a2", until = Long.MAX_VALUE), f("b1", until = Long.MAX_VALUE)).map { if (it.name == protectedFile) it.copy(removable = false) else it }
            val p = plan(files, listOf(set("album/a", "a1", "a2", keptAt = 1), set("album/b", "b1", keptAt = 5)), need = 100)
            assertEquals(listOf("album/b"), p.removedSets.map { it.key })
            assertEquals(listOf("b1"), p.evict.map { it.name })
        }
    }

    @Test fun aSharedTrackLeavesOnlyWithItsSecondSetAndIsCountedOnce() {
        val files = listOf(f("x", until = Long.MAX_VALUE), f("y", until = Long.MAX_VALUE), f("shared", until = Long.MAX_VALUE))
        val sets = listOf(set("album/a", "x", "shared", keptAt = 1), set("playlist/b", "y", "shared", keptAt = 2))
        val first = plan(files, sets, need = 100)
        assertEquals(listOf("album/a"), first.removedSets.map { it.key })
        assertEquals(listOf("x"), first.evict.map { it.name })
        val both = plan(files, sets, need = 250)
        assertEquals(listOf("album/a", "playlist/b"), both.removedSets.map { it.key })
        assertEquals(setOf("x", "y", "shared"), both.evict.map { it.name }.toSet())
        assertEquals(300, both.bytes)
    }

    @Test fun identicalMembershipLeavesAsOneUnitAndFreesTheBytesOnce() {
        val files = listOf(f("p", until = Long.MAX_VALUE), f("q", until = Long.MAX_VALUE), f("other", until = Long.MAX_VALUE))
        val sets = listOf(set("album/x", "p", "q", keptAt = 1), set("playlist/x", "p", "q", keptAt = 1), set("album/z", "other", keptAt = 9))
        val p = plan(files, sets, need = 150)
        assertEquals(listOf("album/x", "playlist/x"), p.units.single().sets.map { it.key })
        assertEquals(200, p.bytes)
        assertEquals(listOf("album/x", "playlist/x"), p.keptSetsGone.map { it.key })
        // A unit with one protected file stays whole: the next set goes instead.
        val guarded = plan(files.map { if (it.name == "q") it.copy(removable = false) else it }, sets, need = 150)
        assertEquals(listOf("album/z"), guarded.removedSets.map { it.key }); assertEquals(50, guarded.missingBytes)
    }

    @Test fun setsGoInTheOrderOfTheirLatestMemberKeepUntilThenLeastRecentlyUsed() {
        val g = CachePolicy(THREE_MONTHS, THREE_DAYS, TWO_WEEKS)
        val files = listOf(f("a1", until = now + 1), f("a2", until = now + 40), f("b1", until = now + 20), f("b2", until = now + 21))
        val sets = listOf(set("album/a", "a1", "a2"), set("album/b", "b1", "b2"))
        assertEquals(listOf("album/b"), plan(files, sets, need = 150, with = g).removedSets.map { it.key })
        // ∞ everywhere: the one used longest ago goes first: max(keptAt, newest play).
        val forever = listOf(f("a1", until = Long.MAX_VALUE, played = 900), f("b1", until = Long.MAX_VALUE, played = 100))
        val tie = listOf(set("album/a", "a1", keptAt = 50), set("album/b", "b1", keptAt = 300))
        assertEquals(listOf("album/b"), plan(forever, tie, need = 50).removedSets.map { it.key })
    }

    @Test fun theRequestingAddIsAHolderThatIsNeverChosen() {
        val files = listOf(f("x", until = Long.MAX_VALUE), f("mine", until = Long.MAX_VALUE, removable = false))
        val p = plan(files, listOf(set("album/a", "x", "mine")), need = 100, expire = false, holder = setOf("mine"))
        assertEquals(listOf("x"), p.evict.map { it.name })
    }

    @Test fun deadSetsOnlyWithAFiniteKeptTimeAndNoCachedTrackLeft() {
        val month = CachePolicy(MONTH, THREE_DAYS, TWO_WEEKS)
        val old = set("album/old", "gone", keptAt = now - 31 * CacheRules.DAY_MS)
        assertEquals(listOf("album/old"), plan(emptyList(), listOf(old), need = 0, with = month).deadSets.map { it.key })
        assertTrue(plan(emptyList(), listOf(old), need = 0).deadSets.isEmpty())
        // Its last track expiring in this plan makes it dead in the same pass.
        val withTrack = plan(listOf(f("gone", until = now - 1)), listOf(old), need = 0, with = month)
        assertEquals(listOf("album/old"), withTrack.deadSets.map { it.key }); assertEquals(listOf("gone"), withTrack.expired.map { it.name })
        assertEquals(listOf("album/old"), withTrack.keptSetsGone.map { it.key })
    }

    @Test fun nothingToDoIsEmpty() {
        val p = plan(listOf(f("a")), need = -5)
        assertTrue(p.isEmpty); assertTrue(p.enough); assertEquals(0, p.bytes)
    }
}
