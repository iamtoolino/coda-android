package dev.coda.android.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The artist may take at most this share of the free width when the album also needs room. */
internal const val ArtistShareCap = .55f

/**
 * Widths for the "Artist · Album" line (one line each, ellipsis, never wrap) in [available] px
 * after the separator. Both names at their natural width when they fit. Otherwise the artist takes its natural width up
 * to [ArtistShareCap], the album gets all the rest, and a very long artist may use what a short album doesn't need.
 */
internal fun artistAlbumWidths(artist: Int, album: Int, available: Int): Pair<Int, Int> {
    if (artist + album <= available) return artist to album
    val cap = (available * ArtistShareCap).roundToInt()
    val artistWidth = min(artist, max(cap, available - album))
    return artistWidth to min(album, available - artistWidth)
}

/** Lays out exactly three children: the artist text, the separator and the album text. Texts must be `maxLines = 1`. */
@Composable
internal fun ArtistAlbumLayout(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val (artist, dot, album) = measurables
        // The separator is bounded by the parent too: in a zero-width or very narrow row nothing is placed outside it.
        val dotMax = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        val dotPlaceable = dot.measure(Constraints(maxWidth = dotMax, maxHeight = constraints.maxHeight))
        val available = if (constraints.hasBoundedWidth) max(0, constraints.maxWidth - dotPlaceable.width) else Int.MAX_VALUE / 4
        val (artistWidth, albumWidth) = artistAlbumWidths(
            artist.maxIntrinsicWidth(Constraints.Infinity), album.maxIntrinsicWidth(Constraints.Infinity), available)
        val artistPlaceable = artist.measure(Constraints(maxWidth = artistWidth, maxHeight = constraints.maxHeight))
        val albumPlaceable = album.measure(Constraints(maxWidth = albumWidth, maxHeight = constraints.maxHeight))
        val height = maxOf(artistPlaceable.height, dotPlaceable.height, albumPlaceable.height)
        val width = (artistPlaceable.width + dotPlaceable.width + albumPlaceable.width).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, height) {
            artistPlaceable.placeRelative(0, 0)
            dotPlaceable.placeRelative(artistPlaceable.width, 0)
            albumPlaceable.placeRelative(artistPlaceable.width + dotPlaceable.width, 0)
        }
    }
}
