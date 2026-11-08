package dev.coda.core.subsonic

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class BookmarkClientTest {
    private val ok = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""
    private fun <T> withClient(block: suspend (SubsonicClient, MockWebServer) -> T): T = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody(ok))
            block(SubsonicClient(SubsonicConfig(server.url("/navidrome").toString(), "listener", "secret")), server)
        }
    }

    @Test fun createBookmarkUsesFormBody() = withClient { client, server ->
        client.createBookmark(" track/1 ", -5, """{"protocol":"album-resume-bookmark"}""")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/navidrome/rest/createBookmark.view", request.requestUrl!!.encodedPath)
        assertNull(request.requestUrl!!.queryParameter("id"))
        val body = okhttp3.HttpUrl.Builder().scheme("http").host("x").encodedQuery(request.body.readUtf8()).build()
        assertEquals("track/1", body.queryParameter("id"))
        assertEquals("0", body.queryParameter("position"))
        assertEquals("""{"protocol":"album-resume-bookmark"}""", body.queryParameter("comment"))
        assertEquals("listener", body.queryParameter("u"))
    }

    @Test fun deleteBookmarkSendsOnlyTheId() = withClient { client, server ->
        client.deleteBookmark(" track-1 ")
        val request = server.takeRequest()
        assertEquals("/navidrome/rest/deleteBookmark.view", request.requestUrl!!.encodedPath)
        val body = okhttp3.HttpUrl.Builder().scheme("http").host("x").encodedQuery(request.body.readUtf8()).build()
        assertEquals("track-1", body.queryParameter("id"))
        assertNull(body.queryParameter("position")); assertNull(body.queryParameter("comment"))
    }

    @Test fun emptyIdIsRejectedBeforeAnyRequest() {
        withClient { client, server ->
            assertThrows(SubsonicError.InvalidConfiguration::class.java) { runBlocking { client.deleteBookmark("  ") } }
            assertThrows(SubsonicError.InvalidConfiguration::class.java) { runBlocking { client.createBookmark("", 0, "c") } }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun getSongDecodesTheSong() {
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok","version":"1.16.1","song":{"id":"s1","title":"T","albumId":"a1","type":"music"}}}"""))
                val song = SubsonicClient(SubsonicConfig(server.url("/").toString(), "u", "p")).getSong("s1")
                assertEquals("a1", song.albumId); assertEquals("getSong.view", server.takeRequest().requestUrl!!.pathSegments.last())
            }
        }
    }
}
