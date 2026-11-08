package dev.coda.core.cache

import dev.coda.core.cache.Lifetime.*
import org.junit.Assert.*
import org.junit.Test

/** Lifetimes, the play-counter rule, examples A–I of §1.5, normalization and presets. */
class CachePolicyTest {
    private val day = CacheRules.DAY_MS
    private val now = 10_000 * day
    private fun ago(days: Int) = now - days * day
    private val default = CachePolicy.DEFAULT
    private val never = Long.MAX_VALUE

    @Test fun stepsHaveTheirDaysAndKeys() {
        assertEquals(listOf("12h", "1d", "3d", "1w", "2w", "1m", "3m", "6m", "1y", "max"), Lifetime.entries.map { it.key })
        assertEquals(listOf(12, 24, 72, 168, 336, 720, 2160, 4320, 8760, null), Lifetime.entries.map { it.hours })
        assertEquals(12 * 3_600_000L, HALF_DAY.ms)
        assertNull(MAX.ms)
        Lifetime.entries.forEach { assertEquals(it, Lifetime.fromKey(it.key)) }
        assertNull(Lifetime.fromKey("2d")); assertNull(Lifetime.fromKey(null))
        assertEquals(listOf(1, 2, 5, 10, 15, 20, 30, 50), CachePolicy.KEEP_FREE_GB)
        assertEquals(5_000_000_000L, default.keepFreeBytes)
    }

    @Test fun asLongAsPossibleSaturatesWithoutOverflow() {
        assertEquals(never, plusSaturating(now, null))
        assertEquals(never, plusSaturating(Long.MAX_VALUE - 5, day))
        assertEquals(now + day, plusSaturating(now, day))
        val stream = CachePolicy(MAX, MAX, MAX)
        assertEquals(never, stream.keepUntil(Long.MAX_VALUE - 1, Long.MAX_VALUE - 1, 7, Long.MAX_VALUE - 1))
    }

    private data class Example(val id: String, val policy: CachePolicy, val addedAt: Long, val lastPlayedAt: Long?, val plays: Int,
        val keptAt: Long?, val want: Long)

    /** §1.5, shared with the mockup. Times in the order Kept / Heard once / Heard again. */
    private val examples = listOf(
        Example("A", default, ago(1), null, 0, null, now + 2 * day),
        Example("B", default, ago(5), ago(1), 1, null, now + 2 * day),
        Example("C", default, ago(5), ago(5), 1, null, ago(2)),
        Example("D", default, ago(30), ago(2), 2, null, now + 12 * day),
        // A keep raises addedAt to the keep date (TrackCache.renew).
        Example("E", default, ago(300), ago(200), 1, ago(300), never),
        Example("F", CachePolicy(MAX, HALF_DAY, HALF_DAY), ago(3), ago(3), 2, null, ago(3) + day / 2),
        Example("G", CachePolicy(THREE_MONTHS, THREE_DAYS, TWO_WEEKS), ago(100), ago(10), 2, ago(100), now + 4 * day),
        // Download whole queue renewed addedAt to now; the older repeat play doesn't shorten it.
        Example("H", default, now, ago(13), 2, null, now + 3 * day),
        Example("I", CachePolicy(MAX, MAX, MAX), ago(400), ago(400), 1, null, never),
    )

    @Test fun examplesAToIOfTheSpec() {
        for (e in examples) assertEquals("example ${e.id}", e.want, e.policy.keepUntil(e.addedAt, e.lastPlayedAt, e.plays, e.keptAt))
        // C and F are past: they go at the next check; E and I have no date.
        assertTrue(examples.first { it.id == "C" }.want <= now)
        assertTrue(examples.first { it.id == "F" }.want <= now)
    }

    @Test fun playCountTable() {
        val added = ago(20); val played = ago(10)
        // Not kept: 0 plays → arrival + T2; 1 → max(arrival, play) + T2; ≥ 2 → last play + T3 (and never below arrival + T2).
        assertEquals(added + 3 * day, default.keepUntil(added, null, 0, null))
        assertEquals(played + 3 * day, default.keepUntil(added, played, 1, null))
        assertEquals(played + 14 * day, default.keepUntil(added, played, 2, null))
        assertEquals(played + 14 * day, default.keepUntil(added, played, 5, null))
        // Kept: also kept date + T1.
        val finite = CachePolicy(MONTH, THREE_DAYS, TWO_WEEKS)
        for (plays in listOf(0, 1, 2, 5)) {
            assertEquals("kept, $plays plays", never, default.keepUntil(added, played.takeIf { plays > 0 }, plays, added))
            assertEquals("kept 1m, $plays plays", added + 30 * day, finite.keepUntil(added, played.takeIf { plays > 0 }, plays, added))
        }
        // A play late in the kept time outlasts it (example G's shape).
        assertEquals(ago(1) + 14 * day, finite.keepUntil(added, ago(1), 2, added))
        // A CA-2 record with a play date but no count reads as heard once.
        assertEquals(played + 3 * day, default.keepUntil(added, played, 0, null))
    }

    @Test fun oneMonthThreeTimesIsCa2ForRecordsAsCa2WritesThem() {
        val ca2 = CachePolicy(MONTH, MONTH, MONTH)
        for (added in listOf(ago(40), ago(10), now)) for (played in listOf(null, ago(35), ago(5), now)) for (kept in listOf(null, added, added - 3 * day)) {
            val record = CachedTrack("s", "f", dev.coda.core.playback.StreamFormat.RAW, 1, added, played)
            assertEquals("added $added played $played kept $kept", maxOf(added, played ?: Long.MIN_VALUE) + 30 * day, ca2.keepUntil(record, kept))
        }
    }

    @Test fun aPlayNeverShortensALife() {
        val steps = Lifetime.entries
        for (once in steps) for (again in steps.filter { it >= once }) for (kept in listOf(null, ago(50))) for (count in 0..3) {
            val policy = CachePolicy(MAX, once, again).normalized()
            val added = ago(30)
            val last = if (count == 0) null else ago(20)
            val before = policy.keepUntil(added, last, count, kept)
            for (at in listOf(ago(20), ago(1), now)) {
                val after = policy.keepUntil(added, at, count + 1, kept)
                assertTrue("$once/$again kept=$kept count=$count play at $at", after >= before)
            }
            // A renewed arrival (Download whole queue) never shortens either.
            assertTrue(policy.keepUntil(now, last, count, kept) >= before)
        }
    }

    @Test fun normalizedOnlyRaises() {
        assertEquals(CachePolicy(MONTH, MONTH, MONTH), CachePolicy(WEEK, MONTH, THREE_DAYS).normalized())
        assertEquals(default, default.normalized())
        assertEquals(CachePolicy(YEAR, DAY, YEAR), CachePolicy(DAY, DAY, YEAR).normalized())
        for (k in Lifetime.entries) for (o in Lifetime.entries) for (a in Lifetime.entries) {
            val n = CachePolicy(k, o, a).normalized()
            assertTrue(n.heardOnce <= n.heardAgain && n.heardAgain <= n.kept)
            assertTrue(n.kept >= k && n.heardOnce == o && n.heardAgain >= a)
        }
    }

    @Test fun presetsMatchExactlyAndAnythingElseIsCustom() {
        assertEquals(StoragePreset.BALANCED, default.preset())
        assertEquals(StoragePreset.LESS_SPACE, CachePolicy(MAX, HALF_DAY, HALF_DAY).preset())
        assertEquals(StoragePreset.STREAM_LESS, CachePolicy(MAX, MAX, MAX).preset())
        // Keep free space is not part of the preset.
        assertEquals(StoragePreset.BALANCED, default.copy(keepFreeBytes = 10 * CachePolicy.GB).preset())
        assertNull(CachePolicy(THREE_MONTHS, THREE_DAYS, TWO_WEEKS).preset())
        // Custom chosen stays Custom even with a preset's times; not chosen follows the times.
        assertEquals(StoragePreset.BALANCED, default.selection(customChosen = false))
        assertNull(default.selection(customChosen = true))
        assertNull(CachePolicy(THREE_MONTHS, THREE_DAYS, TWO_WEEKS).selection(customChosen = false))
        assertNull(CachePolicy(MAX, DAY, THREE_DAYS).preset())
        for (preset in StoragePreset.entries) assertEquals(preset, default.with(preset).preset())
        StoragePreset.entries.forEach { assertTrue(it.heardOnce <= it.heardAgain && it.heardAgain <= it.kept) }
    }

    @Test fun storedKeysReadAsTheDefaultWhenMissingOrUnknownAndAreNormalized() {
        assertEquals(default, CachePolicy.fromKeys(null, null, null, null))
        assertEquals(default, CachePolicy.fromKeys("forever", "2d", "", 7))
        assertEquals(CachePolicy(MONTH, MONTH, MONTH), CachePolicy.fromKeys("1w", "1m", "3d", 5))
        assertEquals(CachePolicy(THREE_MONTHS, THREE_DAYS, TWO_WEEKS, 10 * CachePolicy.GB), CachePolicy.fromKeys("3m", "3d", "2w", 10))
    }
}
