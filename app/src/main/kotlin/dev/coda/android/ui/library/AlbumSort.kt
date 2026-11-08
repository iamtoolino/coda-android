package dev.coda.android.ui.library

import dev.coda.core.library.AlbumSectionKind
import dev.coda.core.subsonic.AlbumListType

/** macOS AlbumCollectionKind labels; Random is the additional phone feedback option. [sections]: DS-9 per-sort sections. */
enum class AlbumSort(val label: String, val type: AlbumListType, val sections: AlbumSectionKind) {
    TITLE("Title", AlbumListType.ALPHABETICAL, AlbumSectionKind.TITLE),
    ADDED("Recently Added", AlbumListType.NEWEST, AlbumSectionKind.ADDED),
    RELEASED("Recent Releases", AlbumListType.RECENT_RELEASES, AlbumSectionKind.RELEASED),
    PLAYED("Recently Played", AlbumListType.RECENTLY_PLAYED, AlbumSectionKind.PLAYED),
    FREQUENT("Most Played", AlbumListType.MOST_PLAYED, AlbumSectionKind.FREQUENT),
    RATED("Top Rated", AlbumListType.TOP_RATED, AlbumSectionKind.RATED),
    RANDOM("Random", AlbumListType.RANDOM, AlbumSectionKind.NONE);
    companion object { fun from(type: AlbumListType) = entries.first { it.type == type } }
}
