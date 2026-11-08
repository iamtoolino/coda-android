package dev.coda.android.ui.library

import dev.coda.android.ui.home.HomeList
import org.junit.Assert.*
import org.junit.Test

class AlbumSortTest {
    @Test fun selectorUsesServerSemanticsAndMacLabels() {
        assertEquals(listOf("Title", "Recently Added", "Recent Releases", "Recently Played", "Most Played", "Top Rated", "Random"), AlbumSort.entries.map { it.label })
        assertEquals(listOf("alphabeticalByName", "newest", "byYear", "recent", "frequent", "highest", "random"), AlbumSort.entries.map { it.type.apiValue })
        HomeList.entries.filter { it.type != null }.forEach { assertEquals(it.type, AlbumSort.from(it.type!!).type) }
        assertEquals(listOf("Recently Added Albums", "Recent Releases", "Recently Played", "Playlists"), HomeList.entries.map { it.title })
    }
}
