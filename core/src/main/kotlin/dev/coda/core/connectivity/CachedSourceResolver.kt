package dev.coda.core.connectivity

import dev.coda.core.cache.CacheFormatPolicy
import dev.coda.core.cache.TrackCache
import dev.coda.core.cache.songFormat
import dev.coda.core.playback.*

/**
 * "What plays", at engine hand-off. No disk or URL work: memory lookups only. A complete original plays first
 * on any network; an Opus copy plays unless the original can stream (Wi-Fi, server reachable), and anything else
 * streams in the format the cache would keep (network only).
 */
class CachedSourceResolver(
    private val cache: () -> TrackCache?,
    private val network: () -> NetworkState,
    private val serverReachable: () -> Boolean = { true },
) : SourceResolver {
    override fun resolve(entry: QueueEntry): TrackSource {
        if (entry.source is TrackSource.CachedFile) return entry.source
        val cache = cache()
        val network = network()
        val song = entry.songFormat()
        return when (val play = CacheFormatPolicy.play(cache?.kind(entry.songId), network, serverReachable(), song)) {
            // Leased at hand-off so nothing can delete the file before the engine opens it.
            is CacheFormatPolicy.Play.Cached -> cache?.acquire(entry.songId, play.kind)?.let { TrackSource.CachedFile(it.path) }
                ?: TrackSource.Remote(entry.songId, CacheFormatPolicy.fetchFormat(network, song))
            is CacheFormatPolicy.Play.Stream -> TrackSource.Remote(entry.songId, play.format)
        }
    }
}
