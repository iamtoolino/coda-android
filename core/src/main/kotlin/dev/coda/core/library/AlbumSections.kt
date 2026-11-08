package dev.coda.core.library

import dev.coda.core.subsonic.Song

/** One entry of an album's track list: a disc heading (multi-disc albums only) or a track row. */
sealed interface AlbumRow {
    data class Disc(val disc: Int, val title: String?, val trackCount: Int, val seconds: Int) : AlbumRow {
        /** Spec 5.2 header: "DISC 1 · 7 TRACKS · 43 MIN". */
        val label: String get() = "DISC $disc · $trackCount ${if (trackCount == 1) "TRACK" else "TRACKS"} · ${(seconds / 60).coerceAtLeast(if (seconds > 0) 1 else 0)} MIN"
    }
    data class Track(val index: Int, val song: Song) : AlbumRow
}

object AlbumSections {
    private fun discOf(song: Song) = maxOf(1, song.discNumber ?: 1)

    fun discCount(songs: List<Song>): Int = songs.map(::discOf).distinct().size

    /** [songs] in canonical order (disc, then track). A single-disc album gets no heading at all. */
    fun rows(songs: List<Song>): List<AlbumRow> {
        if (discCount(songs) < 2) return songs.mapIndexed { index, song -> AlbumRow.Track(index, song) }
        val rows = mutableListOf<AlbumRow>()
        var start = 0
        while (start < songs.size) {
            val disc = discOf(songs[start])
            var end = start
            while (end < songs.size && discOf(songs[end]) == disc) end++
            val group = songs.subList(start, end)
            rows += AlbumRow.Disc(disc, group.firstNotNullOfOrNull { it.discSubtitle?.takeIf(String::isNotBlank) },
                group.size, group.sumOf { it.duration ?: 0 })
            group.forEachIndexed { offset, song -> rows += AlbumRow.Track(start + offset, song) }
            start = end
        }
        return rows
    }

    /** "2 h 20 min" for long albums, "43 min" otherwise (rounded down, at least 1 min). */
    fun duration(seconds: Int): String {
        val minutes = (seconds / 60).coerceAtLeast(1)
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }
}
