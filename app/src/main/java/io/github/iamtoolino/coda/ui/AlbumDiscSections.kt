package io.github.iamtoolino.coda.ui

import io.github.iamtoolino.coda.data.Song

internal data class AlbumDiscSection(
    val number: Int,
    val songs: List<IndexedValue<Song>>,
) {
    val duration: Int = songs.sumOf { it.value.duration }
}

internal fun albumDiscSections(songs: List<Song>): List<AlbumDiscSection> {
    if (songs.isEmpty()) return emptyList()
    val sections = linkedMapOf<Int, MutableList<IndexedValue<Song>>>()
    songs.forEachIndexed { index, song ->
        val discNumber = song.discNumber?.takeIf { it > 0 } ?: 1
        sections.getOrPut(discNumber, ::mutableListOf) += IndexedValue(index, song)
    }
    return sections.map { (number, entries) -> AlbumDiscSection(number, entries) }
}

internal fun shouldShowDiscHeaders(sections: List<AlbumDiscSection>): Boolean =
    sections.size > 1 || sections.any { it.number > 1 }
