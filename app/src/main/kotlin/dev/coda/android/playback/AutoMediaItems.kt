package dev.coda.android.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import dev.coda.android.artwork.ArtworkContentProvider
import dev.coda.core.library.LibraryNode
import dev.coda.core.library.PlaybackRequest

/** Maps core browse nodes and playback requests onto Media3 items. Artwork is always a credential-free content URI. */
internal object AutoMediaItems {
    fun browse(node: LibraryNode): MediaItem {
        val metadata = MediaMetadata.Builder().setTitle(node.title).setSubtitle(node.subtitle)
            .setIsBrowsable(node.browsable).setIsPlayable(node.playable)
            .setMediaType(when (node.kind) {
                LibraryNode.Kind.FOLDER -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                LibraryNode.Kind.ALBUM, LibraryNode.Kind.RESUME -> MediaMetadata.MEDIA_TYPE_ALBUM
                LibraryNode.Kind.ARTIST -> MediaMetadata.MEDIA_TYPE_ARTIST
                LibraryNode.Kind.PLAYLIST -> MediaMetadata.MEDIA_TYPE_PLAYLIST
                LibraryNode.Kind.TRACK -> MediaMetadata.MEDIA_TYPE_MUSIC
            })
        // Albums and playlists are grid tiles in the car; without a cover id they get Coda's placeholder, as on the phone.
        (node.artworkId ?: ArtworkContentProvider.PLACEHOLDER_ID.takeIf { node.kind == LibraryNode.Kind.PLAYLIST || node.kind == LibraryNode.Kind.ALBUM })
            ?.let { metadata.setArtworkUri(ArtworkContentProvider.uri(it)) }
        node.song?.let { song ->
            metadata.setArtist(song.artistName).setAlbumTitle(song.album).setTrackNumber(song.track)
                .setDurationMs(song.duration?.toLong()?.times(1000))
        }
        node.childStyle?.let { style ->
            val value = if (style == LibraryNode.ChildStyle.GRID) MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
                else MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            metadata.setExtras(contentStyle(value))
        }
        return MediaItem.Builder().setMediaId(node.id).setMediaMetadata(MetadataGuard.guard(metadata.build())).build()
    }

    /** Auto reads these hints from the parent: grid for album lists, list for tracks and folders. */
    fun contentStyle(value: Int) = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, value)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, value)
    }

    fun queue(request: PlaybackRequest): List<MediaItem> = request.songs.map {
        QueueMediaItems.from(it, request.album, request.continuationAlbumId)
    }
}
