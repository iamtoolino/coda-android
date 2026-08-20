package io.github.iamtoolino.coda.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.iamtoolino.coda.AppGraph
import io.github.iamtoolino.coda.artwork.ArtworkSource
import io.github.iamtoolino.coda.artwork.externalArtistArtworkSource
import io.github.iamtoolino.coda.artwork.localPlaybackArtworkSource
import io.github.iamtoolino.coda.artwork.navidromeArtworkSource
import io.github.iamtoolino.coda.data.Artist

internal val LocalArtworkGeneration = staticCompositionLocalOf { 0L }

@Composable
internal fun navidromeCoverSource(artworkId: String?, size: Int): ArtworkSource? =
    navidromeArtworkSource(
        namespace = AppGraph.cacheNamespace,
        generation = LocalArtworkGeneration.current,
        artworkId = artworkId,
        size = size,
        url = AppGraph.navidrome.coverArtUrl(artworkId, size),
    )

@Composable
internal fun artistArtworkSource(
    artist: Artist,
    size: Int,
    preferExternal: Boolean = false,
): ArtworkSource? {
    val generation = LocalArtworkGeneration.current
    val external = externalArtistArtworkSource(
        namespace = AppGraph.cacheNamespace,
        generation = generation,
        artistId = artist.id,
        requestedSize = size,
        url = artist.artistImageUrl,
    )
    val cover = navidromeArtworkSource(
        namespace = AppGraph.cacheNamespace,
        generation = generation,
        artworkId = artist.coverArt,
        size = size,
        url = AppGraph.navidrome.coverArtUrl(artist.coverArt, size),
    )
    return if (preferExternal) external ?: cover else cover ?: external
}

@Composable
internal fun playbackArtworkSource(
    artworkIdentity: String,
    url: String?,
): ArtworkSource? = localPlaybackArtworkSource(
    namespace = AppGraph.cacheNamespace,
    generation = LocalArtworkGeneration.current,
    artworkIdentity = artworkIdentity,
    url = url,
)
