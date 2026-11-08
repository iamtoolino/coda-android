package dev.coda.core.testing

import kotlinx.serialization.encodeToString
import java.io.Closeable
import java.net.InetAddress
import java.security.MessageDigest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer

/** Loopback-only server, usable unchanged from JVM and app-process instrumented tests.
 * Credentials below are public synthetic test values, never a real server account.
 */
class FakeSubsonicServer(
    private val username: String = "synthetic",
    @Volatile var password: String = "synthetic-password",
    val streamMode: StreamMode = StreamMode.CONTENT_LENGTH,
    /** 0: any free port. the cold-start test binds a fixed one so a fresh process reaches the saved login's server again. */
    private val fixedPort: Int = 0,
) : Closeable {
    @Volatile var beforeEndpoint: ((String) -> Unit)? = null
    /** Runs for every request before it is checked (also unauthenticated or rejected ones); gets its URL incl. form parameters. */
    @Volatile var beforeRequest: ((okhttp3.HttpUrl) -> Unit)? = null
    @Volatile var albumPages: Map<String, dev.coda.core.subsonic.AlbumPage> = emptyMap()
    /** Songs answered by `getSong` (any other id: "not found", as before). */
    @Volatile var songPages: Map<String, dev.coda.core.subsonic.Song> = emptyMap()
    /** Every authenticated request as `endpoint`, `endpoint id` or `endpoint id size`, in arrival order. */
    val requestLog = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile var albumList: List<dev.coda.core.subsonic.Album>? = null
    /** Replaces the single synthetic artist in `getArtists` (large-library bucket tests). */
    @Volatile var artistList: List<dev.coda.core.subsonic.Artist>? = null
    /** > 0: `playlist-1` holds this many generated songs `pl-N` (covers `gen-(N mod 10)`, all streaming the first synthetic track). */
    /** Synthetic playlist overrides for playlist-page acceptance; never production library data. */
    @Volatile var playlistEntries: List<dev.coda.core.subsonic.Song>? = null
    @Volatile var albumMetadata: Map<String, dev.coda.core.subsonic.Album> = emptyMap()
    @Volatile var playlistName: String = "Synthetic mix"
    /** Non-null: `getPlaylists` answers these instead of the single `playlist-1` (PL-3 list tests; `getPlaylist` still serves only `playlist-1`). */
    @Volatile var playlistList: List<dev.coda.core.subsonic.Playlist>? = null
    @Volatile var playlistDeleted: Boolean = false
    @Volatile var stressPlaylistSize: Int = 0
    @Volatile var unsupportedSearch: Boolean = false
    @Volatile var searchSongs: List<dev.coda.core.subsonic.Song>? = null
    /** Search answers with songs only (no albums, no artists): the best match is a track. */
    @Volatile var searchSongsOnly: Boolean = false
    @Volatile var beforeSearch: (() -> Unit)? = null
    @Volatile var beforeStream: ((String) -> Unit)? = null
    @Volatile var streamOverrides: Map<String, Pair<ByteArray, String>> = emptyMap()
    @Volatile var streamAliases: Map<String, String> = emptyMap()
    /** [streamOverrides] ids served chunked without a length and ignoring Range (an unseekable fresh transcode). */
    @Volatile var chunkedOverrides: Set<String> = emptySet()
    @Volatile var rejectOpus = false
    /** Every stream request (song, requested format, Range header), and a server that ignores Range. */
    data class StreamRequest(val id: String, val format: String?, val range: String?, val timeOffset: String? = null)
    /** Advertised by `getOpenSubsonicExtensions` (null: the endpoint answers "not found", as a plain Subsonic server). */
    @Volatile var openSubsonicExtensions: List<String>? = null
    /**
     * Navidrome's `timeOffset` on a transcode: (song id, whole seconds) → the transcode started there, served like
     * a fresh transcode (chunked, no length, Range ignored). An offset not listed here is ignored (the whole override).
     */
    @Volatile var offsetStreams: Map<Pair<String, Int>, ByteArray> = emptyMap()
    val streamRequests = java.util.concurrent.CopyOnWriteArrayList<StreamRequest>()
    @Volatile var ignoreRange = false
    /** An ETag per [streamOverrides] song id, and a server that does not look at If-Range. */
    @Volatile var streamEtags: Map<String, String> = emptyMap()
    @Volatile var ignoreIfRange = false
    /** > 0: stream bodies arrive at this many bytes per 100 ms, so a download can be caught in flight. */
    @Volatile var streamThrottleBytes = 0L
    /** Every `getAlbumList2` request in arrival order (paging tests). */
    data class AlbumListRequest(val type: String, val size: Int, val offset: Int)
    val albumListRequests = java.util.concurrent.CopyOnWriteArrayList<AlbumListRequest>()
    @Volatile private var offline = false
    fun goOffline() { offline = true }
    fun goOnline() { offline = false }
    @Volatile var artistAlbums: List<dev.coda.core.subsonic.Album>? = null
    /** `getArtist` calls for artist-1 (a rebuilt artist page loads with a fresh view model). */
    val artistRequests = java.util.concurrent.atomic.AtomicInteger()
    /** A generated large library ([SyntheticLargeLibrary]) answers the library endpoints instead of the two synthetic albums. */
    @Volatile var largeLibrary: SyntheticLargeLibrary? = null
    private val randomRequests = java.util.concurrent.atomic.AtomicInteger()
    @Volatile var emptyAlbumListTypes: Set<String> = emptySet()
    @Volatile var emptyHomeExtras: Boolean = false
    @Volatile var playQueueOverride: dev.coda.core.subsonic.PlayQueue? = null
    data class SavedQueue(val ids: List<String>, val current: String?, val position: Long?, val clientName: String?)
    val savedQueues = java.util.concurrent.CopyOnWriteArrayList<SavedQueue>()
    @Volatile var homeQueueChanged: String = "2026-01-02"
    @Volatile var homeBookmarks: List<dev.coda.core.subsonic.Bookmark>? = null
    @Volatile var rejectedEndpoints: Set<String> = emptySet()
    /** Endpoints answered with HTTP 500, like a broken server (unlike [rejectedEndpoints], the session stays valid). */
    @Volatile var failingEndpoints: Set<String> = emptySet()
    @Volatile var coverlessAlbumIds: Set<String> = emptySet()
    /**
     * Cover ids as Navidrome names them: an album's `coverArt` is `al-<id>_<stamp>`, a song's `mf-<id>_<stamp>`
     * (neither is the album id). `getCoverArt` answers them and the bare album id with the album's cover.
     */
    @Volatile var navidromeCoverIds = false
    /** The `_<stamp>` of the Navidrome-style ids; changing it is a cover changed on the server. */
    @Volatile var coverStamp = "5f1a"
    private fun albumCoverId(albumId: String) = if (navidromeCoverIds) "al-${albumId}_$coverStamp" else albumId
    private fun songCoverId(album: SyntheticLibrary.Album, trackId: String) = if (navidromeCoverIds) "mf-${trackId}_$coverStamp" else album.id
    private fun coverOwner(id: String?): String? = if (id == null || !navidromeCoverIds) id else
        SyntheticLibrary.albums.find { album -> id == albumCoverId(album.id) || album.tracks.any { id == songCoverId(album, it.id) } }?.id ?: id
    @Volatile var coverOverrides: Map<String, ByteArray> = emptyMap()
    @Volatile var beforeCover: ((String, Int) -> Unit)? = null
    /** Every `getCoverArt` id in arrival order, and ids answered with HTTP 500 (a cover that fails to load). Ids `photo-*` serve a synthetic artist photo. */
    val coverRequests = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile var failingCoverIds: Set<String> = emptySet()
    /** Every accepted `scrobble` call, in arrival order. A rejected call (see [failScrobbles]) is not recorded. */
    data class Scrobble(val songId: String, val submission: Boolean, val timeMs: Long)
    val scrobbles = java.util.concurrent.CopyOnWriteArrayList<Scrobble>()
    /** Answers `scrobble` with HTTP 503, like an unreachable server, until cleared. */
    data class Rating(val id: String, val rating: Int)
    val ratings = java.util.concurrent.CopyOnWriteArrayList<Rating>()
    /** Song ids added and entry indexes removed through updatePlaylist. */
    val playlistAdditions = java.util.concurrent.CopyOnWriteArrayList<String>()
    val playlistRemovals = java.util.concurrent.CopyOnWriteArrayList<Int>()
    /** The default `playlist-1` (one song from each of the first two albums); removals change what `getPlaylist` answers. */
    private val defaultPlaylist = java.util.concurrent.CopyOnWriteArrayList(listOf(0, 1))
    /** Song ids `getPlaylist` answers now, in order (default playlist or [playlistEntries]). */
    val playlistSongIds: List<String> get() = playlistEntries?.map { it.id } ?: defaultPlaylist.map { SyntheticLibrary.albums[it].tracks[0].id }
    private fun removePlaylistEntry(index: Int) = synchronized(defaultPlaylist) {
        val entries = playlistEntries
        if (entries != null) { if (index in entries.indices) playlistEntries = entries.filterIndexed { i, _ -> i != index } }
        else if (index in defaultPlaylist.indices) defaultPlaylist.removeAt(index)
    }
    data class CreatedPlaylist(val name: String, val ids: List<String>)
    val createdPlaylists = java.util.concurrent.CopyOnWriteArrayList<CreatedPlaylist>()
    @Volatile var failRatings = false
    @Volatile var failPlaylistCreation = false
    @Volatile var failScrobbles: Boolean = false
    /** Song ID to `discNumber` in getAlbum (multi-disc fixtures); [discTitles] adds the album's `discTitles`. */
    @Volatile var songDiscs: Map<String, Int> = emptyMap()
    @Volatile var discTitles: Map<Int, String> = emptyMap()
    /** Song ID to OpenSubsonic replayGain JSON object (for example {"trackGain":-3,"albumGain":-6}) in getAlbum. */
    @Volatile var songReplayGain: Map<String, String> = emptyMap()
    enum class StreamMode { CONTENT_LENGTH, CHUNKED, CLOSE_DELIMITED }

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
        }
    }

    init { server.start(InetAddress.getByName("127.0.0.1"), fixedPort) }

    /** Server origin, as a user would enter it. Clients append `rest/<endpoint>.view` (NavidromeClient.swift convention). */
    val baseUrl: String get() = "http://127.0.0.1:${server.port}/"
    /** Loopback port (LG-1 [SchemeFrontDoor] forwards plain http here). */
    val port: Int get() = server.port
    /** `ping` requests without any credentials (the sign-in server check). */
    val anonymousPings = java.util.concurrent.atomic.AtomicInteger()

    /** Configurable salt for deterministic client tests; no production credentials needed. */
    fun authenticatedUrl(endpoint: String, salt: String = "synthetic-salt"): String =
        server.url("/rest/$endpoint.view").newBuilder()
            .host("127.0.0.1")
            .addQueryParameter("u", username)
            .addQueryParameter("t", token(password, salt))
            .addQueryParameter("s", salt)
            .addQueryParameter("v", "1.16.1")
            .addQueryParameter("c", "CodaTest")
            .addQueryParameter("f", "json")
            .build().toString()

    override fun close() = server.shutdown()

    private fun stream(url: okhttp3.HttpUrl, request: RecordedRequest): MockResponse {
        val wanted = url.queryParameter("id")
        url.queryParameter("timeOffset")?.toIntOrNull()?.takeIf { url.queryParameter("format") == "opus" }?.let { seconds ->
            offsetStreams[wanted.orEmpty() to seconds]?.let { bytes ->
                return MockResponse().setHeader("Content-Type", "audio/ogg").setChunkedBody(Buffer().write(bytes), 1024)
            }
        }
        streamOverrides[wanted]?.let { (bytes, type) ->
            // Like Navidrome's fresh transcode: chunked, no length, Range ignored (the stream cannot be seeked).
            if (wanted in chunkedOverrides) return MockResponse().setHeader("Content-Type", type).setChunkedBody(Buffer().write(bytes), 1024)
            val etag = streamEtags[wanted]
            // A validator that no longer matches (If-Range) is answered with the whole file, as a real server does.
            val changed = etag != null && !ignoreIfRange && request.getHeader("If-Range")?.let { it != etag } == true
            val response = request.getHeader("Range")?.takeUnless { ignoreRange || changed }?.let { range(bytes, type, it) }
                ?: binary(bytes, type).setHeader("Accept-Ranges", "bytes")
            return if (etag != null) response.setHeader("ETag", etag) else response
        }
        val track = SyntheticLibrary.albums.flatMap { it.tracks }
            .find { it.id == (streamAliases[wanted] ?: if (wanted?.startsWith("pl-") == true) "track-1" else wanted) }
            ?: return error(70, "Song not found")
        val opus = url.queryParameter("format") == "opus"
        if (opus && rejectOpus) return MockResponse().setResponseCode(415)
        val bytes = track.bytes(if (opus) "opus" else "raw")
        val type = if (opus) "audio/ogg" else "audio/flac"
        return if (!opus && request.getHeader("Range") != null && !ignoreRange) {
            range(bytes, type, request.getHeader("Range")!!)
        } else {
            val mode = if (opus) StreamMode.CHUNKED else streamMode
            when (mode) {
                StreamMode.CONTENT_LENGTH -> binary(bytes, type).setHeader("Accept-Ranges", "bytes")
                StreamMode.CHUNKED -> MockResponse().setHeader("Content-Type", type)
                    .setChunkedBody(Buffer().write(bytes), 1024)
                StreamMode.CLOSE_DELIMITED -> binary(bytes, type)
                    .removeHeader("Content-Length").setHeader("Connection", "close")
                    .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
            }
        }
    }

    private fun respond(request: RecordedRequest): MockResponse {
        if (request.requestUrl?.encodedPath?.endsWith("/stream.view") == true) {
            streamRequests += StreamRequest(request.requestUrl?.queryParameter("id").orEmpty(),
                request.requestUrl?.queryParameter("format"), request.getHeader("Range"), request.requestUrl?.queryParameter("timeOffset"))
            beforeStream?.invoke(request.requestUrl?.queryParameter("id").orEmpty())
        }
        if (offline) return MockResponse().setResponseCode(503)
        val requestUrl = request.requestUrl ?: return error(10, "Missing parameters")
        val url = if (request.method == "POST") requestUrl.newBuilder().encodedQuery(request.body.readUtf8()).build() else requestUrl
        beforeRequest?.invoke(url) // with a POST's form parameters merged in
        if (url.encodedPath.endsWith("/ping.view") && listOf("u", "t", "s", "p").all { url.queryParameter(it) == null }) {
            anonymousPings.incrementAndGet()
        }
        val salt = url.queryParameter("s")
        if (url.queryParameter("u") != username || salt.isNullOrEmpty() ||
            url.queryParameter("t") != token(password, salt)
        ) return error(40, "Wrong username or password")
        val endpoint = url.encodedPath.removePrefix("/rest/").removeSuffix(".view")
        beforeEndpoint?.invoke(endpoint)
        requestLog += listOfNotNull(endpoint, url.queryParameter("id"), url.queryParameter("size")).joinToString(" ")
        if (endpoint in rejectedEndpoints) return error(40, "Session expired")
        if (endpoint in failingEndpoints) return MockResponse().setResponseCode(500)
        largeLibrary?.let { library -> respondLarge(library, endpoint, url, request)?.let { return it } }
        return when (endpoint) {
            "setRating" -> {
                if (failRatings) return MockResponse().setResponseCode(503)
                ratings += Rating(url.queryParameter("id").orEmpty(), url.queryParameter("rating")?.toIntOrNull() ?: -1)
                json("")
            }
            "createPlaylist" -> {
                if (failPlaylistCreation) return MockResponse().setResponseCode(503)
                val name = url.queryParameter("name").orEmpty()
                val ids = url.queryParameterValues("songId").filterNotNull()
                createdPlaylists += CreatedPlaylist(name, ids)
                json(""","playlist":{"id":"playlist-1","name":${kotlinx.serialization.json.Json.encodeToString(name)},"songCount":${ids.size}}""")
            }
            "updatePlaylist" -> {
                if (url.queryParameter("playlistId") != "playlist-1") return error(70, "Playlist not found")
                url.queryParameter("name")?.let { playlistName = it }
                // "Add to playlist…" / "Remove from playlist".
                playlistAdditions += url.queryParameterValues("songIdToAdd").filterNotNull()
                url.queryParameter("songIndexToRemove")?.toIntOrNull()?.let { playlistRemovals += it; removePlaylistEntry(it) }
                json("")
            }
            "deletePlaylist" -> {
                if (url.queryParameter("id") != "playlist-1") return error(70, "Playlist not found")
                playlistDeleted = true
                json("")
            }
            "ping" -> json("")
            "getOpenSubsonicExtensions" -> openSubsonicExtensions?.let { names ->
                json(""","openSubsonicExtensions":[""" + names.joinToString { """{"name":"$it","versions":[1]}""" } + "]")
            } ?: error(70, "Endpoint not found")
            "getAlbumList2" -> {
                val offset = url.queryParameter("offset")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val size = url.queryParameter("size")?.toIntOrNull()?.coerceIn(0, 500) ?: 10
                albumListRequests += AlbumListRequest(url.queryParameter("type").orEmpty(), size, offset)
                val sourceAlbums = if (url.queryParameter("type") == "alphabeticalByName") SyntheticLibrary.albums.sortedBy { it.name.lowercase() } else SyntheticLibrary.albums
                val albums = if (url.queryParameter("type") in emptyAlbumListTypes) emptyList() else sourceAlbums.drop(offset).take(size)
                json(",\"albumList2\":{\"album\":[${albumList?.drop(offset)?.take(size)?.joinToString { kotlinx.serialization.json.Json.encodeToString(it) } ?: albums.joinToString { albumJson(it) }}]}")
            }
            "getArtists" -> json(artistList?.let { list -> ""","artists":{"index":[{"artist":${kotlinx.serialization.json.Json.encodeToString(list)}}]}""" }
                ?: ""","artists":{"index":[{"artist":[{"id":"artist-1","name":"Synthetic Artist","albumCount":2}]}]}""")
            "getArtist" -> {
                artistRequests.incrementAndGet()
                val albumsJson = artistAlbums?.joinToString { kotlinx.serialization.json.Json.encodeToString(it) } ?: SyntheticLibrary.albums.joinToString { albumJson(it) }
                // An artist from [artistList] keeps its own fields (coverArt present or absent).
                val listed = artistList?.find { it.id == url.queryParameter("id") }
                if (listed != null) return json(",\"artist\":" + kotlinx.serialization.json.Json.encodeToString(listed).dropLast(1) + ",\"album\":[$albumsJson]}")
                json(""","artist":{"id":"artist-1","name":"Synthetic Artist","albumCount":2,"album":[$albumsJson]}""")
            }
            "search3" -> {
                beforeSearch?.invoke()
                if (unsupportedSearch) return error(0, "Synthetic unsupported search")
                val offset = url.queryParameter("songOffset")?.toIntOrNull() ?: 0
                val count = url.queryParameter("songCount")?.toIntOrNull() ?: 40
                val songs = searchSongs?.drop(offset)?.take(count)?.joinToString { kotlinx.serialization.json.Json.encodeToString(it) }
                    ?: if (offset == 0) homeSong() else ""
                val query = url.queryParameter("query").orEmpty()
                // Generated large libraries (albumList/artistList) are searched by name.
                val generatedAlbums = albumList?.filter { query.isNotBlank() && it.name.contains(query, true) }.orEmpty()
                val generatedArtists = artistList?.filter { query.isNotBlank() && it.name.contains(query, true) }.orEmpty()
                if (listOf("synthetic", "signal", "colour").none { query.contains(it, true) } && (generatedAlbums.isNotEmpty() || generatedArtists.isNotEmpty())) {
                    val albumCount = url.queryParameter("albumCount")?.toIntOrNull() ?: 20
                    val artistCount = url.queryParameter("artistCount")?.toIntOrNull() ?: 20
                    return json(""","searchResult3":{"artist":${kotlinx.serialization.json.Json.encodeToString(generatedArtists.take(artistCount))},"album":${kotlinx.serialization.json.Json.encodeToString(generatedAlbums.take(albumCount))},"song":[]}""")
                }
                if (listOf("synthetic", "signal", "colour").none { query.contains(it, true) }) json(""","searchResult3":{}""")
                else if (searchSongsOnly) json(""","searchResult3":{"song":[$songs]}""")
                else json(""","searchResult3":{"artist":[{"id":"artist-1","name":"Synthetic Artist","albumCount":2}],"album":[${SyntheticLibrary.albums.joinToString { albumJson(it) }}],"song":[$songs]}""")
            }
            "getPlaylists" -> json(if (emptyHomeExtras) "" else playlistList?.let { list ->
                ""","playlists":{"playlist":${kotlinx.serialization.json.Json.encodeToString(list)}}"""
            } ?: ""","playlists":{"playlist":[{"id":"playlist-1","name":"Synthetic mix","songCount":2,"coverArt":"album-1"}]}""")
            "getPlaylist" -> {
                if (url.queryParameter("id") != "playlist-1") return error(70, "Playlist not found")
                playlistEntries?.let { entries ->
                    val name = kotlinx.serialization.json.Json.encodeToString(playlistName)
                    val encoded = kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(dev.coda.core.subsonic.Song.serializer()), entries)
                    return json(""","playlist":{"id":"playlist-1","name":$name,"songCount":${entries.size},"entry":$encoded}""")
                }
                if (stressPlaylistSize > 0) return json(""","playlist":{"id":"playlist-1","name":"Synthetic mix","songCount":$stressPlaylistSize,"coverArt":"gen-0","entry":[${
                    (0 until stressPlaylistSize).joinToString { i -> """{"id":"pl-$i","parent":"gen-${i % 10}","albumId":"gen-${i % 10}","title":"Stress $i","album":"Generated ${i % 10}","artist":"Synthetic Artist","coverArt":"gen-${i % 10}","track":${i + 1},"duration":30,"suffix":"flac","contentType":"audio/flac","isDir":false}""" }}]}""")
                // One song from each album, so playlist playback crosses album boundaries.
                val kept = defaultPlaylist.toList()
                val entries = kept.joinToString { a -> songJson(SyntheticLibrary.albums[a], SyntheticLibrary.albums[a].tracks[0], 0) }
                json(""","playlist":{"id":"playlist-1","name":"Synthetic mix","songCount":${kept.size},"coverArt":"album-1","entry":[$entries]}""")
            }
            "getSong" -> songPages[url.queryParameter("id")]?.let { json(",\"song\":" + kotlinx.serialization.json.Json.encodeToString(it)) }
                ?: error(70, "Song not found")
            "getBookmarks" -> json(homeBookmarks?.let { ""","bookmarks":{"bookmark":${kotlinx.serialization.json.Json.encodeToString(it)}}""" } ?: if (emptyHomeExtras) "" else
                ""","bookmarks":{"bookmark":[{"comment":${bookmarkComment()},"changed":"2026-01-02","entry":${homeSong()}}]}""")
            "savePlayQueue" -> {
                savedQueues += SavedQueue(url.queryParameterValues("id").filterNotNull(), url.queryParameter("current"),
                    url.queryParameter("position")?.toLongOrNull(), url.queryParameter("c"))
                json("")
            }
            "getPlayQueue" -> json(playQueueOverride?.let { ",\"playQueue\":" + kotlinx.serialization.json.Json.encodeToString(it) } ?: if (emptyHomeExtras) "" else
                ""","playQueue":{"current":"track-1","position":500,"changedBy":"Coda on Mac","changed":"$homeQueueChanged","entry":[${homeSong()}]}""")
            "getAlbum", "getCoverArt" -> {
                val id = url.queryParameter("id")
                if (endpoint == "getCoverArt" && id != null) {
                    coverRequests += id
                    if (id in failingCoverIds || id.startsWith("photo-")) {
                        beforeCover?.invoke(id, url.queryParameter("size")?.toIntOrNull() ?: 472)
                        return if (id in failingCoverIds) MockResponse().setResponseCode(500)
                            else binary(coverOverrides[id] ?: SyntheticLibrary.albums.first().coverBytes(), "image/jpeg")
                    }
                }
                if (endpoint == "getAlbum") albumPages[id]?.let { page ->
                    val encoded = kotlinx.serialization.json.Json.encodeToString(page.album).dropLast(1) +
                        ",\"song\":" + kotlinx.serialization.json.Json.encodeToString(page.songs) + "}"
                    return json(",\"album\":$encoded")
                }
                // Generated large-library albums (see [generatedAlbums]) share the first synthetic cover.
                val album = SyntheticLibrary.albums.find { it.id == (if (endpoint == "getCoverArt") coverOwner(id) else id) }
                    ?: SyntheticLibrary.albums.first().takeIf { endpoint == "getCoverArt" && id?.startsWith("gen-") == true }
                    ?: return error(70, "Album not found")
                if (endpoint == "getCoverArt") {
                    beforeCover?.invoke(id ?: album.id, url.queryParameter("size")?.toIntOrNull() ?: 472)
                    binary(coverOverrides[id] ?: coverOverrides[album.id] ?: album.coverBytes(), "image/jpeg")
                }
                else json(",\"album\":${albumMetadata[id]?.let { kotlinx.serialization.json.Json.encodeToString(it) } ?: albumJson(album, includeSongs = true)}")
            }
            "stream" -> stream(url, request).apply {
                if (streamThrottleBytes > 0) throttleBody(streamThrottleBytes, 100, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            "scrobble" -> {
                if (failScrobbles) return MockResponse().setResponseCode(503)
                scrobbles += Scrobble(url.queryParameter("id").orEmpty(), url.queryParameter("submission") == "true",
                    url.queryParameter("time")?.toLongOrNull() ?: -1)
                json("")
            }
            else -> error(70, "Endpoint not found")
        }
    }

    private fun respondLarge(library: SyntheticLargeLibrary, endpoint: String, url: okhttp3.HttpUrl, request: RecordedRequest): MockResponse? {
        val json = kotlinx.serialization.json.Json { explicitNulls = false }
        fun albums(list: List<dev.coda.core.subsonic.Album>) = json.encodeToString(list)
        return when (endpoint) {
            "getAlbumList2" -> {
                val offset = url.queryParameter("offset")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val size = url.queryParameter("size")?.toIntOrNull()?.coerceIn(0, 500) ?: 10
                val type = url.queryParameter("type").orEmpty()
                albumListRequests += AlbumListRequest(type, size, offset)
                val seed = if (type == "random") randomRequests.incrementAndGet() else 0
                json(",\"albumList2\":{\"album\":${albums(library.albumList(type, seed).drop(offset).take(size))}}")
            }
            "getArtists" -> json(""","artists":{"ignoredArticles":"${SyntheticLargeLibrary.IGNORED_ARTICLES}","index":[{"name":"*","artist":${json.encodeToString(library.artists)}}]}""")
            "getArtist" -> {
                val artist = library.artist(url.queryParameter("id").orEmpty()) ?: return error(70, "Artist not found")
                val encoded = json.encodeToString(artist).dropLast(1) + ",\"album\":" + albums(library.albumsOf(artist.id)) + "}"
                json(",\"artist\":$encoded")
            }
            "getAlbum" -> {
                val album = library.albums.firstOrNull { it.id == url.queryParameter("id") } ?: return null
                val songs = (1..2).joinToString { n -> """{"id":"${album.id}-t$n","title":"Part $n","album":${json.encodeToString(album.name)},"albumId":"${album.id}",
                    "artist":${json.encodeToString(album.artistName)},"artistId":"${album.artistId}","coverArt":"${album.coverArt}","track":$n,"duration":46,
                    "suffix":"flac","contentType":"audio/flac","isDir":false}""" }
                json(",\"album\":" + json.encodeToString(album).dropLast(1) + ",\"song\":[$songs]}")
            }
            "getCoverArt" -> url.queryParameter("id")?.takeIf { it.startsWith("lib-") }?.let { binary(coverOverrides[it] ?: SyntheticLargeLibrary.cover(it), "image/png") }
            "stream" -> url.queryParameter("id")?.takeIf { it.startsWith("lib-album-") }?.let {
                val bytes = SyntheticLibrary.albums.first().tracks.first().bytes("raw")
                request.getHeader("Range")?.let { range(bytes, "audio/flac", it) } ?: binary(bytes, "audio/flac").setHeader("Accept-Ranges", "bytes")
            }
            "search3" -> {
                val query = url.queryParameter("query").orEmpty()
                val artists = library.artists.filter { it.name.contains(query, true) }.take(url.queryParameter("artistCount")?.toIntOrNull() ?: 20)
                val found = library.albums.filter { it.name.contains(query, true) }.take(url.queryParameter("albumCount")?.toIntOrNull() ?: 20)
                // [searchSongs] whose title matches join the generated library's results (Search rows with albums and tracks).
                val songs = searchSongs?.filter { query.isNotBlank() && it.title.contains(query, true) }.orEmpty()
                if ((url.queryParameter("songOffset")?.toIntOrNull() ?: 0) > 0) json(""","searchResult3":{}""")
                else json(""","searchResult3":{"artist":${json.encodeToString(artists)},"album":${albums(found)},"song":${json.encodeToString(songs)}}""")
            }
            else -> null
        }
    }

    private fun homeSong() = """{"id":"track-1","title":"Signal One","album":"Synthetic Green",
        "albumId":"album-1","artist":"Synthetic Artist","displayArtist":"Synthetic Artist feat. Guest","coverArt":"album-1","duration":2,"type":"music","mediaType":"song"}"""
    private fun bookmarkComment() = """"{\"protocol\":\"album-resume-bookmark\",\"protocolVersion\":1,\"resumeSongId\":\"track-2\"}""""

    private fun range(bytes: ByteArray, type: String, header: String): MockResponse {
        val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header)
        val first = match?.groupValues?.get(1).orEmpty()
        val last = match?.groupValues?.get(2).orEmpty()
        val start = if (first.isEmpty()) {
            (bytes.size.toLong() - (last.toLongOrNull() ?: 0)).coerceAtLeast(0)
        } else first.toLongOrNull() ?: -1
        val end = if (first.isEmpty() || last.isEmpty()) bytes.lastIndex.toLong()
            else (last.toLongOrNull() ?: -1).coerceAtMost(bytes.lastIndex.toLong())
        if (match == null || (first.isEmpty() && last.isEmpty()) ||
            start !in 0L..bytes.lastIndex.toLong() || end < start
        ) return MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${bytes.size}")
        return binary(bytes.copyOfRange(start.toInt(), end.toInt() + 1), type)
            .setResponseCode(206).setHeader("Accept-Ranges", "bytes")
            .setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
    }

    private fun songJson(album: SyntheticLibrary.Album, track: SyntheticLibrary.Track, i: Int) =
        """{"id":"${track.id}","parent":"${album.id}","albumId":"${album.id}",
            |"title":"${track.title}","album":"${album.name}","artist":"${album.artist}","artistId":"artist-1",
            |"coverArt":"${songCoverId(album, track.id)}","track":${i + 1},"duration":${track.duration.toInt()},
            |"size":${track.bytes().size},"contentType":"audio/flac","suffix":"flac","isDir":false${songDiscs[track.id]?.let { ",\"discNumber\":$it" }.orEmpty()}${replayGainJson(track.id)}}
        """.trimMargin()

    private fun albumJson(album: SyntheticLibrary.Album, includeSongs: Boolean = false): String {
        val songs = if (!includeSongs) "" else ",\"song\":[" + album.tracks.mapIndexed { i, track ->
            songJson(album, track, i)
        }.joinToString() + "]"
        val cover = if (album.id in coverlessAlbumIds) "" else ",\"coverArt\":\"${albumCoverId(album.id)}\""
        val day = if (album.id == "album-1") 2 else 1
        return """{"id":"${album.id}","name":"${album.name}","artist":"${album.artist}","artistId":"artist-1",
            |"songCount":${album.tracks.size}$cover,
            |"duration":${album.tracks.sumOf { it.duration }.toInt()},
            |"created":"2026-01-0${day}T00:00:00Z"${discTitlesJson()}$songs}
        """.trimMargin()
    }

    private fun discTitlesJson() = if (discTitles.isEmpty()) "" else
        ",\"discTitles\":[" + discTitles.entries.joinToString { """{"disc":${it.key},"title":"${it.value}"}""" } + "]"

    private fun replayGainJson(songId: String) = songReplayGain[songId]?.let { ",\"replayGain\":$it" }.orEmpty()

    private fun binary(bytes: ByteArray, type: String) = MockResponse()
        .setHeader("Content-Type", type).setBody(Buffer().write(bytes))

    private fun json(fields: String, status: String = "ok") = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"subsonic-response":{"status":"$status","version":"1.16.1","type":"navidrome","serverVersion":"synthetic","openSubsonic":true$fields}}""")

    private fun error(code: Int, message: String) =
        json(",\"error\":{\"code\":$code,\"message\":\"$message\"}", "failed")

    companion object {
        /** [count] synthetic albums `gen-N` with deterministic, varied names (digits, symbols, accents, non-Latin, "The ..."). */
        fun generatedAlbums(count: Int): List<dev.coda.core.subsonic.Album> = (0 until count).map { i ->
            dev.coda.core.subsonic.Album(id = "gen-$i", name = generatedName(i, "Album"), artist = "Synthetic Artist", coverArt = "gen-$i")
        }

        fun generatedArtists(count: Int): List<dev.coda.core.subsonic.Artist> = (0 until count).map { i ->
            dev.coda.core.subsonic.Artist(id = "gen-artist-$i", name = generatedName(i, "Artist"), albumCount = 1)
        }

        private val prefixes = listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel", "India", "Juliet", "Kilo",
            "Lima", "Mike", "November", "Oscar", "Papa", "Quebec", "Romeo", "Sierra", "Tango", "Uniform", "Victor", "Whiskey", "Xray",
            "Yankee", "Zulu", "The Moon", "\u00c9clair", "\u00d8st", "\u65e5\u672c", "7 Seas", "!Bang")

        private fun generatedName(i: Int, kind: String) = "${prefixes[(i * 7) % prefixes.size]} $kind $i"

        fun token(password: String, salt: String): String = MessageDigest.getInstance("MD5")
            .digest((password + salt).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
