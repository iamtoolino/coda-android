package io.github.iamtoolino.coda.artwork

internal object ArtworkSizes {
    const val CAR_BROWSE_THUMBNAIL = 320
    const val PLAYLIST_THUMBNAIL = 420
    const val ALBUM_GRID = 420
    const val ARTIST_THUMBNAIL = 500
    const val ALBUM_CARD = 600
    const val HERO = 1_200
}

internal data class ArtworkSource(
    val url: String,
    val diskCacheKey: String?,
    val memoryCacheKey: String,
)

internal fun navidromeArtworkSource(
    namespace: String,
    generation: Long,
    artworkId: String?,
    size: Int,
    url: String?,
): ArtworkSource? {
    val id = artworkId?.takeIf(String::isNotBlank) ?: return null
    val resolvedUrl = url?.takeIf(String::isNotBlank) ?: return null
    val sourceKey = "$namespace:artwork-v$generation:cover:$id:$size"
    return ArtworkSource(
        url = resolvedUrl,
        diskCacheKey = sourceKey,
        memoryCacheKey = sourceKey,
    )
}

internal fun externalArtistArtworkSource(
    namespace: String,
    generation: Long,
    artistId: String,
    requestedSize: Int,
    url: String?,
): ArtworkSource? {
    val resolvedUrl = url?.takeIf(String::isNotBlank) ?: return null
    val sourceKey = "$namespace:artwork-v$generation:external-artist:$artistId"
    return ArtworkSource(
        url = resolvedUrl,
        diskCacheKey = sourceKey,
        memoryCacheKey = "$sourceKey:$requestedSize",
    )
}

internal fun localPlaybackArtworkSource(
    namespace: String,
    generation: Long,
    artworkIdentity: String,
    url: String?,
): ArtworkSource? {
    val resolvedUrl = url?.takeIf(String::isNotBlank) ?: return null
    return ArtworkSource(
        url = resolvedUrl,
        diskCacheKey = null,
        memoryCacheKey = "$namespace:artwork-v$generation:local-playback:$artworkIdentity",
    )
}
