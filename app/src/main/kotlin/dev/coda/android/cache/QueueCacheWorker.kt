package dev.coda.android.cache

import dev.coda.core.cache.CacheFormatPolicy
import dev.coda.core.cache.TrackCache
import dev.coda.core.cache.TrackCachePlanner
import dev.coda.core.cache.TrackMeta
import dev.coda.core.cache.songFormat
import dev.coda.core.connectivity.NetworkState
import dev.coda.core.playback.QueueEntry
import dev.coda.core.playback.TrackSource
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * The read-ahead window (previous + current + next N, "Tracks cached ahead"). It only decides what to fetch
 * ahead; whatever is fetched stays in the one cache until its keep-until. The format follows the network at each
 * request (never free space); on Wi-Fi an Opus copy in the window is replaced by the original before it plays.
 * "Keep queue offline" is an add ([OfflineLibrary]), not part of the window.
 */
class QueueCacheWorker(
    private val prefetcher: Prefetcher,
    private val network: () -> NetworkState,
    private val ahead: () -> Int = { 4 },
    private val remote: (TrackSource.Remote) -> String,
) {
    private var lastOccurrence: UUID? = null
    private var lastSong: String? = null
    @Volatile private var previous: String? = null

    private fun window(queue: List<QueueEntry>, currentIndex: Int?): List<QueueEntry> {
        val ids = TrackCachePlanner.playbackWindow(queue.map { it.songId }, currentIndex, ahead(), previous)
        val bySong = queue.associateBy { it.songId }
        return ids.mapNotNull { bySong[it] }
    }

    /** Window songs to pin (memory, main thread). Only the main-thread pin owner records transitions. */
    @Synchronized fun pins(queue: List<QueueEntry>, currentIndex: Int?): List<String> {
        val current = currentIndex?.let { queue.getOrNull(it) }
        if (current != null && current.id != lastOccurrence) {
            previous = lastSong
            lastOccurrence = current.id
            lastSong = current.songId
        }
        return TrackCachePlanner.playbackWindow(queue.map { it.songId }, currentIndex, ahead(), previous)
    }

    /**
     * One pass over the window, in order (current first). Runs on the service's IO worker. [downloaded]: a song whose
     * copy this pass just stored (the controller then prepares a still-streaming successor from it).
     */
    suspend fun cycle(cache: TrackCache, queue: List<QueueEntry>, currentIndex: Int?,
                      downloaded: (String) -> Unit = {}, stillCurrent: () -> Boolean) {
        val window = window(queue, currentIndex)
        val ids = window.map { it.songId }.toSet()
        prefetcher.updateWindow(ids)
        for (entry in window) {
            if (!stillCurrent() || !network().available) break
            val song = entry.songFormat()
            val meta = TrackMeta.from(entry)
            cache.adoptLegacy(entry.songId, song, meta)
            // The network is read before each request; a cached copy is never fetched again or downgraded.
            val format = CacheFormatPolicy.download(cache.kind(entry.songId), network(), song) ?: continue
            val before = cache.kind(entry.songId)
            val result = prefetcher.fetchResult(cache, Prefetcher.Request(entry.songId, format, song, meta, protect = ids, window = true), remote)
            if (result.stored && cache.kind(entry.songId) != before) downloaded(entry.songId)
        }
    }

    companion object {
        /**
         * [changed] after every song any downloader writes to the signed-in session's cache (a "Load for offline"
         * or keep as well as this worker; [TrackCache.maintenanceEvents]), so the controller can prepare a still-streaming
         * successor from the new file ([dev.coda.core.playback.PlaybackController.cacheChanged]). Runs until cancelled.
         */
        suspend fun followPublishedCopies(libraries: kotlinx.coroutines.flow.Flow<OfflineLibrary?>, changed: () -> Unit) =
            libraries.map { it?.cache }.distinctUntilChanged().collectLatest { cache -> cache?.maintenanceEvents?.drop(1)?.collect { changed() } }
    }
}
