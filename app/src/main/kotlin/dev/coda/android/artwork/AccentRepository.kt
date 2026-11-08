package dev.coda.android.artwork

import dev.coda.core.accent.AccentSampler
import dev.coda.core.accent.AccentSelection
import dev.coda.core.accent.AccentSelector
import dev.coda.core.accent.CgBoxDownsampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * G3 accents. Create one repository per server session, with a loader
 * bound to that session's artwork cache/fetcher; discard it when switching servers.
 * The loader receives stable identity, and resolves authentication only at fetch time.
 *
 * Owns the memory cache (the front, read on the main thread); OF-1a persists it in the account's `library.db`
 * ([store]): derived accents are written there, a memory miss reads there before decoding, and [preload] seeds the
 * memory cache at start, so a cold start or an offline cover shows the colour derived before.
 */
class AccentRepository(
    private val store: dev.coda.core.library.LibraryStore? = null,
    private val loadCover: suspend (coverId: String, size: Int) -> ByteArray,
) {
    private data class Key(val coverId: String, val size: Int)
    private val decoder = ReducedJpegDecoder()
    private val cache = object : LinkedHashMap<Key, AccentSelection>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, AccentSelection>?) = size > 512
    }
    private var generation = 0L

    suspend fun accent(coverArtId: String): AccentSelection = withContext(Dispatchers.Default) {
        val key = Key(coverArtId, 472)
        val started = synchronized(cache) {
            cache[key]?.let { return@withContext it }
            generation
        }
        // After the queued writes: a refresh's forget must land before this read.
        store?.let { it.awaitWrites(); it.accent(key.coverId, key.size) }?.let { saved ->
            synchronized(cache) { if (generation == started) cache[key] = saved }
            return@withContext saved
        }
        val decoded = decoder.decode(loadCover(key.coverId, key.size))
        coroutineContext.ensureActive()
        val accent = AccentSelector.select(AccentSampler.sample(
            CgBoxDownsampler.downsample(decoded.rgba, decoded.width, decoded.height)))
        coroutineContext.ensureActive()
        val current = synchronized(cache) {
            (generation == started).also { if (it) cache[key] = accent }
        }
        if (current) store?.saveAccent(key.coverId, key.size, accent)
        accent
    }

    /** Seeds the memory cache with the most recently derived accents of `library.db` (never over a newer one). */
    suspend fun preload() {
        val source = store ?: return
        val started = synchronized(cache) { generation }
        val saved = source.recentAccents(512)
        synchronized(cache) {
            if (generation != started) return
            // Oldest first, so the most recent end up most recently used in the LRU.
            for ((id, accent) in saved.asReversed()) { val key = Key(id.first, id.second); if (key !in cache) cache[key] = accent }
        }
    }

    /** The accent already in memory for [coverArtId], or null; never loads or decodes (safe on the main thread). */
    fun cached(coverArtId: String): AccentSelection? = synchronized(cache) { cache[Key(coverArtId, 472)] }

    fun invalidate(coverIds: Set<String>) {
        synchronized(cache) {
            generation++
            cache.keys.removeAll { it.coverId in coverIds }
        }
        store?.forgetAccents(coverIds)
    }

    /** Refresh/logout must not let an older in-flight request repopulate the cache. */
    fun clear() = synchronized(cache) {
        generation++
        cache.clear()
    }
}
