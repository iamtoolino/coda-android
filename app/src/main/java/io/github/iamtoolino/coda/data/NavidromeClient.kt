package io.github.iamtoolino.coda.data

import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.Json
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.FormBody
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal suspend fun <T> collectAllPages(
    pageSize: Int,
    maximumPageRequests: Int,
    maximumItems: Int,
    itemId: (T) -> String,
    loadPage: suspend (offset: Int) -> List<T>,
): List<T> {
    require(pageSize > 0 && maximumPageRequests > 0 && maximumItems > 0)
    val result = mutableListOf<T>()
    val seenIds = mutableSetOf<String>()
    val seenFullPages = mutableSetOf<List<String>>()
    var offset = 0
    repeat(maximumPageRequests) {
        val page = loadPage(offset)
        if (result.size + page.size > maximumItems) {
            throw IOException("Album pagination exceeded $maximumItems items")
        }
        if (page.size == pageSize) {
            val signature = page.map(itemId)
            val madeProgress = signature.any { it !in seenIds }
            if (!seenFullPages.add(signature) || !madeProgress) {
                throw IOException("Album pagination made no progress at offset $offset")
            }
            seenIds += signature
        } else {
            page.forEach { seenIds += itemId(it) }
        }
        result += page
        if (page.size < pageSize) return result
        if (offset > Int.MAX_VALUE - page.size) {
            throw IOException("Album pagination offset overflowed")
        }
        offset += page.size
    }
    throw IOException("Album pagination exceeded $maximumPageRequests requests")
}

class NavidromeClient(
    serverUrl: String,
    private val username: String,
    private val password: String,
    private val httpClient: OkHttpClient = DEFAULT_HTTP_CLIENT,
    internal val queueClientName: String = CLIENT_NAME,
) {
    private val baseUrl = serverUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    suspend fun ping() {
        call("ping")
    }

    suspend fun newestAlbums(size: Int = 40, offset: Int = 0): List<Album> =
        albums(AlbumListType.NEWEST, size, offset)

    suspend fun albums(
        type: AlbumListType,
        size: Int = 40,
        offset: Int = 0,
    ): List<Album> {
        val parameters = buildList<Pair<String, Any?>> {
            add("type" to type.apiValue)
            add("size" to size.coerceIn(1, 500))
            add("offset" to offset.coerceAtLeast(0))
            if (type == AlbumListType.RELEASE_YEAR) {
                add("fromYear" to 3000)
                add("toYear" to 0)
            }
        }
        return call("getAlbumList2", *parameters.toTypedArray()).albumList2?.album.orEmpty()
    }

    suspend fun allAlbums(type: AlbumListType): List<Album> {
        return collectAllPages(
            pageSize = ALL_ALBUMS_PAGE_SIZE,
            maximumPageRequests = MAX_ALL_ALBUM_PAGE_REQUESTS,
            maximumItems = MAX_ALL_ALBUMS,
            itemId = Album::id,
        ) { offset ->
            albums(type = type, size = ALL_ALBUMS_PAGE_SIZE, offset = offset)
        }
    }

    suspend fun allNewestAlbums(): List<Album> = allAlbums(AlbumListType.NEWEST)

    suspend fun recentlyPlayedAlbums(size: Int = 20): List<Album> =
        albums(AlbumListType.RECENTLY_PLAYED, size)

    suspend fun artists(): List<Artist> = call("getArtists")
        .artists?.index.orEmpty()
        .flatMap { it.artist }
        .sortedBy { it.name.lowercase() }

    suspend fun artistAlbums(id: String): Pair<Artist, List<Album>> {
        val detail = call("getArtist", "id" to id).artist
            ?: error("Artist was not returned by the server")
        val artist = Artist(
            id = detail.id,
            name = detail.name,
            albumCount = detail.albumCount.takeIf { it > 0 } ?: detail.album.size,
            coverArt = detail.coverArt,
            artistImageUrl = detail.artistImageUrl,
            genre = detail.genre,
        )
        return artist to detail.album.sortedWith(
            compareBy<Album> { it.releaseDate()?.year ?: it.year ?: Int.MAX_VALUE }
                .thenBy { it.releaseDate()?.month ?: 0 }
                .thenBy { it.releaseDate()?.day ?: 0 }
                .thenBy { it.name.lowercase() },
        )
    }

    suspend fun album(id: String): AlbumPage {
        val detail = call("getAlbum", "id" to id).album
            ?: error("Album was not returned by the server")
        return AlbumPage(detail.album(), detail.song.sortedWith(songOrder))
    }

    suspend fun song(id: String): Song = call("getSong", "id" to id).song
        ?: error("Song was not returned by the server")

    suspend fun setAlbumRating(id: String, rating: Int) {
        require(rating in 0..5) { "Album rating must be between 0 and 5" }
        callPost(
            "setRating",
            listOf("id" to id, "rating" to rating),
        )
    }

    suspend fun playlists(): List<Playlist> = call("getPlaylists")
        .playlists?.playlist.orEmpty()
        .sortedBy { it.name.lowercase() }

    suspend fun playlist(id: String): Playlist = call("getPlaylist", "id" to id).playlist
        ?: error("Playlist was not returned by the server")

    suspend fun search(query: String): SearchResult {
        if (query.isBlank()) return SearchResult()
        val result = call(
            "search3",
            "query" to query,
            "artistCount" to 20,
            "albumCount" to 40,
            "songCount" to 40,
        ).searchResult3 ?: SearchResult()
        return result.copy(artist = result.artist.filter(Artist::isAlbumArtistSearchResult))
    }

    suspend fun playQueue(): PlayQueue? = call("getPlayQueue").playQueue

    suspend fun scrobble(
        songId: String,
        submission: Boolean,
        time: Long = System.currentTimeMillis(),
    ) {
        call(
            "scrobble",
            "id" to songId,
            "submission" to submission,
            "time" to time,
        )
    }

    suspend fun savePlayQueue(songIds: List<String>, currentIndex: Int, positionMs: Long) {
        if (songIds.isEmpty()) {
            callPost("savePlayQueue", emptyList(), queueClientName)
            return
        }
        val parameters = buildList<Pair<String, Any?>> {
            songIds.forEach { add("id" to it) }
            add("current" to songIds[currentIndex.coerceIn(songIds.indices)])
            add("position" to positionMs)
        }
        callPost("savePlayQueue", parameters, queueClientName)
    }

    fun coverArtUrl(id: String?, size: Int = 600): String? = id?.let {
        endpoint("getCoverArt", listOf("id" to it, "size" to size)).toString()
    }

    fun streamUrl(id: String, mobile: Boolean): String = endpoint(
        "stream",
        listOf("id" to id, "format" to if (mobile) "opus" else "raw"),
    ).toString()

    private suspend fun call(
        endpoint: String,
        vararg parameters: Pair<String, Any?>,
    ): SubsonicResponse {
        check(isConfigured) { "Connect a Navidrome server first" }
        val request = Request.Builder()
            .url(endpoint(endpoint, parameters.toList()))
            .get()
            .build()

        return httpClient.newCall(request).await { response ->
            response.requireSuccess()
            val body = response.body.string()
            val decoded = json.decodeFromString<SubsonicEnvelope>(body).response
            if (decoded.status != "ok") {
                error(decoded.error?.message ?: "Navidrome request failed")
            }
            decoded
        }
    }

    private suspend fun callPost(
        endpoint: String,
        parameters: List<Pair<String, Any?>>,
        clientName: String = CLIENT_NAME,
    ): SubsonicResponse {
        check(isConfigured) { "Connect a Navidrome server first" }
        val salt = UUID.randomUUID().toString().replace("-", "").take(12)
        val token = md5(password + salt)
        val body = FormBody.Builder()
            .add("u", username)
            .add("t", token)
            .add("s", salt)
            .add("v", "1.16.1")
            .add("c", clientName)
            .add("f", "json")
            .apply {
                parameters.forEach { (name, value) ->
                    if (value != null) add(name, value.toString())
                }
            }
            .build()
        val request = Request.Builder()
            .url("$baseUrl/rest/$endpoint.view")
            .post(body)
            .build()

        return httpClient.newCall(request).await { response ->
            response.requireSuccess()
            val decoded = json.decodeFromString<SubsonicEnvelope>(response.body.string()).response
            if (decoded.status != "ok") {
                error(decoded.error?.message ?: "Navidrome request failed")
            }
            decoded
        }
    }

    private suspend fun <T> Call.await(transform: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val value = response.use(transform)
                            if (continuation.isActive) continuation.resume(value)
                        } catch (error: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                },
            )
        }

    private fun endpoint(endpoint: String, parameters: List<Pair<String, Any?>>): HttpUrl {
        check(baseUrl.isNotBlank()) { "Navidrome URL is not configured" }
        val salt = UUID.randomUUID().toString().replace("-", "").take(12)
        val token = md5(password + salt)
        return "$baseUrl/rest/$endpoint.view".toHttpUrl().newBuilder()
            .addQueryParameter("u", username)
            .addQueryParameter("t", token)
            .addQueryParameter("s", salt)
            .addQueryParameter("v", "1.16.1")
            .addQueryParameter("c", CLIENT_NAME)
            .addQueryParameter("f", "json")
            .apply {
                parameters.forEach { (name, value) ->
                    if (value != null) addQueryParameter(name, value.toString())
                }
            }
            .build()
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun Response.requireSuccess() {
        if (!isSuccessful) error("Navidrome returned HTTP $code")
    }

    companion object {
        const val CLIENT_NAME = "CodaAndroid"
        internal const val API_CALL_TIMEOUT_MILLIS = 20_000
        private const val ALL_ALBUMS_PAGE_SIZE = 500
        private const val MAX_ALL_ALBUM_PAGE_REQUESTS = 1_001
        private const val MAX_ALL_ALBUMS = 500_000
        internal val DEFAULT_HTTP_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(API_CALL_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
            .build()

        internal fun normalizeServerUrl(value: String): String {
            val candidate = value.trim().trimEnd('/').let {
                if (it.contains("://")) it else "https://$it"
            }
            val url = candidate.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Enter a valid server URL")
            require(url.scheme == "http" || url.scheme == "https") {
                "Server URL must use HTTP or HTTPS"
            }
            return url.toString().trimEnd('/')
        }

        private fun Album.releaseDate(): ItemDate? = originalReleaseDate ?: releaseDate

        private val songOrder = compareBy<Song> { it.discNumber ?: 1 }
            .thenBy { it.track ?: Int.MAX_VALUE }
            .thenBy { it.title.lowercase() }
    }
}
