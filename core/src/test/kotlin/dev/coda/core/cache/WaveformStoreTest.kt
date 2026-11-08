package dev.coda.core.cache

import dev.coda.core.playback.StreamFormat.OPUS_128
import dev.coda.core.playback.StreamFormat.RAW
import dev.coda.core.testing.MemoryWaveformRows
import dev.coda.core.testing.TestCacheIndex
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WaveformStoreTest : CacheFixture() {
    private val peaks = List(200) { it / 200f }

    /** The rows of the cache on [root] in its real library.db. */
    private fun dbRows() = WaveformDb { TestCacheIndex.store(root) }

    @Test fun oneWaveformPerSongPublishedOnlyWithCompleteAudio() {
        val cache = open()
        assertFalse("No audio yet", cache.storeWaveform("s", peaks))
        cache.put("s", OPUS_128, type = "audio/ogg")
        assertTrue(cache.storeWaveform(TrackKey("s", OPUS_128), peaks))
        assertEquals(peaks, cache.waveform("s"))
        assertEquals(peaks, cache.waveform(TrackKey("s", RAW)))
    }

    @Test fun storedAsOneRowWithTheOldBlobAndItsDateAndReadBackAfterARestart() {
        val cache = open()
        cache.put("s")
        assertTrue(cache.storeWaveform("s", peaks))
        val blob = dbRows().read(listOf("s"))!!.getValue("s")
        assertEquals(808, blob.size)
        assertEquals(peaks, WaveformStore.decode(blob))
        assertEquals(mapOf("s" to wall), dbRows().dates())
        assertFalse("no waveform files", File(root, "waveforms").exists())
        val reopened = open()
        assertTrue(reopened.waveforms.has("s")); assertEquals(wall, reopened.waveforms.ageDate("s"))
        assertEquals("the newest are in memory after a start", peaks, reopened.waveform("s"))
    }

    @Test fun noRecomputeWhenOpusIsReplacedByTheOriginal() {
        val cache = open()
        cache.put("s", OPUS_128, type = "audio/ogg")
        cache.storeWaveform("s", peaks)
        cache.put("s", RAW)
        assertTrue(cache.waveforms.has("s")); assertEquals(peaks, cache.waveform("s"))
        assertEquals(setOf("s"), open().let { dbRows().dates()!!.keys })
    }

    @Test fun waveformsSurviveDeleteNowEvictionAndExpiryOfTheirAudio() {
        val cache = open()
        listOf("deleted", "evicted", "expired").forEach { cache.put(it); cache.storeWaveform(it, peaks) }
        cache.deleteNow(null, listOf("deleted"))
        space.setFree(RESERVE - 1)
        cache.housekeep()
        wall += 31 * day
        space.setFree(64 * GiB)
        cache.housekeep()
        assertTrue(audioFiles().isEmpty())
        for (id in listOf("deleted", "evicted", "expired")) assertEquals(peaks, cache.waveform(id))
        assertEquals(setOf("deleted", "evicted", "expired"), dbRows().dates()!!.keys)
        // A re-streamed track shows its waveform at once after a restart too.
        val reopened = open()
        reopened.waveforms.load(listOf("evicted"))
        assertEquals(peaks, reopened.waveform("evicted"))
    }

    @Test fun waveformsAgeOutAfter365DaysAndPlayingRenewsThem() {
        val cache = open()
        cache.put("played"); cache.put("idle")
        cache.storeWaveform("played", peaks); cache.storeWaveform("idle", peaks)
        wall += 200 * day
        cache.markPlayed("played")
        wall += 165 * day
        cache.housekeep()
        assertFalse(cache.waveforms.has("idle")); assertTrue(cache.waveforms.has("played"))
        assertEquals(setOf("played"), dbRows().dates()!!.keys)
        // The renewed date is in the row and survives a restart.
        val reopened = open()
        wall += 199 * day
        reopened.housekeep(); assertTrue(reopened.waveforms.has("played"))
        wall += day
        reopened.housekeep(); assertFalse(reopened.waveforms.has("played"))
        assertTrue(dbRows().dates()!!.isEmpty())
    }

    @Test fun theAgeAndCapRulesAreQueriesExpiredFirstThenTheOldestBeyondTheCap() {
        val rows = dbRows()
        val blob = WaveformStore.encode(peaks)
        val now = 1_000 * day
        // Two expired (at or past 365 days), five recent: cap 3 keeps the three newest.
        rows.write("old1", blob, now - 400 * day); rows.write("old2", blob, now - 365 * day)
        listOf("a", "b", "c", "d", "e").forEachIndexed { i, id -> rows.write(id, blob, now - (10 - i) * day) }
        val dropped = rows.maintain(emptyMap(), now - CacheRules.WAVEFORM_AGE_LIMIT_MS, cap = 3)
        assertEquals(setOf("old1", "old2", "a", "b"), dropped)
        assertEquals(setOf("c", "d", "e"), rows.dates()!!.keys)
        // Under the cap and nothing expired: nothing goes; a renewal never moves a date back.
        assertEquals(emptySet<String>(), rows.maintain(mapOf("e" to 0L, "c" to now), now - CacheRules.WAVEFORM_AGE_LIMIT_MS, cap = 3))
        assertEquals(now - 6 * day, rows.dates()!!["e"]); assertEquals(now, rows.dates()!!["c"])
    }

    @Test fun theStoreIsCappedOldestFirst() {
        var now = 0L
        val store = WaveformStore(dbRows(), { now }, cap = 3)
        for (id in listOf("a", "b", "c", "d", "e")) { now += 1_000; store.store(id, peaks) }
        store.maintain()
        assertEquals(3, store.size)
        assertFalse(store.has("a")); assertFalse(store.has("b")); assertTrue(store.has("e"))
        assertEquals(setOf("c", "d", "e"), dbRows().dates()!!.keys)
    }

    @Test fun maintainWritesRenewalsAndBothRulesInOneTransaction() {
        var now = 0L
        val rows = MemoryWaveformRows()
        val store = WaveformStore(rows, { now }, cap = 2)
        listOf("a", "b", "c").forEach { now += 1_000; store.store(it, peaks) }
        now += 1_000; store.touch("a"); store.touch("b")
        store.maintain()
        assertEquals("one call carries the renewals and both rules", 1, rows.maintainCalls)
        assertEquals(setOf("a", "b"), rows.dates().keys)
        // A failing transaction changes nothing in memory either; the renewals stay pending.
        val failing = object : WaveformRows by rows {
            override fun maintain(renewed: Map<String, Long>, cutoff: Long, cap: Int): Set<String> = throw java.io.IOException("full")
        }
        val other = WaveformStore(failing, { now }, cap = 1)
        other.touch("a"); other.maintain()
        assertTrue(other.has("a") && other.has("b")); assertTrue(other.maintenanceDue())
    }

    @Test fun corruptRowsAreDroppedAndComputedAgain() {
        val rows = dbRows()
        val store = WaveformStore(rows, { 0L })
        store.store("ok", peaks)
        rows.write("bad", byteArrayOf(1, 2, 3), 0)
        val reopened = WaveformStore(rows, { 0L })
        reopened.load(listOf("ok", "bad"))
        assertEquals(peaks, reopened.peaks("ok"))
        assertNull(reopened.peaks("bad")); assertFalse(reopened.has("bad"))
        assertEquals(setOf("ok"), rows.dates()!!.keys)
        assertTrue(reopened.store("bad", peaks))
    }

    @Test fun onlyRecentWaveformsAreInMemoryAfterStartTheRestLoadOnRequest() {
        var now = 0L
        val store = WaveformStore(dbRows(), { now })
        repeat(40) { now += 1_000; store.store("s$it", peaks) }
        val reopened = WaveformStore(dbRows(), { now })
        assertEquals(peaks, reopened.peaks("s39"))
        assertNull(reopened.peaks("s0"))
        reopened.load(listOf("s0"))
        assertEquals(peaks, reopened.peaks("s0"))
    }

    @Test fun aRetiredStoreWritesNothing() {
        var retired = false
        val rows = MemoryWaveformRows()
        val store = WaveformStore(rows, { 0L }, retired = { retired })
        store.store("a", peaks)
        retired = true
        assertFalse(store.store("b", peaks))
        store.touch("a"); store.maintain()
        assertEquals(0, rows.maintainCalls)
    }
}
