package dev.coda.android.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.coda.core.subsonic.Album
import dev.coda.core.subsonic.Song

/** Queue metadata shared by phone and Auto entry points. */
internal object QueueMediaItems {
    /** A known album decides alone: one without art stays without (any other id would fetch the server's placeholder). */
    fun coverArtId(song: Song, album: Album?): String? = if (album != null) album.artworkId else song.albumArtworkId

    fun from(song: Song, album: Album? = null, continuationAlbumId: String? = null): MediaItem =
        MediaItem.Builder().setMediaId("song/${song.id}").setMediaMetadata(
            MediaMetadata.Builder().setTitle(song.title).setArtist(song.artistName)
                .setAlbumTitle(album?.name ?: song.album).setAlbumArtist(album?.artistName ?: song.albumArtistName)
                .setReleaseYear(album?.releaseYear ?: song.year)
                .setDurationMs(song.duration?.toLong()?.times(1000)).setTrackNumber(song.track).setDiscNumber(song.discNumber)
                .setExtras(Bundle().apply {
                    // Absent when the album isn't known here (search, playlists): the player then asks the server.
                    album?.let { putInt("albumRating", it.userRating ?: 0) }
                    putString("formatName", song.suffix?.uppercase())
                    song.bitRate?.let { putInt("bitRate", it) }
                    putString("coverArtId", coverArtId(song, album))
                    putString("albumId", song.albumId ?: album?.id)
                    continuationAlbumId?.let { putString("albumContinuationId", it) }
                    putReplayGain(song.replayGain)
                }).build().let(MetadataGuard::guard)).build()
}
