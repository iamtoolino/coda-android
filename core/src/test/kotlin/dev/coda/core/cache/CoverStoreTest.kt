package dev.coda.core.cache

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The account's one durable artwork store, next to the music. */
class CoverStoreTest : CacheFixture() {
    private val big = ByteArray(300) { 1 }
    private val small = ByteArray(40) { 2 }
    private fun meta(album: String) = TrackMeta("T", "A", album, albumId = album, coverArtId = album)
    private fun album(id: String, vararg tracks: String) = KeptItem("album/$id", tracks.map { KeptTrack(it, meta = meta(id)) }, id)

    private class Source(val pictures: Map<Pair<String, Int>, ByteArray>, val network: Map<Pair<String, Int>, ByteArray>) : CoverStore.Source {
        val fetched = mutableListOf<Pair<String, Int>>()
        override fun cached(id: String, size: Int) = pictures[id to size]
        override suspend fun fetch(id: String, size: Int): ByteArray? { fetched += id to size; return network[id to size] }
    }
    private fun CoverStore.fillKept(ids: Set<String>, source: CoverStore.Source, allowed: () -> Boolean, tried: MutableSet<String>, limit: Int = Int.MAX_VALUE) =
        runBlocking { fill(ids, emptySet(), source, allowed, { false }, tried, limit) }

    @Test fun lookupIsExactOrLargerAndTheOfflineFallbackIsSmallerButNeverABlur() {
        val store = CoverStore(File(root, "covers"))
        assertTrue(store.put("al-1", CoverStore.KEPT, big))
        // A 472 request is served from the 1440 copy (decoded down); no smaller copy exists for a fallback.
        assertEquals(300L, store.lookup("al-1", 472)!!.length())
        assertEquals(300L, store.lookup("al-1", 1440)!!.length())
        assertNull(store.fallback("al-1", 1440))
        assertTrue(store.put("al-2", CoverStore.SMALL, small))
        assertNull(store.lookup("al-2", 1440))
        assertEquals(40L, store.fallback("al-2", 1440)!!.length())
        // Every size the app asks for is stored as delivered (the 64 atmosphere copy, the 1024 session cover) ...
        assertTrue(store.put("al-3", 64, small)); assertTrue(store.put("al-3", 1024, big))
        assertEquals(300L, store.lookup("al-3", 472)!!.length())
        // ... but a 64 copy never stands in for a 1440 or 472 request offline.
        File(store.directory, "${CoverStore.base("al-3")}-1024").delete()
        assertNull(store.fallback("al-3", 472)); assertNull(store.fallback("al-3", 1440))
        // Keyed by the stable id and size only.
        assertEquals(setOf("${CoverStore.base("al-1")}-1440", "${CoverStore.base("al-2")}-472", "${CoverStore.base("al-3")}-64"),
            File(root, "covers").list()!!.toSet())
    }

    @Test fun aStoredCoverIsOneTheBrowseSizeCanUseWithoutTheNetwork() {
        // An offline playlist takes a song's cover only when a 288 tile can be drawn from the store.
        val store = CoverStore(File(root, "covers"))
        assertFalse(store.stored("al-1"))
        assertTrue(store.put("al-1", 64, small))
        assertFalse("a 64 copy is a blur at 288", store.stored("al-1"))
        assertTrue(store.put("al-2", CoverStore.SMALL, small)); assertTrue(store.stored("al-2"))
        assertTrue(store.put("al-3", CoverStore.KEPT, big)); assertTrue(store.stored("al-3"))
    }

    @Test fun keptCoversGetBothSizesFromThePictureCacheFirstThenOneFetchEachDuringAKeep() {
        val store = CoverStore(File(root, "covers"))
        val source = Source(mapOf(("a" to 1440) to big, ("a" to 472) to small, ("b" to 472) to small), mapOf(("b" to 1440) to big, ("c" to 472) to small))
        val tried = mutableSetOf<String>()
        store.fillKept(setOf("a", "b", "c"), source, allowed = { false }, tried = tried)
        assertTrue(store.has("a", 1440) && store.has("a", 472) && store.has("b", 472)); assertFalse(store.has("b", 1440))
        assertEquals(emptyList<Pair<String, Int>>(), source.fetched)
        store.fillKept(setOf("a", "b", "c"), source, allowed = { true }, tried = tried)
        store.fillKept(setOf("a", "b", "c"), source, allowed = { true }, tried = tried)
        assertTrue(store.has("b", 1440) && store.has("c", 472)); assertFalse(store.has("c", 1440))
        // Missing sizes are fetched once each, never again after a failure.
        assertEquals(listOf("b" to 1440, "c" to 472, "c" to 1440), source.fetched)
    }

    /** Played albums get 472 and 1440 only on Wi-Fi with the screen on; a keep fetches whatever the screen. */
    @Test fun playedAlbumsAreCompletedOnlyWithTheScreenOnAndKeptOnesDuringAKeep() = runBlocking {
        val store = CoverStore(File(root, "covers"))
        val network = listOf("k", "p").flatMap { id -> CoverStore.WANTED.map { (id to it) to big } }.toMap()
        val source = Source(emptyMap(), network)
        val tried = mutableSetOf<String>()
        var screenOn = false
        // Screen off on Wi-Fi during a keep: the kept album's covers come, the played album's do not.
        store.fill(setOf("k"), setOf("p", "k"), source, { true }, { screenOn }, tried)
        assertEquals(listOf("k" to 472, "k" to 1440), source.fetched)
        assertFalse(store.has("p", 472) || store.has("p", 1440))
        screenOn = true
        store.fill(setOf("k"), setOf("p", "k"), source, { true }, { screenOn }, tried)
        assertEquals(listOf("k" to 472, "k" to 1440, "p" to 472, "p" to 1440), source.fetched)
    }

    /** Nothing is evicted with its keep; copies of albums no keep names stay too; they count in the music size. */
    @Test fun coversAreNeverEvictedAndCountInTheMusicSize() {
        val cache = open()
        cache.renew(album("al-1", "t1"))
        cache.renew(KeptItem("playlist/p", listOf(KeptTrack("t3", meta = meta("al-2"))), coverArt = "pl-p"))
        assertEquals(setOf("al-1", "al-2", "pl-p"), cache.keptCoverIds())
        val music = cache.sizeBytes()
        assertTrue(cache.putCover("al-1", 1440, big)); assertTrue(cache.putCover("pl-p", 472, small))
        // Any album seen or played is stored, kept or not (an artist picture too).
        assertTrue(cache.putCover("al-9", 472, small)); assertTrue(cache.putCover("ar-1", 1440, big))
        assertEquals(music + 680, cache.sizeBytes())
        cache.deleteNow("album/al-1", listOf("t1"))
        cache.deleteNow("playlist/p", listOf("t3"))
        cache.clearPending()
        assertTrue(listOf("al-1" to 1440, "pl-p" to 472, "al-9" to 472, "ar-1" to 1440).all { (id, size) -> cache.covers.has(id, size) })
        assertEquals(music + 680, cache.sizeBytes())
    }

    @Test fun aRestartKeepsEveryCoverAndDropsOnlyTempFiles() {
        val cache = open()
        assertTrue(cache.putCover("al-1", 1440, big))
        cache.flush()
        File(root, "covers/${CoverStore.base("other")}-472").writeBytes(small)
        File(root, "covers/x.tmp").writeBytes(small)
        val again = open()
        assertTrue(again.covers.has("al-1", 1440) && again.covers.has("other", 472))
        assertFalse(File(root, "covers/x.tmp").exists())
        assertEquals(340L, again.covers.sizeBytes())
        // The covers folder is never taken for music by the restart's adoption pass.
        assertTrue(audioFiles().isEmpty())
    }

    /** Artwork counts like kept music: unkept songs give way to it; with only kept music left it is not stored. */
    @Test fun aCoverNeverPushesFreeSpaceBelowTheLineAndUnkeptSongsGiveWay() {
        val cache = open()
        cache.renew(album("al-1", "t1")); cache.put("t1", meta = meta("al-1"))
        space.setFree(policy.keepFreeBytes + 100)
        // Only kept music: nothing may go, the 1440 copy is refused, the small one fits.
        assertFalse(cache.putCover("al-1", 1440, big))
        assertTrue(cache.putCover("al-1", 472, small))
        assertTrue(cache.contains("t1"))
        // Music that isn't kept makes room for the cover.
        cache.put("heard", size = 1_000, meta = meta("al-2"))
        space.setFree(policy.keepFreeBytes + 100)
        assertTrue(cache.putCover("al-1", 1440, big))
        assertFalse(cache.contains("heard")); assertTrue(cache.contains("t1"))
    }

    /** The network rule is asked before every cover; a stop mid-fill stops the rest, and it resumes later. */
    @Test fun fillAsksBeforeEveryFetchAndStopsWhenWifiGoes() {
        val store = CoverStore(File(root, "covers"))
        var wifi = true
        val source = object : CoverStore.Source {
            val fetched = mutableListOf<String>()
            override fun cached(id: String, size: Int): ByteArray? = null
            // The first fetch loses Wi-Fi under way: its request fails, and it is tried again once Wi-Fi is back.
            override suspend fun fetch(id: String, size: Int): ByteArray? { fetched += "$id@$size"; wifi = false; return null }
        }
        val tried = mutableSetOf<String>()
        store.fillKept(linkedSetOf("a", "b", "c"), source, { wifi }, tried)
        assertEquals(listOf("a@472"), source.fetched)
        assertEquals(emptySet<String>(), tried)
        wifi = true
        store.fillKept(linkedSetOf("a", "b", "c"), source, { wifi }, tried)
        assertEquals(listOf("a@472", "a@472"), source.fetched)
    }

    @Test fun fillFetchesAtMostTheLimitPerCallAndSaysWhatIsLeft() {
        val store = CoverStore(File(root, "covers"))
        val ids = (1..3).mapTo(LinkedHashSet()) { "c$it" }
        val source = Source(emptyMap(), ids.flatMap { id -> CoverStore.WANTED.map { (id to it) to big } }.toMap())
        val tried = mutableSetOf<String>()
        assertTrue(store.fillKept(ids, source, { true }, tried, limit = 4))
        assertEquals(4, source.fetched.size)
        assertTrue(store.keptMissing(ids, tried))
        assertFalse(store.fillKept(ids, source, { true }, tried, limit = 4))
        assertEquals(6, source.fetched.size)
        assertTrue(ids.all { id -> CoverStore.WANTED.all { store.has(id, it) } })
        assertFalse(store.keptMissing(ids, tried))
    }

    /** A cancelled pass (the job stopped, Cancel) cancels the running fetch; nothing more is fetched or written. */
    @Test fun cancellingTheFillStopsTheRunningFetchAndTheRest() = runBlocking {
        val store = CoverStore(File(root, "covers"))
        val started = CompletableDeferred<Unit>()
        val fetched = mutableListOf<String>()
        var cancelled = false
        val source = object : CoverStore.Source {
            override fun cached(id: String, size: Int): ByteArray? = null
            override suspend fun fetch(id: String, size: Int): ByteArray? {
                fetched += id; started.complete(Unit)
                try { awaitCancellation() } finally { cancelled = true }
            }
        }
        val tried = mutableSetOf<String>()
        val job = launch(Dispatchers.Default) { store.fill(linkedSetOf("a", "b"), emptySet(), source, { true }, { true }, tried) }
        started.await(); job.cancelAndJoin()
        assertTrue(cancelled); assertEquals(listOf("a"), fetched)
        assertFalse(store.has("a", CoverStore.SMALL))
        // The cut-short copy is tried again by a later pass.
        assertEquals(emptySet<String>(), tried)
    }

    /** A refresh at one size drops the other sizes stored before it (never a stale copy afterwards). */
    @Test fun aRefreshDropsOlderSizesOfThatCoverButKeepsOnesWrittenSince() {
        val cache = open()
        assertTrue(cache.putCover("al-1", 1440, big)); assertTrue(cache.putCover("al-1", 472, small))
        File(root, "covers").listFiles()!!.forEach { it.setLastModified(1_000) }
        val since = 2_000L
        // The 472 refresh succeeded, the 1440 one failed: the old 1440 goes, so 1440 requests no longer get it.
        assertTrue(cache.refreshCover("al-1", 472, ByteArray(50) { 3 }, since))
        assertFalse(cache.covers.has("al-1", 1440)); assertEquals(50L, cache.covers.exact("al-1", 472)!!.length())
        assertEquals(50L, cache.covers.sizeBytes())
        // A later 1440 of the same refresh keeps the 472 written since.
        assertTrue(cache.refreshCover("al-1", 1440, big, since))
        assertTrue(cache.covers.has("al-1", 472)); assertEquals(350L, cache.covers.sizeBytes())
        // A refresh at another size (Home's 64) stores that size and leaves the others alone.
        assertTrue(cache.refreshCover("al-1", 64, small, Long.MAX_VALUE))
        assertTrue(cache.covers.has("al-1", 1440) && cache.covers.has("al-1", 472) && cache.covers.has("al-1", 64))
    }

    /** A small request takes the old picture cache's exact copy before decoding a larger stored one. */
    @Test fun theLookupBeforeTheNetworkIsStoreExactThenPictureCacheThenStoreLarger() {
        val store = CoverStore(File(root, "covers"))
        store.put("al-1", 1440, big); store.put("al-1", 472, small)
        val read = { f: File -> f.length() }
        assertEquals(40L, store.beforeNetwork("al-1", 472, read) { 7L })
        assertEquals(7L, store.beforeNetwork("al-1", 64, read) { 7L })
        assertEquals(40L, store.beforeNetwork("al-1", 64, read) { null })
        assertEquals(7L, store.beforeNetwork("al-9", 1440, read) { 7L })
        assertNull(store.beforeNetwork("al-9", 1440, read) { null })
    }

    /** The size total is memory only after the init: kept by writes, no folder listing. */
    @Test fun theCoverTotalFollowsWritesWithoutListing() {
        val cache = open()
        val base = cache.sizeBytes()
        assertTrue(cache.putCover("al-1", 1440, big)); assertTrue(cache.putCover("al-1", 1440, small))
        assertEquals(base + 40, cache.sizeBytes())
        // A file appearing behind the store's back is not seen: proof the read does not list the folder.
        File(root, "covers/${CoverStore.base("al-1")}-472").writeBytes(big)
        assertEquals(base + 40, cache.sizeBytes())
    }

    /** Revalidation: each copy keeps the server stamp it was fetched under, across a restart. */
    @Test fun aCopyFetchedUnderAnotherStampIsStaleAndAnUnknownStampIsAdopted() {
        val cache = open()
        assertTrue(cache.putCover("al-1", 472, small, stamp = "al-al-1_1"))
        assertFalse(cache.covers.stale("al-1", 472, "al-al-1_1"))
        assertFalse("nothing reported: no revalidation", cache.covers.stale("al-1", 472, null))
        assertTrue(cache.covers.stale("al-1", 472, "al-al-1_2"))
        // A copy with no stamp (migrated, kept) takes the reported one and is not downloaded again.
        assertTrue(cache.putCover("al-2", 472, small))
        assertFalse(cache.covers.stale("al-2", 472, "al-al-2_1"))
        assertTrue(cache.covers.stale("al-2", 472, "al-al-2_2"))
        // A refetch records the new stamp; it all survives a restart.
        assertTrue(cache.putCover("al-1", 472, big, stamp = "al-al-1_2"))
        val again = open()
        assertFalse(again.covers.stale("al-1", 472, "al-al-1_2"))
        assertEquals("al-al-2_1", again.covers.stamp("al-2", 472))
        assertFalse("no copy, nothing stale", again.covers.stale("al-3", 472, "x"))
    }

    /** Playing a track marks its album (memory, then the flush writes it); the mark survives a restart. */
    @Test fun aPlayMarksItsAlbumForBothSizesAndTheMarkIsKept() {
        val cache = open()
        cache.put("t1", meta = meta("al-1"))
        cache.markPlayed("t1")
        cache.markPlayed("t2", meta("al-2")) // not on the phone yet (an early play)
        assertEquals(setOf("al-1", "al-2"), cache.covers.played())
        cache.flush()
        assertEquals(setOf("al-1", "al-2"), open().covers.played())
    }

    /** An old picture-cache copy moves in without a download. */
    @Test fun oldCopiesMoveInWithoutADownload() {
        val store = CoverStore(File(root, "covers")).also { it.open() }
        val pictures = File(root, "pictures").apply { mkdirs() }
        val old = File(pictures, "x").apply { writeBytes(big) }
        assertTrue(store.adopt("al-1", 472, old))
        assertFalse(old.exists()); assertEquals(300L, store.exact("al-1", 472)!!.length()); assertEquals(300L, store.sizeBytes())
        // The store's own copy wins over an old one; the old file goes.
        val older = File(pictures, "y").apply { writeBytes(small) }
        assertTrue(store.adopt("al-1", 472, older)); assertFalse(older.exists()); assertEquals(300L, store.exact("al-1", 472)!!.length())
    }

    /** The account wipe deletes the folder; a retired cache writes no copy and no index line afterwards. */
    @Test fun aRetiredCacheWritesNoCoverAndNoIndex() {
        val cache = open()
        assertTrue(cache.putCover("al-1", 472, small, stamp = "s1"))
        cache.retire()
        root.deleteRecursively()
        assertFalse(cache.putCover("al-2", 472, small, stamp = "s2"))
        cache.markPlayed("t1", meta("al-3")); cache.flush()
        assertFalse(cache.covers.adopt("al-4", 472, File(temporary.root, "none")))
        assertFalse(root.exists())
    }

    /** On ART-1: a cover asks only for its own bytes, with maintenance's allowance for the read-ahead window. */
    @Test fun aCoverAsksOnlyForItsOwnBytesAndTheWindowMaySitBelowTheLine() {
        val cache = open()
        cache.put("heard1", size = 1_000, meta = meta("al-2")); cache.put("heard2", size = 1_000, meta = meta("al-3"))
        // Short of the line by far more than one cover: that is maintenance's shortfall. Nothing goes, the cover is refused.
        space.setFree(policy.keepFreeBytes - 5_000)
        assertFalse(cache.putCover("al-1", 1440, big))
        assertTrue(cache.contains("heard1") && cache.contains("heard2"))
        // The read-ahead window's copy may sit below the line: with it taken out the cover fits, nothing is deleted.
        cache.put("next", size = 10_000, meta = meta("al-4")); cache.pin(listOf("next"))
        space.setFree(policy.keepFreeBytes - 5_000)
        assertTrue(cache.putCover("al-1", 1440, big))
        assertTrue(cache.contains("heard1") && cache.contains("heard2") && cache.contains("next"))
        // Short by less than the cover: one unkept song gives way, never all of them.
        cache.pin(emptyList())
        space.setFree(policy.keepFreeBytes + 100)
        assertTrue(cache.putCover("al-5", 1440, big))
        assertEquals(2, listOf("heard1", "heard2", "next").count(cache::contains))
    }

    /** On ART-1: a changed cover the store refuses is neither served stale nor fetched again on every display. */
    @Test fun aChangedCoverTheStoreRefusesIsNotServedStaleNorFetchedAgain() {
        val cache = open(); val store = cache.covers
        assertTrue(cache.putCover("al-1", 472, small, stamp = "s1"))
        assertTrue(store.stale("al-1", 472, "s2"))
        // Far below the line with nothing that can give way: the store refuses the new bytes.
        space.setFree(policy.keepFreeBytes - 5_000)
        assertFalse(cache.putCover("al-1", 472, big, stamp = "s2"))
        // The stale copy went and the new stamp is recorded: the next display does not fetch it again ...
        assertNull(store.exact("al-1", 472)); assertFalse(store.stale("al-1", 472, "s2"))
        // ... and the copy the fetcher wrote to the old picture cache instead moves in as fresh.
        val picture = File(temporary.root, "picture").apply { writeBytes(big) }
        assertTrue(store.adopt("al-1", 472, picture))
        assertFalse(store.stale("al-1", 472, "s2")); assertEquals("s2", store.stamp("al-1", 472))
        // A refused write of an unchanged cover keeps its copy.
        space.setFree(policy.keepFreeBytes + 100)
        assertTrue(cache.putCover("al-6", 472, small, stamp = "t1"))
        assertFalse(cache.putCover("al-6", 472, big, stamp = "t1"))
        assertEquals(40L, store.exact("al-6", 472)!!.length())
    }


    /** The cover index is rows in library.db (no `index` file) and comes back after a restart. */
    @Test fun theCoverIndexRoundTripsThroughTheDatabase() {
        val cache = open()
        assertTrue(cache.putCover("al-1", 472, small, stamp = "s1"))
        cache.markPlayed("t1", meta("al-2")); cache.flush()
        assertEquals(listOf("${CoverStore.base("al-1")}-472"), cache.covers.directory.list()!!.toList())
        val rows = runBlocking { dev.coda.core.testing.TestCacheIndex.store(root).use(emptyList<String>()) { db ->
            db.covers().stamps().map { "${it.name}=${it.stamp}" } + db.covers().played() } }
        assertEquals(listOf("${CoverStore.base("al-1")}-472=s1", "al-2"), rows)
        val again = open()
        assertEquals("s1", again.covers.stamp("al-1", 472))
        assertEquals(setOf("al-2"), again.covers.played())
    }

    /** DB-2, the rule: the stamp row is written before the copy gets its final name. */
    @Test fun theStampRowIsWrittenBeforeTheCopyGetsItsName() {
        val directory = File(root, "covers")
        val seen = mutableListOf<Boolean>()
        val index = object : CoverIndex {
            override fun load() = CoverIndex.Saved()
            override fun saveStamp(name: String, stamp: String) { seen += File(directory, name).exists() }
            override fun savePlayed(ids: Collection<String>) {}
        }
        val store = CoverStore(directory, index)
        assertTrue(store.put("al-1", 472, small, stamp = "s1"))
        assertTrue(store.put("al-1", 472, big, stamp = "s2"))
        assertEquals("first copy: no file yet; a replacement: the old copy still under its name", listOf(false, true), seen)
        assertEquals(300L, store.exact("al-1", 472)!!.length())
    }

    /** A refresh that runs after the cache was retired writes nothing and deletes no other size either. */
    @Test fun aRefreshAfterRetirementDeletesNoStaleSize() {
        var retired = false
        val store = CoverStore(File(root, "covers"), retired = { retired })
        assertTrue(store.put("al-1", CoverStore.SMALL, small)); assertTrue(store.put("al-1", CoverStore.KEPT, big))
        val before = File(root, "covers").listFiles()!!.map { it.name }.toSet()
        retired = true
        assertFalse(store.refresh("al-1", CoverStore.SMALL, small, since = System.currentTimeMillis() + 60_000))
        assertEquals(before, File(root, "covers").listFiles()!!.map { it.name }.toSet())
    }
}
