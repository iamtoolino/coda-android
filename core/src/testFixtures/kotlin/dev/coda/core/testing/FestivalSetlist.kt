package dev.coda.core.testing

import dev.coda.core.subsonic.Song

/** Authored setlist: one fictional artist, pairs, triples and singles from several albums. */
object FestivalSetlist {
    val songs: List<Song> = listOf("a", "a", "b", "c", "c", "c", "d", "a", "b", "b", "e", "e", "e", "d", "c")
        .mapIndexed { index, album -> Song("set-$index", "Lantern ${index + 1}", albumId = album,
            album = "Chapter ${album.uppercase()}", artist = "Glass Harbour", coverArt = "album-2", duration = 190) }
}
