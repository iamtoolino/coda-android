package io.github.iamtoolino.coda.player

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import io.github.iamtoolino.coda.AppGraph
import io.github.iamtoolino.coda.NavidromeSession
import io.github.iamtoolino.coda.artwork.ArtworkSizes
import io.github.iamtoolino.coda.data.Song

internal fun Song.toPlayableMediaItem(context: Context): MediaItem =
    toPlayableMediaItem(context, isMobileNetwork(context))

internal fun streamCacheKey(namespace: String, songId: String, variant: String): String =
    "stream:$namespace:$songId:$variant"

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun Song.toPlayableMediaItem(context: Context, mobile: Boolean): MediaItem {
    val session = requireNotNull(AppGraph.sessionSnapshot()) { "Connect a Navidrome server first" }
    return toPlayableMediaItem(context, mobile, session)
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun Song.toPlayableMediaItem(
    context: Context,
    mobile: Boolean,
    session: NavidromeSession,
): MediaItem {
    val coverUri = CarArtwork.cover(
        context,
        albumId ?: coverArt,
        size = ArtworkSizes.ALBUM_CARD,
        namespace = session.cacheNamespace,
    )
    val extras = Bundle().apply {
        putString("albumId", albumId)
        putString("artistId", artistId)
        putString("coverArtId", albumId ?: coverArt)
        putString("cacheNamespace", session.cacheNamespace)
        putLong("accountGeneration", session.generation)
        putLong("durationMs", duration.coerceAtLeast(0) * 1_000L)
        track?.let { putInt("trackNumber", it) }
        discNumber?.let { putInt("discNumber", it) }
        putString("sourceCodec", suffix)
        bitDepth?.let { putInt("sourceBitDepth", it) }
        samplingRate?.let { putInt("sourceSamplingRate", it) }
        bitRate?.let { putInt("sourceBitRate", it) }
        putString("codec", if (mobile) "opus" else suffix)
        if (!mobile) {
            bitDepth?.let { putInt("bitDepth", it) }
            samplingRate?.let { putInt("samplingRate", it) }
            bitRate?.let { putInt("bitRate", it) }
        }
    }
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setArtworkUri(coverUri)
        .setExtras(extras)
        .build()
    val variant = if (mobile) "opus" else "raw"
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(session.client.streamUrl(id, mobile))
        .setCustomCacheKey(streamCacheKey(session.cacheNamespace, id, variant))
        .setMediaMetadata(metadata)
        .build()
}
