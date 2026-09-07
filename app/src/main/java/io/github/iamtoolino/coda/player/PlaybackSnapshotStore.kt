package io.github.iamtoolino.coda.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.github.iamtoolino.coda.NavidromeSession
import io.github.iamtoolino.coda.data.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class PlaybackSnapshot(
    val cacheNamespace: String,
    val songs: List<Song>,
    val currentIndex: Int,
    val positionMs: Long,
)

internal object PlaybackSnapshotCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(snapshot: PlaybackSnapshot): String = json.encodeToString(snapshot)

    fun decode(value: String): PlaybackSnapshot? =
        runCatching { json.decodeFromString<PlaybackSnapshot>(value) }.getOrNull()
}

internal class PlaybackSnapshotStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(cacheNamespace: String): PlaybackSnapshot? = preferences
        .getString(SNAPSHOT_KEY, null)
        ?.let(PlaybackSnapshotCodec::decode)
        ?.takeIf { it.cacheNamespace == cacheNamespace && it.songs.isNotEmpty() }

    fun save(snapshot: PlaybackSnapshot) {
        preferences.edit().putString(SNAPSHOT_KEY, PlaybackSnapshotCodec.encode(snapshot)).apply()
    }

    fun clear() {
        preferences.edit().remove(SNAPSHOT_KEY).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "playback_snapshot"
        const val SNAPSHOT_KEY = "current"
    }
}

internal fun Player.playbackSnapshot(account: NavidromeSession): PlaybackSnapshot? {
    if (mediaItemCount == 0 || currentMediaItemIndex !in 0 until mediaItemCount) return null
    val songs = (0 until mediaItemCount).map { index ->
        getMediaItemAt(index).snapshotSong() ?: return null
    }
    return PlaybackSnapshot(
        cacheNamespace = account.cacheNamespace,
        songs = songs,
        currentIndex = currentMediaItemIndex,
        positionMs = currentPosition.coerceAtLeast(0),
    )
}

private fun MediaItem.snapshotSong(): Song? {
    if (mediaId.isBlank()) return null
    val extras = mediaMetadata.extras
    return Song(
        id = mediaId,
        title = mediaMetadata.title?.toString().orEmpty(),
        album = mediaMetadata.albumTitle?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString().orEmpty(),
        albumId = extras?.getString("albumId"),
        artistId = extras?.getString("artistId"),
        coverArt = extras?.getString("coverArtId"),
        canonicalAlbumCoverArt = extras?.getString("canonicalAlbumCoverArt"),
        track = extras?.getInt("trackNumber")?.takeIf { extras.containsKey("trackNumber") },
        discNumber = extras?.getInt("discNumber")?.takeIf { extras.containsKey("discNumber") },
        duration = ((extras?.getLong("durationMs") ?: 0L) / 1_000L)
            .coerceIn(0, Int.MAX_VALUE.toLong())
            .toInt(),
        suffix = extras?.getString("sourceCodec"),
        bitRate = extras?.getInt("sourceBitRate")?.takeIf { extras.containsKey("sourceBitRate") },
        bitDepth = extras?.getInt("sourceBitDepth")?.takeIf { extras.containsKey("sourceBitDepth") },
        samplingRate = extras?.getInt("sourceSamplingRate")
            ?.takeIf { extras.containsKey("sourceSamplingRate") },
    )
}
