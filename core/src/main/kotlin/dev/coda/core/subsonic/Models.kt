package dev.coda.core.subsonic

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class ItemDate(val year: Int? = null, val month: Int? = null, val day: Int? = null)

@Serializable
data class ArtistReference(val id: String, val name: String)

@Serializable
data class DiscTitle(val disc: Int, val title: String, val coverArt: String? = null)

@Serializable
data class Album(
    val id: String,
    val name: String,
    val artist: String? = null,
    val displayArtist: String? = null,
    val artists: List<ArtistReference>? = null,
    val isCompilation: Boolean? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val created: String? = null,
    val year: Int? = null,
    val originalReleaseDate: ItemDate? = null,
    val releaseDate: ItemDate? = null,
    val playCount: Int? = null,
    val userRating: Int? = null,
    val discTitles: List<DiscTitle>? = null,
    val genre: String? = null,
    val releaseTypes: List<String>? = null,
    /** OpenSubsonic: the server's sort name (section letters). */
    val sortName: String? = null,
    /** OpenSubsonic: when the album was last played (ISO 8601), for the Recently Played sections. */
    val played: String? = null,
) {
    val artistName: String get() = displayArtist?.nonEmpty() ?: artist.orEmpty()
    /**
     * OF-1c3, the one key for "this album's cover" ([CoverIds.album]): the album id when the album has a `coverArt`, the
     * same key its songs, the queue, Now Playing and the kept-cover store use. Null when the server names no `coverArt`:
     * `getCoverArt(albumId)` would answer with the server's generic placeholder, so null means "show our own".
     */
    val artworkId: String? get() = CoverIds.album(id, coverArt)
    /** The server's own `coverArt` id (Navidrome `al-…`), the key older builds used for album rows: read as a fallback only. */
    val serverCoverArt: String? get() = coverArt?.takeIf { it.isNotEmpty() }
    val releaseYear: Int? get() = originalReleaseDate?.year ?: releaseDate?.year ?: year
}

@Serializable
data class Song(
    val id: String,
    val title: String,
    val album: String? = null,
    val artist: String? = null,
    val displayArtist: String? = null,
    val artists: List<ArtistReference>? = null,
    val displayAlbumArtist: String? = null,
    val albumArtists: List<ArtistReference>? = null,
    val isCompilation: Boolean? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val discSubtitle: String? = null,
    val duration: Int? = null,
    val year: Int? = null,
    val suffix: String? = null,
    val contentType: String? = null,
    val type: String? = null,
    val mediaType: String? = null,
    val bitRate: Int? = null,
    /** Bytes of the original file as stored on the server (Subsonic `size`). */
    val size: Long? = null,
    val bitDepth: Int? = null,
    val samplingRate: Int? = null,
    @Serializable(with = LossyReplayGainSerializer::class)
    val replayGain: dev.coda.core.playback.ReplayGainMetadata? = null,
    /**
     * Set by [SubsonicClient] when the song's album is known to have no `coverArt`. A song only carries its own `coverArt`
     * (`mf-`/`dc-`/`al-` ids that tell nothing about the album: AV-4 probe), so this is the one reliable "no art" signal.
     */
    val albumHasNoCover: Boolean = false,
) {
    val artistName: String get() = displayArtist?.nonEmpty() ?: artist.orEmpty()
    val albumArtistName: String get() = displayAlbumArtist?.nonEmpty() ?: artistName
    /**
     * The id to request the album cover for this song with: null for an album known to have none, so the caller draws our
     * own picture (`getCoverArt` answers an album without art with the server's generic placeholder). Otherwise the
     * album id, which keeps one cache entry per album; a loose song falls back to its own `coverArt`.
     */
    val albumArtworkId: String? get() = if (albumHasNoCover) null else CoverIds.song(albumId, coverArt)

    fun applyingAlbum(album: Album): Song = copy(
        albumHasNoCover = album.artworkId == null,
        displayAlbumArtist = album.artistName.nonEmpty() ?: displayAlbumArtist,
        albumArtists = album.artists ?: albumArtists,
        isCompilation = album.isCompilation ?: isCompilation,
    )
}

private fun String.nonEmpty(): String? = takeIf { it.isNotEmpty() }

/**
 * The one id rule for "this album's cover", so the same picture has one key in the picture cache, the kept-cover
 * store, the accent store and the artwork provider. The key is the **album id**: every song, queue entry, kept track
 * (`TrackMeta.coverArtId`) and Auto node already knows it, and it is stable (Navidrome's `al-<id>_<stamp>` changes
 * with the album).
 */
object CoverIds {
    /** An album's cover key: its id, or null when the album has no `coverArt` (our own picture). */
    fun album(albumId: String, coverArt: String?): String? =
        if (coverArt.isNullOrEmpty()) null else albumId.nonEmpty() ?: coverArt
    /** A song's album cover key: its album's id; a song with no album falls back to its own `coverArt`. */
    fun song(albumId: String?, coverArt: String?): String? = albumId?.nonEmpty() ?: coverArt?.nonEmpty()
}

data class ServerDetails(
    val software: String?,
    val version: String?,
    val apiVersion: String?,
    val supportsOpenSubsonic: Boolean?,
)

data class AlbumPage(val album: Album, val songs: List<Song>)
data class PlaylistPage(val playlist: Playlist, val songs: List<Song>)
data class AlbumListPage(val albums: List<Album>, val totalCount: Int?)

/** Home query semantics from macOS AlbumCollectionKind. */
enum class AlbumListType(val apiValue: String) {
    NEWEST("newest"), RECENT_RELEASES("byYear"), RECENTLY_PLAYED("recent"), ALPHABETICAL("alphabeticalByName"), MOST_PLAYED("frequent"), TOP_RATED("highest"), RANDOM("random")
}

@Serializable
data class Playlist(val id: String, val name: String, val songCount: Int? = null,
    val duration: Int? = null, val changed: String? = null, val coverArt: String? = null,
    val owner: String? = null, val created: String? = null)

@Serializable
data class Bookmark(val position: Long? = null, val comment: String? = null,
    val created: String? = null, val changed: String? = null, val entry: Song? = null)

@Serializable
data class PlayQueue(val current: String? = null, val position: Long? = null,
    val changed: String? = null, val changedBy: String? = null, val currentIndex: Int? = null,
    val entry: List<Song> = emptyList()) {
    val resolvedCurrentIndex: Int get() = currentIndex?.takeIf { it in entry.indices }
        ?: entry.indexOfFirst { it.id == current }.takeIf { it >= 0 } ?: 0
    val currentSong: Song? get() = entry.getOrNull(resolvedCurrentIndex)
    val handoffIdentity: String get() = (listOf(changed.orEmpty(), changedBy.orEmpty(),
        current.orEmpty(), (position ?: 0).toString()) + entry.map { it.id }).joinToString("|")
}

@Serializable
data class Artist(val id: String, val name: String, val albumCount: Int? = null,
    val coverArt: String? = null, val genre: String? = null,
    /** Index sort key: OpenSubsonic `sortName`, else the name without the server's ignored article (see getArtists). */
    val sortName: String? = null)

data class ArtistPage(val artist: Artist, val albums: List<Album>)

@Serializable
data class SearchResults(
    @SerialName("artist") val artists: List<Artist> = emptyList(),
    @SerialName("album") val albums: List<Album> = emptyList(),
    @SerialName("song") val songs: List<Song> = emptyList(),
)
