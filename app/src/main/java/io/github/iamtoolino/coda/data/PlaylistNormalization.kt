package io.github.iamtoolino.coda.data

internal const val NORMALIZED_STREAM_FORMAT = "coda-normalized-v1"

/**
 * Personal server integration, deliberately absent from product settings and setup docs.
 * The explicit directive selects a separately provisioned transcoder per queue occurrence.
 * Keep the versioned format distinct from ordinary Opus for server and local cache identity.
 */
internal fun Playlist.withPlaybackPolicy(): Playlist {
    val normalized = comment?.contains("[coda:replaygain=track]") == true
    return copy(entry = entry.map { it.copy(normalizedStream = normalized) })
}
