package io.github.iamtoolino.coda.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ItemDate(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
)

@Serializable
data class Album(
    val id: String,
    val name: String,
    val artist: String = "",
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val created: String? = null,
    val year: Int? = null,
    val originalReleaseDate: ItemDate? = null,
    val releaseDate: ItemDate? = null,
    val playCount: Long? = null,
    val starred: String? = null,
    val userRating: Int? = null,
)

enum class AlbumListType(val apiValue: String) {
    NEWEST("newest"),
    RECENTLY_PLAYED("recent"),
    HIGHEST_RATED("highest"),
    MOST_PLAYED("frequent"),
    ALPHABETICAL("alphabeticalByName"),
    RELEASE_YEAR("byYear"),
}

@Serializable
data class Artist(
    val id: String,
    val name: String,
    val albumCount: Int = 0,
    val coverArt: String? = null,
    val artistImageUrl: String? = null,
    val genre: String? = null,
)

@Serializable
data class Song(
    val id: String,
    val title: String,
    val album: String = "",
    val artist: String = "",
    val albumId: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val duration: Int = 0,
    val suffix: String? = null,
    val contentType: String? = null,
    val bitRate: Int? = null,
    val bitDepth: Int? = null,
    val samplingRate: Int? = null,
)

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val songCount: Int = 0,
    val duration: Int = 0,
    val changed: String? = null,
    val coverArt: String? = null,
    val owner: String? = null,
    val entry: List<Song> = emptyList(),
)

@Serializable
data class PlayQueue(
    val current: String? = null,
    val position: Long? = null,
    val changed: String? = null,
    val changedBy: String? = null,
    val currentIndex: Int? = null,
    val entry: List<Song> = emptyList(),
)

@Serializable
internal data class SubsonicEnvelope(
    @SerialName("subsonic-response") val response: SubsonicResponse,
)

@Serializable
internal data class SubsonicResponse(
    val status: String,
    val error: SubsonicError? = null,
    val albumList2: AlbumList? = null,
    val artists: ArtistIndexes? = null,
    val artist: ArtistDetail? = null,
    val album: AlbumDetail? = null,
    val song: Song? = null,
    val playlists: PlaylistList? = null,
    val playlist: Playlist? = null,
    val searchResult3: SearchResult? = null,
    val playQueue: PlayQueue? = null,
)

@Serializable
internal data class SubsonicError(
    val code: Int? = null,
    val message: String = "Unknown server error",
)

@Serializable
internal data class AlbumList(val album: List<Album> = emptyList())

@Serializable
internal data class ArtistIndexes(val index: List<ArtistIndex> = emptyList())

@Serializable
internal data class ArtistIndex(val name: String = "", val artist: List<Artist> = emptyList())

@Serializable
internal data class ArtistDetail(
    val id: String,
    val name: String,
    val albumCount: Int = 0,
    val coverArt: String? = null,
    val artistImageUrl: String? = null,
    val genre: String? = null,
    val album: List<Album> = emptyList(),
)

@Serializable
internal data class AlbumDetail(
    val id: String,
    val name: String,
    val artist: String = "",
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val created: String? = null,
    val year: Int? = null,
    val originalReleaseDate: ItemDate? = null,
    val releaseDate: ItemDate? = null,
    val userRating: Int? = null,
    val song: List<Song> = emptyList(),
) {
    fun album(): Album = Album(
        id = id,
        name = name,
        artist = artist,
        artistId = artistId,
        coverArt = coverArt,
        songCount = songCount,
        duration = duration,
        created = created,
        year = year,
        originalReleaseDate = originalReleaseDate,
        releaseDate = releaseDate,
        userRating = userRating,
    )
}

@Serializable
internal data class PlaylistList(val playlist: List<Playlist> = emptyList())

@Serializable
data class SearchResult(
    val artist: List<Artist> = emptyList(),
    val album: List<Album> = emptyList(),
    val song: List<Song> = emptyList(),
)

data class AlbumPage(val album: Album, val songs: List<Song>)
