package dev.coda.core.cache

import dev.coda.core.playback.QueueEntry
import dev.coda.core.playback.ReplayGainMetadata
import dev.coda.core.subsonic.Song
import dev.coda.core.testing.TestCacheIndex
import dev.coda.store.CacheStateRow
import dev.coda.store.CacheTrackRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** The ReplayGain of each cached song lives on its cache row (TrackMeta), so offline playback never loses it. */
class ReplayGainCacheTest : CacheFixture() {
    private val rg = ReplayGainMetadata(trackGain = -4.5, albumGain = -6.25, trackPeak = 0.91, albumPeak = 0.98)

    @Test fun trackMetaTakesTheGainFromTheSongAndTheQueueEntry() {
        assertEquals(rg, TrackMeta.from(Song("s", "S", replayGain = rg)).replayGain)
        assertEquals(rg, TrackMeta.from(QueueEntry("s", replayGain = rg)).replayGain)
        assertEquals(rg, KeptTrack.from(QueueEntry("s", replayGain = rg)).meta!!.replayGain)
        assertNull(TrackMeta.from(Song("s", "S")).replayGain)
    }

    @Test fun theGainRoundTripsThroughTheCacheRows() {
        val meta = TrackMeta("T", "A", "B", "al-1", replayGain = rg)
        open().apply { put("t1", meta = meta); flush() }
        assertEquals(meta, open().record("t1")!!.meta)
    }

    /** Rows written by pre3 have no gain in their meta JSON: they still decode (no clear data), with no gain. */
    @Test fun anOldMetaWithoutGainStillDecodes() = runBlocking {
        TestCacheIndex.store(root).transact { d ->
            d.cache().upsertState(CacheStateRow(0, "old", null, null))
            d.cache().upsertTracks(listOf(CacheTrackRow("old", "f.orig", "RAW", 4, 1, 2, 3, "audio/flac", """{"suffix":"flac"}""",
                """{"title":"T","artist":"A","album":"B","albumId":"al-1","coverArtId":"c","durationSeconds":100,"track":2,"disc":1,"year":2001,"albumArtist":"A"}""")))
        }
        val meta = CacheIndexDb({}) { TestCacheIndex.store(root) }.load("me")!!.tracks.getValue("old").meta!!
        assertEquals(TrackMeta("T", "A", "B", "al-1", "c", 100, 2, 1, 2001, "A"), meta)
        assertNull(meta.replayGain)
    }

    @Test fun playingWithAFullerSongUpdatesTheGainAndALesserOneKeepsIt() {
        val cache = open()
        cache.put("p", meta = TrackMeta("T"))
        cache.markPlayed("p", TrackMeta("T", replayGain = rg))
        assertEquals(rg, cache.record("p")!!.meta!!.replayGain)
        cache.markPlayed("p", TrackMeta("T2"))
        assertEquals("T2", cache.record("p")!!.meta!!.title)
        assertEquals("an entry without gain never drops the stored one", rg, cache.record("p")!!.meta!!.replayGain)
        val newer = rg.copy(trackGain = -2.0)
        cache.markPlayed("p", TrackMeta("T2", replayGain = newer))
        assertEquals(newer, cache.record("p")!!.meta!!.replayGain)
    }

    @Test fun keepingWithAFullerSongUpdatesTheGainAndALesserOneKeepsIt() {
        val cache = open()
        cache.put("k", meta = TrackMeta("T"))
        cache.renew(KeptItem("album/al", listOf(KeptTrack("k", meta = TrackMeta("T", replayGain = rg)))))
        assertEquals(rg, cache.record("k")!!.meta!!.replayGain)
        cache.renew(KeptItem("album/al", listOf(KeptTrack("k", meta = TrackMeta("T")))))
        assertEquals(rg, cache.record("k")!!.meta!!.replayGain)
    }

    @Test fun aDownloadCommittedWithoutGainKeepsTheStoredOne() {
        val cache = open()
        cache.put("d", dev.coda.core.playback.StreamFormat.OPUS_128, meta = TrackMeta("T", replayGain = rg))
        cache.put("d", meta = TrackMeta("T")) // the original replaces the Opus copy
        assertEquals(rg, cache.record("d")!!.meta!!.replayGain)
    }
}
