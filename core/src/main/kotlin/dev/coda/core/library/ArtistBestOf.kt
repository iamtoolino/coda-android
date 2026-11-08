package dev.coda.core.library

import dev.coda.core.subsonic.Album

/** Low ratings are explicit exclusions. Unrated releases are a fallback only. Stable ties keep input order. */
fun artistBestOf(albums: List<Album>): List<Album> {
    val rated = albums.filter { (it.userRating ?: 0) in 3..5 }
    return (rated.ifEmpty { albums.filter { (it.userRating ?: 0) == 0 } })
        .sortedWith(compareByDescending<Album> { it.userRating ?: 0 }
            .thenByDescending { it.playCount ?: 0 }
            .thenByDescending { it.releaseYear ?: Int.MIN_VALUE })
}
