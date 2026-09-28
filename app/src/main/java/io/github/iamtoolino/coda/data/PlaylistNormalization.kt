package io.github.iamtoolino.coda.data

internal const val NORMALIZED_STREAM_FORMAT = "coda-normalized-v1"

/** Only this explicit playlist directive opts a queue occurrence in. */
internal fun Playlist.withPlaybackPolicy(): Playlist {
    val normalized = comment?.contains("[coda:replaygain=track]") == true
    return copy(entry = entry.map { it.copy(normalizedStream = normalized) })
}
