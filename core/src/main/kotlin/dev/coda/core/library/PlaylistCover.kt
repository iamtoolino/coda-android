package dev.coda.core.library

import dev.coda.core.subsonic.Song

/**
 * An offline playlist's cover, the same on the phone (Playlists tab, Home, the page header) and in
 * the car. Its own cover when the artwork store has it, else the album cover of its first song (playlist order) that
 * the store has, else null (Coda's placeholder). Only stored covers ([stored]: the store, keyed by album id per
 * OF-1c3): never a fetch or a download.
 */
object PlaylistCover {
    fun offline(own: String?, songs: Sequence<Song>, stored: (String) -> Boolean): String? =
        own?.takeIf(stored) ?: storedSongCovers(songs, stored).firstOrNull()

    /** The stored album covers of [songs] in playlist order, each once: the page header's mosaic offline. */
    fun storedSongCovers(songs: Sequence<Song>, stored: (String) -> Boolean): Sequence<String> =
        songs.mapNotNull { it.albumArtworkId }.distinct().filter(stored)
}
