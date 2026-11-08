package dev.coda.core.subsonic

import dev.coda.core.testing.FakeSubsonicServer
import dev.coda.core.testing.SyntheticLibrary
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class SubsonicTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun config(server: String = "https://music.example.test/navidrome/") =
        SubsonicConfig(server, " synthetic ", "synthetic-password", "Coda on Test")

    @Test fun swiftGoldenParity() {
        val golden = json.parseToJsonElement(
            javaClass.getResource("/subsonic/swift-goldens.json")!!.readText(),
        ).jsonObject
        golden.getValue("urls").jsonArray.forEach { row ->
            val item = row.jsonObject
            val input = item.getValue("input").jsonPrimitive.content
            val expected = item.getValue("output")
            if (expected == JsonNull) {
                assertThrows(SubsonicError.InvalidConfiguration::class.java) { config(input) }
            } else {
                assertEquals(expected.jsonPrimitive.content, config(input).serverUrl)
            }
        }
        val songs = json.decodeFromJsonElement<List<Song>>(golden.getValue("songs"))
        assertEquals(golden.getValue("sortedIds").jsonArray.map { it.jsonPrimitive.content },
            AlbumOrdering.canonical(songs).map { it.id })
        golden.getValue("totals").jsonArray.forEach { row ->
            val item = row.jsonObject
            assertEquals(item.getValue("total").jsonPrimitive.intOrNull,
                resolvedAlbumTotalCount(item.getValue("header").jsonPrimitive.contentOrNull,
                    item.getValue("offset").jsonPrimitive.int,
                    item.getValue("returned").jsonPrimitive.int,
                    item.getValue("requested").jsonPrimitive.int))
        }
    }

    @Test fun authenticationAndEndpointPaths() {
        assertEquals("b305cadbb3bce54f3aa59c64fec00dea", SubsonicConfig.md5("passwordsalt"))
        val config = config()
        val url = config.streamUrl(" song/1 ", salt = "abc123")
        assertEquals("/navidrome/rest/stream.view", url.encodedPath)
        assertEquals("song/1", url.queryParameter("id"))
        assertEquals("synthetic", url.queryParameter("u"))
        assertEquals(SubsonicConfig.md5("synthetic-passwordabc123"), url.queryParameter("t"))
        assertEquals("abc123", url.queryParameter("s"))
        assertEquals("1.16.1", url.queryParameter("v"))
        assertEquals("Coda on Test", url.queryParameter("c"))
        assertEquals("json", url.queryParameter("f"))
        assertEquals("raw", url.queryParameter("format"))
        assertNull(url.queryParameter("maxBitRate"))
        assertNull(url.queryParameter("estimateContentLength"))
        val opus = config.streamUrl("song-1", "opus")
        assertEquals("opus", opus.queryParameter("format"))
        assertEquals("128", opus.queryParameter("maxBitRate"))
        assertNull(opus.queryParameter("estimateContentLength"))
        val cover = config.coverArtUrl("album/1", 600)
        assertEquals("/navidrome/rest/getCoverArt.view", cover.encodedPath)
        assertEquals("album/1", cover.queryParameter("id"))
        assertEquals("600", cover.queryParameter("size"))
        val salts = (1..32).map { config.endpointUrl("ping").queryParameter("s")!! }
        assertTrue(salts.all { it.matches(Regex("[0-9a-f]{12}")) })
        assertEquals(32, salts.toSet().size)
        assertEquals("Coda on Android", SubsonicConfig.makeClientName(null))
        assertEquals("Coda on Phone", SubsonicConfig.makeClientName(" Phone "))
        assertThrows(SubsonicError.InvalidConfiguration::class.java) { config.endpointUrl("bad/path") }
        assertThrows(SubsonicError.InvalidConfiguration::class.java) { config.streamUrl(" ") }
        assertThrows(SubsonicError.InvalidConfiguration::class.java) {
            SubsonicConfig("https://music.example.test", " ", "secret")
        }
        assertThrows(SubsonicError.InvalidConfiguration::class.java) {
            SubsonicConfig("https://music.example.test", "listener", "")
        }
    }

    @Test fun fakeServerIntegration() = runBlocking {
        FakeSubsonicServer().use { server ->
            val client = SubsonicClient(config(server.baseUrl))
            val details = client.ping()
            assertEquals("navidrome", details.software)
            assertEquals("1.16.1", details.apiVersion)
            assertEquals(true, details.supportsOpenSubsonic)
            val page = client.getAlbumList2()
            assertEquals(SyntheticLibrary.albums.map { it.id }, page.albums.map { it.id })
            assertEquals(2, page.totalCount)
            val album = client.getAlbum(page.albums.first().id)
            assertEquals(page.albums.first(), album.album)
            assertEquals(SyntheticLibrary.albums.first().tracks.map { it.id }, album.songs.map { it.id })
            val http = OkHttpClient()
            http.newCall(okhttp3.Request.Builder().url(client.coverArtUrl(album.album.id, 100)).build())
                .execute().use { assertArrayEquals(SyntheticLibrary.albums.first().coverBytes(), it.body!!.bytes()) }
            http.newCall(okhttp3.Request.Builder().url(client.streamUrl(album.songs.first().id)).build())
                .execute().use { assertArrayEquals(SyntheticLibrary.albums.first().tracks.first().bytes(), it.body!!.bytes()) }
            try { client.getAlbum("missing"); fail() } catch (_: SubsonicError.NotFound) { }
            try {
                SubsonicClient(SubsonicConfig(server.baseUrl, "synthetic", "wrong")).ping()
                fail()
            } catch (_: SubsonicError.Unauthorized) { }
        }
    }

    @Test fun pageParametersHeadersAndDiscMetadata() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = SubsonicClient(config(server.url("/").toString()))
            server.enqueue(ok("\"albumList2\":{\"album\":[{\"id\":\"one\",\"name\":\"One\"}]}" )
                .setHeader("X-Total-Count", "1250"))
            assertEquals(1250, client.getAlbumList2(size = 0, offset = -2).totalCount)
            val request = server.takeRequest().requestUrl!!
            assertEquals("/rest/getAlbumList2.view", request.encodedPath)
            assertEquals("newest", request.queryParameter("type"))
            assertEquals("1", request.queryParameter("size"))
            assertEquals("0", request.queryParameter("offset"))
            server.enqueue(ok("\"albumList2\":{}"))
            client.getAlbumList2(size = 999, offset = 3)
            assertEquals("500", server.takeRequest().requestUrl!!.queryParameter("size"))
            server.enqueue(ok(""""album":{"id":"one","name":"One","displayArtist":" Album Artist ",
                "isCompilation":true,"artists":[{"id":"artist","name":"Artist"}],
                "discTitles":[{"disc":0,"title":" First "},{"disc":1,"title":"Ignored"}],
                "song":[{"id":"b","title":"beta","track":1},
                {"id":"a","title":"Alpha","track":1,"type":"music","mediaType":"song"}]}"""))
            val page = client.getAlbum("one")
            assertEquals(listOf("a", "b"), page.songs.map { it.id })
            assertTrue(page.songs.all { it.discSubtitle == "First" && it.isCompilation == true })
            assertTrue(page.songs.all { it.albumArtistName == " Album Artist " && it.albumArtists == page.album.artists })
            assertEquals("music", page.songs.first().type)
            assertEquals("song", page.songs.first().mediaType)
        }
    }

    @Test fun errorMappingAndSafeDiagnostics() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = SubsonicClient(config(server.url("/").toString()))
            suspend fun check(response: MockResponse, expected: Class<out SubsonicError>): SubsonicError {
                server.enqueue(response)
                try { client.ping(); error("Expected failure") } catch (error: SubsonicError) {
                    assertTrue(error.javaClass.name, expected.isInstance(error))
                    assertFalse(error.stackTraceToString().contains("private server text"))
                    return error
                }
            }
            for (code in listOf(40, 41, 70, 0)) {
                val response = MockResponse().setBody("""{"subsonic-response":{"status":"failed",
                    "error":{"code":$code,"message":"private server text"}}}""")
                check(response, when (code) {
                    40, 41 -> SubsonicError.Unauthorized::class.java
                    70 -> SubsonicError.NotFound::class.java
                    else -> SubsonicError.Server::class.java
                })
            }
            check(MockResponse().setBody("""{"subsonic-response":{"status":"failed"}}"""), SubsonicError.Server::class.java)
            for (status in listOf(401, 403, 500, 503)) {
                val error = check(MockResponse().setResponseCode(status),
                    if (status < 500) SubsonicError.Unauthorized::class.java else SubsonicError.Http::class.java)
                if (error is SubsonicError.Http) assertEquals(status, error.status)
            }
            check(MockResponse().setBody("not json"), SubsonicError.Decoding::class.java)
            server.enqueue(ok("\"album\":{\"id\":\"one\"}"))
            try { client.getAlbum("one"); fail() } catch (_: SubsonicError.Decoding) { }
        }
    }

    @Test fun cancellationCancelsPendingCall() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val cancelled = java.util.concurrent.CountDownLatch(1)
            val http = OkHttpClient.Builder().eventListener(object : okhttp3.EventListener() {
                override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) {
                    cancelled.countDown()
                }
            }).build()
            val client = SubsonicClient(config(server.url("/").toString()), http)
            val job = async { client.ping() }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                assertTrue(cancelled.await(2, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
    }

    private fun ok(fields: String) = MockResponse().setBody(
        """{"subsonic-response":{"status":"ok",$fields}}""",
    )
}
