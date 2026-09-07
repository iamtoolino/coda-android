package io.github.iamtoolino.coda.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl

class NavidromeModelsTest {
    @Test
    fun `decodes optional server diagnostic fields without inventing support`() {
        val legacy = json.decodeFromString<SubsonicEnvelope>("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""").response
        assertEquals("1.16.1", legacy.version)
        assertNull(legacy.type)
        assertNull(legacy.serverVersion)
        assertNull(legacy.openSubsonic)
        val modern = json.decodeFromString<SubsonicEnvelope>("""{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"test-version","openSubsonic":true}}""").response
        assertEquals("navidrome", modern.type)
        assertEquals("test-version", modern.serverVersion)
        assertEquals(true, modern.openSubsonic)
    }

    @Test
    fun `default API client bounds complete calls`() {
        assertEquals(
            NavidromeClient.API_CALL_TIMEOUT_MILLIS,
            NavidromeClient.DEFAULT_HTTP_CLIENT.callTimeoutMillis,
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `normalizes server URLs for first login`() {
        assertEquals(
            "https://music.example.test",
            NavidromeClient.normalizeServerUrl(" music.example.test/ "),
        )
        assertEquals(
            "http://192.168.1.10:4533/navidrome",
            NavidromeClient.normalizeServerUrl("http://192.168.1.10:4533/navidrome/"),
        )
    }

    @Test
    fun `decodes album lists from the Subsonic envelope`() {
        val envelope = json.decodeFromString<SubsonicEnvelope>(
            """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "albumList2": {
                  "album": [
                    {
                      "id": "album-1",
                      "name": "Ecliptica",
                      "artist": "Sonata Arctica",
                      "year": 1999,
                      "originalReleaseDate": { "year": 1999, "month": 11, "day": 22 },
                      "created": "2026-07-10T10:00:00Z",
                      "coverArt": "al-album-1",
                      "userRating": 4
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        val album = requireNotNull(envelope.response.albumList2).album.single()
        assertEquals("Ecliptica", album.name)
        assertEquals("Sonata Arctica", album.artist)
        assertEquals(1999, album.year)
        assertEquals(11, album.originalReleaseDate?.month)
        assertEquals(4, album.userRating)
    }

    @Test
    fun `playlist entries retain exact server order`() {
        val envelope = json.decodeFromString<SubsonicEnvelope>(
            """
            {
              "subsonic-response": {
                "status": "ok",
                "playlist": {
                  "id": "playlist-1",
                  "name": "Albums",
                  "songCount": 3,
                  "entry": [
                    { "id": "song-3", "title": "Third", "album": "Album B", "albumId": "b" },
                    { "id": "song-1", "title": "First", "album": "Album A", "albumId": "a" },
                    { "id": "song-2", "title": "Second", "album": "Album A", "albumId": "a" }
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf("song-3", "song-1", "song-2"),
            requireNotNull(envelope.response.playlist).entry.map { it.id },
        )
    }

    @Test
    fun `song presentation prefers explicit album artist`() {
        val song = json.decodeFromString<Song>(
            """
            {
              "id": "track-1",
              "title": "Trust Me, I'm a Doctor!",
              "artist": "ZILF • Joe Campbell-Murray • Bret Ware",
              "displayAlbumArtist": "ZILF"
            }
            """.trimIndent(),
        )

        assertEquals("ZILF • Joe Campbell-Murray • Bret Ware", song.artist)
        assertEquals("ZILF", song.artistName)
    }

    @Test
    fun `song presentation falls back when album artist is absent or blank`() {
        assertEquals(
            "Track Artist",
            Song(id = "track-1", title = "Track", artist = "Track Artist").artistName,
        )
        assertEquals(
            "Track Artist",
            Song(
                id = "track-2",
                title = "Track",
                artist = "Track Artist",
                displayAlbumArtist = " ",
            ).artistName,
        )
    }

    @Test
    fun `album artwork uses album metadata while songs prefer album identity over embedded art`() {
        assertEquals(
            "cover-1",
            Album(id = "album-1", name = "Album", coverArt = "cover-1").artworkId,
        )
        assertEquals("album-1", Album(id = "album-1", name = "Album").artworkId)
        assertEquals(
            "album-1",
            Song(
                id = "track-1",
                title = "Track",
                albumId = "album-1",
                coverArt = "cover-1",
            ).albumArtworkId,
        )
        assertEquals(
            "album-1",
            Song(id = "track-1", title = "Track", albumId = "album-1").albumArtworkId,
        )
        assertEquals(
            "track-1",
            Song(id = "track-1", title = "Track").albumArtworkId,
        )
    }

    @Test
    fun `explicit canonical album cover survives song fallback and blank identities`() {
        val song = Song("track", "Track", albumId = "album", coverArt = "embedded")
        assertEquals("album", song.albumArtworkId)
        assertEquals("album-cover", song.copy(canonicalAlbumCoverArt = "album-cover").albumArtworkId)
        assertEquals("album", song.copy(canonicalAlbumCoverArt = " ").albumArtworkId)
        assertEquals("embedded", song.copy(albumId = " ").albumArtworkId)
        assertEquals("track", song.copy(albumId = null, coverArt = " ").albumArtworkId)
    }

    @Test
    fun `decodes cross client play queue position`() {
        val envelope = json.decodeFromString<SubsonicEnvelope>(
            """
            {
              "subsonic-response": {
                "status": "ok",
                "playQueue": {
                  "current": "song-2",
                  "position": 93210,
                  "changedBy": "Another client",
                  "entry": [
                    { "id": "song-1", "title": "First" },
                    { "id": "song-2", "title": "Second" }
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        val queue = requireNotNull(envelope.response.playQueue)
        assertEquals("song-2", queue.current)
        assertEquals(93_210L, queue.position)
        assertEquals("Another client", queue.changedBy)
    }

    @Test
    fun `save queue posts repeated ids current song and millisecond position`() = runBlocking {
        var captured: Request? = null
        val client = testClient(
            queueClientName = "Coda on Galaxy S25",
            onRequest = { request -> captured = request },
        )

        client.savePlayQueue(listOf("song-1", "song-2", "song-3"), 1, 93_210)

        val request = requireNotNull(captured)
        val form = request.body as FormBody
        assertEquals("https://music.example.test/rest/savePlayQueue.view", request.url.toString())
        assertEquals(listOf("song-1", "song-2", "song-3"), form.values("id"))
        assertEquals(listOf("song-2"), form.values("current"))
        assertEquals(listOf("93210"), form.values("position"))
        assertEquals(listOf("Coda on Galaxy S25"), form.values("c"))
    }

    @Test
    fun `empty save queue omits queue parameters to clear server state`() = runBlocking {
        var captured: Request? = null
        val client = testClient { request -> captured = request }

        client.savePlayQueue(emptyList(), 0, 0)

        val form = requireNotNull(captured).body as FormBody
        val names = (0 until form.size).map(form::name)
        assertFalse("id" in names)
        assertFalse("current" in names)
        assertFalse("position" in names)
    }

    @Test
    fun `album rating is posted to the server`() = runBlocking {
        var captured: Request? = null
        val client = testClient { request -> captured = request }

        client.setAlbumRating("album-1", 4)

        val request = requireNotNull(captured)
        val form = request.body as FormBody
        assertEquals("https://music.example.test/rest/setRating.view", request.url.toString())
        assertEquals(listOf("album-1"), form.values("id"))
        assertEquals(listOf("4"), form.values("rating"))
    }

    @Test
    fun `stream policy requests raw on wifi and opus on mobile without a bitrate`() {
        val client = testClient()

        val wifi = client.streamUrl("song-1", mobile = false).toHttpUrl()
        val mobile = client.streamUrl("song-1", mobile = true).toHttpUrl()

        assertEquals("raw", wifi.queryParameter("format"))
        assertEquals("opus", mobile.queryParameter("format"))
        assertEquals(null, mobile.queryParameter("estimateContentLength"))
        assertNull(wifi.queryParameter("maxBitRate"))
        assertNull(mobile.queryParameter("maxBitRate"))
    }

    @Test
    fun `release year album list requests newest release years first`() = runBlocking {
        var captured: Request? = null
        val client = testClient { request -> captured = request }

        client.albums(AlbumListType.RELEASE_YEAR, size = 24)

        val url = requireNotNull(captured).url
        assertEquals("byYear", url.queryParameter("type"))
        assertEquals("3000", url.queryParameter("fromYear"))
        assertEquals("0", url.queryParameter("toYear"))
        assertEquals("24", url.queryParameter("size"))
    }

    @Test
    fun `artist discography uses structured original release dates oldest first`() = runBlocking {
        val client = testClient(
            responseJson = """
                {
                  "subsonic-response": {
                    "status": "ok",
                    "artist": {
                      "id": "artist-1",
                      "name": "Artist",
                      "album": [
                        {
                          "id": "album-late",
                          "name": "Late",
                          "artist": "Artist",
                          "originalReleaseDate": { "year": 2001, "month": 10, "day": 2 }
                        },
                        {
                          "id": "album-early",
                          "name": "Early",
                          "artist": "Artist",
                          "originalReleaseDate": { "year": 2001, "month": 3, "day": 10 }
                        },
                        { "id": "album-undated", "name": "Undated", "artist": "Artist" }
                      ]
                    }
                  }
                }
            """.trimIndent(),
        )

        val albums = client.artistAlbums("artist-1").second

        assertEquals(listOf("album-early", "album-late", "album-undated"), albums.map { it.id })
    }

    @Test
    fun `album pagination rejects a repeated full page`() {
        var requests = 0
        val firstPage = (0 until 500).toList()

        val error = assertThrows(java.io.IOException::class.java) {
            runBlocking {
                collectAllPages(
                    pageSize = 500,
                    maximumPageRequests = 10,
                    maximumItems = 10_000,
                    itemId = Int::toString,
                ) {
                    requests++
                    firstPage
                }
            }
        }

        assertEquals(2, requests)
        assertEquals("Album pagination made no progress at offset 500", error.message)
    }

    @Test
    fun `album pagination preserves valid large progress and final partial page`() = runBlocking {
        val pages = ArrayDeque(
            listOf(
                (0 until 500).toList(),
                (500 until 1_000).toList(),
                listOf(1_000),
            ),
        )

        val result = collectAllPages(
            pageSize = 500,
            maximumPageRequests = 10,
            maximumItems = 10_000,
            itemId = Int::toString,
        ) { pages.removeFirst() }

        assertEquals(1_001, result.size)
        assertEquals(1_000, result.last())
    }

    @Test
    fun `album pagination enforces a proportional item bound`() {
        assertThrows(java.io.IOException::class.java) {
            runBlocking {
                collectAllPages(
                    pageSize = 2,
                    maximumPageRequests = 10,
                    maximumItems = 3,
                    itemId = Int::toString,
                ) { offset -> listOf(offset, offset + 1) }
            }
        }
    }

    private fun testClient(
        responseJson: String =
            """{"subsonic-response":{"status":"ok","version":"1.16.1"}}""",
        queueClientName: String = NavidromeClient.CLIENT_NAME,
        onRequest: (Request) -> Unit = {},
    ): NavidromeClient {
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                onRequest(request)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        responseJson.toResponseBody("application/json".toMediaType()),
                    )
                    .build()
            }
            .build()
        return NavidromeClient(
            serverUrl = "https://music.example.test",
            username = "user",
            password = "password",
            httpClient = httpClient,
            queueClientName = queueClientName,
        )
    }

    private fun FormBody.values(name: String): List<String> =
        (0 until size).filter { this.name(it) == name }.map { value(it) }
}
