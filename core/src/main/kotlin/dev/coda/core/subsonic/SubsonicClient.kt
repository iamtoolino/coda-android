package dev.coda.core.subsonic

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class SubsonicClient(
    val configuration: SubsonicConfig,
    client: OkHttpClient = OkHttpClient(),
    /** Every API request's outcome for the shared reachability state (true: the server answered; see [dev.coda.core.connectivity.Reachability.answered]). */
    private val onOutcome: (Boolean) -> Unit = {},
    /**
     * Called as each API request starts; the outcome goes to the reporter it returns (the app tags it with the
     * session that sent it). Default: [onOutcome].
     */
    private val issue: () -> (Boolean) -> Unit = { onOutcome },
) {
    // Upgrade-only redirects (RedirectGuard): the authenticated query never leaves the host or drops to http.
    private val client = RedirectGuard.install(client).newBuilder().callTimeout(30, TimeUnit.SECONDS).cache(null).build()

    /**
     * Set when a request to this http server was answered over https after a same-host upgrade redirect; the
     * https origin to use from now on (sign-in stores it instead of the typed http address).
     */
    @Volatile var upgradedServerUrl: String? = null
        private set
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /**
     * Ids of albums this session has seen without a `coverArt`. Songs from playlists, search and the play queue
     * carry no album cover info of their own, so they are marked from here ([Song.albumHasNoCover]); an album never seen
     * stays unmarked.
     */
    private val albumsWithoutCover: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Album id to the server's own `coverArt` id, for albums this session has seen (ART-1 revalidation stamp). */
    private val albumCoverArt = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** The server's `coverArt` id of an album seen this session (the key album rows used before), or null. */
    fun serverCoverArt(albumId: String): String? = albumCoverArt[albumId]

    private fun List<Album>.noted(): List<Album> = also { albums ->
        albums.forEach {
            if (it.artworkId == null) albumsWithoutCover.add(it.id) else albumsWithoutCover.remove(it.id)
            it.serverCoverArt?.let { cover -> albumCoverArt[it.id] = cover }
        }
    }
    private fun Song.marked(): Song = if (!albumHasNoCover && albumId != null && albumId in albumsWithoutCover) copy(albumHasNoCover = true) else this
    private fun List<Song>.marked(): List<Song> = map { it.marked() }

    suspend fun ping(): ServerDetails {
        val response = checkedResponse("ping").first
        return ServerDetails(response.type, response.serverVersion, response.version, response.openSubsonic)
    }

    suspend fun getAlbumList2(size: Int = 40, offset: Int = 0, type: AlbumListType = AlbumListType.NEWEST): AlbumListPage {
        val requestedSize = size.coerceIn(1, 500)
        val requestedOffset = offset.coerceAtLeast(0)
        val (response, total) = checkedResponse(
            "getAlbumList2",
            listOf("type" to type.apiValue, "size" to "$requestedSize", "offset" to "$requestedOffset") +
                if (type == AlbumListType.RECENT_RELEASES) listOf("fromYear" to "3000", "toYear" to "0") else emptyList(),
        )
        val albums = response.albumList2?.album.orEmpty().noted()
        return AlbumListPage(albums, resolvedAlbumTotalCount(total, requestedOffset, albums.size, requestedSize))
    }

    suspend fun getArtists(): List<Artist> = withContext(Dispatchers.Default) {
        val indexes = checkedResponse("getArtists").first.artists
        val articles = indexes?.ignoredArticles.orEmpty().split(' ').filter { it.isNotBlank() }
        indexes?.index.orEmpty().flatMap { it.artist.orEmpty() }
            .map { artist -> dev.coda.core.library.artistSortKey(artist.name, artist.sortName, articles)
                .takeIf { it != artist.name.trim() && it != artist.sortName }?.let { artist.copy(sortName = it) } ?: artist }
            .sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.getDefault()).apply {
                strength = java.text.Collator.SECONDARY
            }) { it.name })
    }

    suspend fun getArtist(id: String): ArtistPage {
        val detail = checkedResponse("getArtist", listOf("id" to id)).first.artist
            ?: throw SubsonicError.Server(null, "The server did not return the artist.")
        return withContext(Dispatchers.Default) {
            ArtistPage(Artist(detail.id, detail.name, detail.albumCount, detail.coverArt, detail.genre),
                ArtistDiscography.sorted(detail.album.orEmpty().noted()))
        }
    }

    /** NavidromeClient.search: trim, clamp counts, and retain artists without an albumCount. */
    suspend fun search3(query: String, artistCount: Int = 16, albumCount: Int = 24, songCount: Int = 40, songOffset: Int = 0): SearchResults {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return SearchResults()
        val result = checkedResponse("search3", listOf("query" to trimmed,
            "artistCount" to artistCount.coerceIn(0, 100).toString(),
            "albumCount" to albumCount.coerceIn(0, 100).toString(),
            "songCount" to songCount.coerceIn(0, 100).toString(),
            "songOffset" to songOffset.coerceAtLeast(0).toString())).first.searchResult3 ?: return SearchResults()
        return SearchResults(result.artist.orEmpty().filter { it.albumCount != 0 }, result.album.orEmpty().noted(), result.song.orEmpty().marked())
    }

    suspend fun setRating(id: String, rating: Int) {
        require(id.isNotBlank() && rating in 0..5)
        checkedResponse("setRating", listOf("id" to id, "rating" to rating.toString()))
    }

    /** Preserve queue order and duplicate songs; form POST avoids URL length limits. */
    suspend fun createPlaylist(name: String, songIds: List<String>): Playlist {
        require(name.isNotBlank() && songIds.isNotEmpty() && songIds.none { it.isBlank() })
        val detail = checkedResponse("createPlaylist", listOf("name" to name.trim()) +
            songIds.map { "songId" to it }, formPost = true).first.playlist
            ?: throw SubsonicError.Server(null, "The server did not return the playlist.")
        return withContext(Dispatchers.Default) { json.decodeFromJsonElement<Playlist>(detail) }
    }

    suspend fun renamePlaylist(id: String, name: String) {
        require(id.isNotBlank() && name.isNotBlank())
        checkedResponse("updatePlaylist", listOf("playlistId" to id, "name" to name.trim()), formPost = true)
    }

    /** "Add to playlist…": appends [songIds] in order (duplicates kept, as the server does). */
    suspend fun addToPlaylist(id: String, songIds: List<String>) {
        require(id.isNotBlank() && songIds.isNotEmpty() && songIds.none { it.isBlank() })
        checkedResponse("updatePlaylist", listOf("playlistId" to id) + songIds.map { "songIdToAdd" to it }, formPost = true)
    }

    /** "Remove from playlist": removes the entry at [index] (0-based position in the playlist). */
    suspend fun removeFromPlaylist(id: String, index: Int) {
        require(id.isNotBlank() && index >= 0)
        checkedResponse("updatePlaylist", listOf("playlistId" to id, "songIndexToRemove" to index.toString()), formPost = true)
    }

    suspend fun deletePlaylist(id: String) {
        require(id.isNotBlank())
        checkedResponse("deletePlaylist", listOf("id" to id), formPost = true)
    }

    suspend fun getPlaylists(): List<Playlist> = checkedResponse("getPlaylists").first.playlists?.playlist.orEmpty()
    suspend fun getBookmarks(): List<Bookmark> = checkedResponse("getBookmarks").first.bookmarks?.bookmark.orEmpty().map { b -> b.copy(entry = b.entry?.marked()) }
    suspend fun getPlayQueue(): PlayQueue? = checkedResponse("getPlayQueue").first.playQueue?.let { it.copy(entry = it.entry.marked()) }

    suspend fun savePlayQueue(songIDs: List<String>, currentIndex: Int, positionMilliseconds: Long) {
        val parameters = songIDs.map { "id" to it } + if (songIDs.isEmpty()) emptyList() else listOf(
            "current" to songIDs[currentIndex.coerceIn(songIDs.indices)],
            "position" to positionMilliseconds.coerceAtLeast(0).toString())
        checkedResponse("savePlayQueue", parameters, formPost = true)
    }

    /** NavidromeClient.song(id:). */
    suspend fun getSong(id: String): Song {
        val detail = checkedResponse("getSong", listOf("id" to id)).first.song
            ?: throw SubsonicError.Server(null, "The server did not return the song.")
        return withContext(Dispatchers.Default) {
            try { json.decodeFromJsonElement<Song>(detail).marked() } catch (_: SerializationException) { throw SubsonicError.Decoding() }
            catch (_: IllegalArgumentException) { throw SubsonicError.Decoding() }
        }
    }

    /** NavidromeClient.createBookmark / bookmarkMutationRequest: form POST, trimmed id, position clamped at 0. */
    suspend fun createBookmark(songId: String, positionMilliseconds: Long, comment: String) {
        checkedResponse("createBookmark", listOf("id" to bookmarkSongId(songId),
            "position" to positionMilliseconds.coerceAtLeast(0).toString(), "comment" to comment), formPost = true)
    }

    /** NavidromeClient.deleteBookmark: form POST with only the (trimmed) song id. */
    suspend fun deleteBookmark(songId: String) {
        checkedResponse("deleteBookmark", listOf("id" to bookmarkSongId(songId)), formPost = true)
    }

    private fun bookmarkSongId(songId: String): String = songId.trim().also {
        if (it.isEmpty()) throw SubsonicError.InvalidConfiguration("The bookmark song ID is empty.")
    }

    suspend fun getAlbum(id: String): AlbumPage {
        val detail = checkedResponse("getAlbum", listOf("id" to id)).first.album
            ?: throw SubsonicError.Server(null, "The server did not return the album.")
        return withContext(Dispatchers.Default) {
            try {
                val album = json.decodeFromJsonElement<Album>(detail).also { listOf(it).noted() }
                val songs = detail.jsonObject["song"]?.takeUnless { it == kotlinx.serialization.json.JsonNull }
                    ?.let { json.decodeFromJsonElement<List<Song>>(it) }.orEmpty()
                val subtitles = mutableMapOf<Int, String>()
                album.discTitles.orEmpty().forEach { subtitles.putIfAbsent(maxOf(1, it.disc), it.title.trim()) }
                AlbumPage(album, AlbumOrdering.canonical(songs.map {
                    it.applyingAlbum(album).copy(discSubtitle = subtitles[maxOf(1, it.discNumber ?: 1)])
                }))
            } catch (_: SerializationException) {
                throw SubsonicError.Decoding()
            } catch (_: IllegalArgumentException) {
                throw SubsonicError.Decoding()
            }
        }
    }

    suspend fun getPlaylist(id: String): PlaylistPage {
        val detail = checkedResponse("getPlaylist", listOf("id" to id)).first.playlist
            ?: throw SubsonicError.Server(null, "The server did not return the playlist.")
        return withContext(Dispatchers.Default) {
            try {
                val songs = detail.jsonObject["entry"]?.takeUnless { it == kotlinx.serialization.json.JsonNull }
                    ?.let { json.decodeFromJsonElement<List<Song>>(it) }.orEmpty()
                PlaylistPage(json.decodeFromJsonElement<Playlist>(detail), songs.marked())
            } catch (_: SerializationException) {
                throw SubsonicError.Decoding()
            } catch (_: IllegalArgumentException) {
                throw SubsonicError.Decoding()
            }
        }
    }

    /** NavidromeClient.scrobble: `submission=false` is now playing, `true` records a play at [timeMs]. */
    suspend fun scrobble(songId: String, submission: Boolean, timeMs: Long) {
        val id = songId.trim()
        if (id.isEmpty()) throw SubsonicError.InvalidConfiguration("The scrobble song ID is empty.")
        checkedResponse("scrobble", listOf(
            "id" to id, "submission" to submission.toString(), "time" to maxOf(0L, timeMs).toString(),
        ))
    }

    fun coverArtUrl(id: String, size: Int): HttpUrl = configuration.coverArtUrl(id, size)
    fun streamUrl(songId: String, format: String = "raw", timeOffsetSeconds: Int = 0): HttpUrl =
        configuration.streamUrl(songId, format, timeOffsetSeconds = timeOffsetSeconds)

    /** Names of the OpenSubsonic extensions the server advertises (`getOpenSubsonicExtensions`). */
    suspend fun openSubsonicExtensions(): Set<String> =
        checkedResponse("getOpenSubsonicExtensions").first.openSubsonicExtensions.orEmpty().map { it.name }.toSet()

    /**
     * Marks this client's API requests; their outcome is reported only after decoding ([onOutcome]), so a shared
     * "the server answered" interceptor leaves them out (an undecodable 2xx is no answer) and counts covers and streams.
     */
    object ApiRequest

    private suspend fun checkedResponse(
        endpoint: String,
        parameters: List<Pair<String, String>> = emptyList(),
        formPost: Boolean = false,
    ): Pair<ApiResponse, String?> {
        val report = issue()
        return try {
            apiResponse(endpoint, parameters, formPost).also { report(true) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            dev.coda.core.connectivity.Reachability.answered(e)?.let(report)
            throw e
        }
    }

    private suspend fun apiResponse(
        endpoint: String,
        parameters: List<Pair<String, String>>,
        formPost: Boolean,
    ): Pair<ApiResponse, String?> {
        val url = configuration.endpointUrl(endpoint, parameters)
        val request = if (formPost) {
            val form = okhttp3.FormBody.Builder().apply {
                for (i in 0 until url.querySize) add(url.queryParameterName(i), url.queryParameterValue(i).orEmpty())
            }.build()
            Request.Builder().url(url.newBuilder().query(null).build()).post(form).tag(ApiRequest::class.java, ApiRequest).build()
        } else Request.Builder().url(url).tag(ApiRequest::class.java, ApiRequest).build()
        val (body, total, finalUrl) = withContext(Dispatchers.IO) {
            client.newCall(request).awaitBody()
        }
        val suffix = "/rest/$endpoint.view"
        if (!request.url.isHttps && finalUrl.isHttps && finalUrl.host.equals(request.url.host, ignoreCase = true) &&
            finalUrl.encodedPath.endsWith(suffix)
        ) {
            val path = finalUrl.encodedPath.removeSuffix(suffix)
            // Credential-free: no user info, query or fragment (the guard already refuses user info in redirects).
            upgradedServerUrl = finalUrl.newBuilder().username("").password("").encodedPath(path.ifEmpty { "/" }).query(null).fragment(null).build()
                .toString().let { if (path.isEmpty()) it.removeSuffix("/") else it }
        }
        val response = withContext(Dispatchers.Default) {
            try {
                json.decodeFromString<Envelope>(body).response
            } catch (_: SerializationException) {
                throw SubsonicError.Decoding()
            } catch (_: IllegalArgumentException) {
                throw SubsonicError.Decoding()
            }
        }
        if (response.status != "ok") {
            throw when (response.error?.code) {
                40, 41 -> SubsonicError.Unauthorized()
                70 -> SubsonicError.NotFound()
                else -> SubsonicError.Server(response.error?.code, response.error?.message)
            }
        }
        return response to total
    }
}

private suspend fun Call.awaitBody(): Triple<String, String?, HttpUrl> = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(SubsonicError.Network(e))
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use {
                    when (it.code) {
                        401, 403 -> throw SubsonicError.Unauthorized(it.code)
                        !in 200..299 -> throw SubsonicError.Http(it.code)
                    }
                    Triple(it.body?.string() ?: throw SubsonicError.Decoding(), it.header("X-Total-Count"), it.request.url)
                }
                continuation.resumeWith(Result.success(result))
            } catch (error: IOException) {
                continuation.resumeWithException(SubsonicError.Network(error))
            } catch (error: SubsonicError) {
                continuation.resumeWithException(error)
            }
        }
    })
}

@Serializable
private data class Envelope(@SerialName("subsonic-response") val response: ApiResponse)

@Serializable
private data class ApiResponse(
    val status: String,
    val version: String? = null,
    val type: String? = null,
    val serverVersion: String? = null,
    val openSubsonic: Boolean? = null,
    val openSubsonicExtensions: List<OpenSubsonicExtension>? = null,
    val error: ApiError? = null,
    val albumList2: AlbumListResponse? = null,
    val artists: ArtistIndexes? = null,
    val artist: ArtistDetail? = null,
    val searchResult3: SearchResponse? = null,
    val playlists: PlaylistsResponse? = null,
    val bookmarks: BookmarksResponse? = null,
    val playQueue: PlayQueue? = null,
    val album: kotlinx.serialization.json.JsonElement? = null,
    val playlist: kotlinx.serialization.json.JsonElement? = null,
    val song: kotlinx.serialization.json.JsonElement? = null,
)

@Serializable
private data class OpenSubsonicExtension(val name: String, val versions: List<Int>? = null)

@Serializable
private data class ApiError(val code: Int? = null, val message: String? = null)

@Serializable
private data class AlbumListResponse(val album: List<Album>? = null)

@Serializable
private data class PlaylistsResponse(val playlist: List<Playlist>? = null)
@Serializable
private data class BookmarksResponse(val bookmark: List<Bookmark>? = null)

@Serializable private data class ArtistIndexes(val index: List<ArtistIndex>? = null, val ignoredArticles: String? = null)
@Serializable private data class ArtistIndex(val artist: List<Artist>? = null)
@Serializable private data class ArtistDetail(val id: String, val name: String,
    val albumCount: Int? = null, val coverArt: String? = null, val genre: String? = null,
    val album: List<Album>? = null)

@Serializable private data class SearchResponse(val artist: List<Artist>? = null,
    val album: List<Album>? = null, val song: List<Song>? = null)
