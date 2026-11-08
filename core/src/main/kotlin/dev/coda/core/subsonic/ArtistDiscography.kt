package dev.coda.core.subsonic

import java.text.Collator
import java.util.Locale

/** PlaybackModels.swift chronologicalReleaseKey + NavidromeClient.artist title tie-break. */
object ArtistDiscography {
    fun isPrimaryRelease(album: Album, artist: Artist): Boolean = when {
        !album.artists.isNullOrEmpty() -> album.artists.any { it.id == artist.id }
        album.artistId != null -> album.artistId == artist.id
        album.artistName.isNotBlank() -> album.artistName.equals(artist.name, ignoreCase = true)
        else -> album.isCompilation != true
    }

    fun sorted(albums: List<Album>, newestFirst: Boolean = false): List<Album> {
        val titles = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }
        val dates = compareBy<Album>(
            { it.releaseYear ?: Int.MAX_VALUE },
            { it.originalReleaseDate?.month ?: it.releaseDate?.month ?: 13 },
            { it.originalReleaseDate?.day ?: it.releaseDate?.day ?: 32 })
        return albums.sortedWith { a, b ->
            // Unknown years remain last in either direction.
            if ((a.releaseYear == null) != (b.releaseYear == null)) {
                if (a.releaseYear == null) 1 else -1
            } else {
                val order = dates.compare(a, b).takeIf { it != 0 } ?: titles.compare(a.name, b.name)
                if (newestFirst && a.releaseYear != null) -order else order
            }
        }
    }
}

/** Retained by the screen model; both accent and Play newest use [newestRelease]. */
data class ArtistProjection(
    val oldest: List<Album> = emptyList(),
    val newest: List<Album> = emptyList(),
    val appearances: List<Album> = emptyList(),
) {
    val newestRelease: Album? get() = newest.firstOrNull()
    /** The artist sheet's Play and Add to queue take every release chronologically, oldest first (undated last). */
    val playOrder: List<Album> get() = oldest
    val oldestLayout = DiscographyLayout(oldest)
    val newestLayout = DiscographyLayout(newest)
    /** "Appears on" uses the same Timeline rows, so it follows the same year order. */
    private val appearancesOldest = DiscographyLayout(ArtistDiscography.sorted(appearances))
    private val appearancesNewest = DiscographyLayout(ArtistDiscography.sorted(appearances, true))
    fun layout(newestFirst: Boolean): DiscographyLayout = if (newestFirst) newestLayout else oldestLayout
    fun appearancesLayout(newestFirst: Boolean): DiscographyLayout = if (newestFirst) appearancesNewest else appearancesOldest

    companion object {
        fun from(page: ArtistPage, discovered: List<Album> = emptyList()): ArtistProjection {
            val primary = page.albums.filter { ArtistDiscography.isPrimaryRelease(it, page.artist) }
            val ids = primary.mapTo(HashSet()) { it.id }
            return ArtistProjection(ArtistDiscography.sorted(primary), ArtistDiscography.sorted(primary, true),
                (page.albums + discovered).filter { it.id !in ids }.distinctBy { it.id })
        }
    }
}

/**
 * Timeline (DS-15 "Compact years"): one row per release, the same for 1, few and many releases. Only the first
 * row of a year shows the year; undated releases (always last) show "–" once.
 */
data class DiscographyLayout(val albums: List<Album>) {
    /** The year label of each row, or null when the row above has the same year. */
    val yearLabels: List<String?> = albums.mapIndexed { i, album ->
        yearOf(album).takeIf { i == 0 || yearOf(albums[i - 1]) != it }
    }

    companion object {
        const val UNDATED = "–"
        fun yearOf(album: Album): String = album.releaseYear?.toString() ?: UNDATED
    }
}
