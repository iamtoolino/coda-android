package dev.coda.android.ui.library

import dev.coda.core.subsonic.*

internal data class SearchPlayback(val songs: List<Song>, val startIndex: Int)

/** LibraryViews.swift:363: preserve the canonical album queue, including preceding tracks. */
internal suspend fun resolveSearchPlayback(client: SubsonicClient, song: Song,
    /** Keeps the fetched album page (the catalogue can then complete a minimal entry from it). */
    saveAlbum: suspend (AlbumPage) -> Unit = {},
    savedAlbum: suspend (String) -> AlbumPage? = { null }): SearchPlayback {
    val albumId = song.albumId
    if (albumId.isNullOrEmpty()) return SearchPlayback(listOf(song), 0)
    // Without the server, a result found on this phone still queues its saved album (cached tracks play offline).
    val page = try { client.getAlbum(albumId).also { saveAlbum(it) } } catch (e: SubsonicError) {
        if (e is SubsonicError.Network || e is SubsonicError.Http && e.status >= 500) savedAlbum(albumId) ?: throw e else throw e
    }
    return SearchPlayback(page.songs, page.songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0))
}
