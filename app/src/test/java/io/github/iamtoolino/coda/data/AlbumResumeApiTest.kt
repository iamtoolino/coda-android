package io.github.iamtoolino.coda.data

import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumResumeApiTest {
    @Test fun `bookmarks decode and mutations use form POST with zero position`() = runBlocking {
        val calls = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val endpoint = request.url.pathSegments.last()
            calls += endpoint
            if (request.method == "POST") {
                val form = request.body as FormBody
                val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
                assertEquals("first", fields["id"])
                if (endpoint == "createBookmark.view") {
                    assertEquals("0", fields["position"])
                    assertEquals("last", parseAlbumResumeMarker(fields["comment"])?.resumeSongId)
                }
            } else {
                assertEquals("getBookmarks.view", endpoint)
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(if (endpoint == "getBookmarks.view") {
                    """{"subsonic-response":{"status":"ok","bookmarks":{"bookmark":[{"entry":{"id":"first","title":"First","albumId":"album"},"changed":"2026-09-01T00:00:00Z"}]}}}""".toResponseBody()
                } else """{"subsonic-response":{"status":"ok"}}""".toResponseBody()).build()
        }.build()
        val client = NavidromeClient("https://example.test", "test", "test", http)
        assertEquals("first", client.bookmarks().single().entry?.id)
        client.createBookmark("first", requireNotNull(albumResumeComment("last")))
        client.deleteBookmark("first")
        assertEquals(listOf("getBookmarks.view", "createBookmark.view", "deleteBookmark.view"), calls)
    }

    @Test fun `canonical sort preserves explicit zeros missing defaults and server order ties`() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"subsonic-response":{"status":"ok","album":{"id":"album","name":"Album","coverArt":"canonical-cover","song":[
                    {"id":"z","title":"Same","track":1,"coverArt":"embedded-cover"},
                    {"id":"a","title":"Same","track":1},
                    {"id":"missing","title":"No number"},
                    {"id":"zero","title":"Zero","discNumber":0,"track":0},
                    {"id":"disc2","title":"Next Disc","discNumber":2,"track":1}
                ]}}}""".toResponseBody()).build()
        }.build()
        val page = NavidromeClient("https://example.test", "test", "test", http).album("album")
        assertEquals(listOf("zero", "z", "a", "missing", "disc2"), page.songs.map { it.id })
        assertTrue(page.songs.first().discNumber == 0)
        assertTrue(page.songs.all { it.albumArtworkId == page.album.artworkId })
        assertEquals("embedded-cover", page.songs.first { it.id == "z" }.coverArt)
    }
}
