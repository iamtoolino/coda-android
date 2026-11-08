package dev.coda.core.testing

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class FakeSubsonicServerTest {
    private val client = OkHttpClient()

    private fun get(url: String, range: String? = null) = client.newCall(
        Request.Builder().url(url).apply { if (range != null) header("Range", range) }.build(),
    ).execute()

    @Test fun everyEndpointAcceptsValidAndRejectsInvalidTokens() {
        FakeSubsonicServer().use { server ->
            for ((endpoint, query, type, expected) in listOf(
                listOf("ping", "", "application/json", "\"status\":\"ok\""),
                listOf("getAlbumList2", "&type=newest", "application/json", "\"album-2\""),
                listOf("getAlbum", "&id=album-1", "application/json", "\"track-2\""),
                listOf("getCoverArt", "&id=album-1", "image/jpeg", ""),
                listOf("stream", "&id=track-1&format=raw", "audio/flac", ""),
            )) {
                val url = server.authenticatedUrl(endpoint) + query
                assertTrue(url.startsWith(server.baseUrl))
                get(url).use { response ->
                    assertEquals(endpoint, 200, response.code)
                    assertEquals(type, response.header("Content-Type"))
                    val bytes = response.body!!.bytes()
                    when (endpoint) {
                        "getCoverArt" -> assertArrayEquals(SyntheticLibrary.albums[0].coverBytes(), bytes)
                        "stream" -> assertArrayEquals(SyntheticLibrary.albums[0].tracks[0].bytes(), bytes)
                        else -> assertTrue(String(bytes).contains(expected))
                    }
                }
                get(url.replace(Regex("t=[^&]+"), "t=invalid")).use { response ->
                    assertEquals(200, response.code) // Subsonic errors are in the envelope.
                    val body = response.body!!.string()
                    assertTrue(body.contains("\"status\":\"failed\""))
                    assertTrue(body.contains("\"code\":40"))
                }
            }
        }
    }

    @Test fun missingAndChangedSaltAreRejected() {
        FakeSubsonicServer().use { server ->
            for (url in listOf(server.baseUrl + "rest/ping.view",
                server.authenticatedUrl("ping").replace("s=synthetic-salt", "s=other"))) {
                get(url).use { assertTrue(it.body!!.string().contains("\"code\":40")) }
            }
        }
    }

    @Test fun baseUrlIsTheServerOriginUsingTheClientPathConvention() {
        FakeSubsonicServer().use { server ->
            assertTrue(server.baseUrl.endsWith(":${server.baseUrl.substringAfterLast(':').trimEnd('/')}/"))
            // Clients append rest/<endpoint>.view to the origin (NavidromeClient.swift); that must reach the fake.
            val url = server.baseUrl + "rest/" + server.authenticatedUrl("ping").substringAfter("/rest/")
            get(url).use { assertTrue(it.body!!.string().contains("\"status\":\"ok\"")) }
        }
    }

    @Test fun tokenIsMd5OfPasswordAndSalt() {
        assertEquals("5f4dcc3b5aa765d61d8327deb882cf99", FakeSubsonicServer.token("pass", "word"))
    }

    @Test fun rangeReturnsExactBytesIncludingOpenEndedAndSuffix() {
        FakeSubsonicServer().use { server ->
            val url = server.authenticatedUrl("stream") + "&id=track-1&format=raw"
            val bytes = SyntheticLibrary.albums[0].tracks[0].bytes()
            for ((header, start, end) in listOf(
                Triple("bytes=7-1030", 7, 1030),
                Triple("bytes=${bytes.size - 99}-", bytes.size - 99, bytes.lastIndex),
                Triple("bytes=-17", bytes.size - 17, bytes.lastIndex),
                Triple("bytes=${bytes.lastIndex}-999999", bytes.lastIndex, bytes.lastIndex),
            )) {
                get(url, header).use { response ->
                    assertEquals(206, response.code)
                    assertEquals("bytes $start-$end/${bytes.size}", response.header("Content-Range"))
                    assertArrayEquals(bytes.copyOfRange(start, end + 1), response.body!!.bytes())
                }
            }
            for (header in listOf("bytes=${bytes.size}-", "bytes=4-2", "bytes=-0", "bytes=-", "junk")) {
                get(url, header).use {
                    assertEquals(416, it.code)
                    assertEquals("bytes */${bytes.size}", it.header("Content-Range"))
                }
            }
        }
    }

    @Test fun lengthlessStreamsDeliverExactBodies() {
        for (mode in FakeSubsonicServer.StreamMode.entries) {
            FakeSubsonicServer(streamMode = mode).use { server ->
                for (format in listOf("raw", "opus")) {
                    get(server.authenticatedUrl("stream") + "&id=track-2&format=$format").use { response ->
                        assertEquals(200, response.code)
                        val chunked = format == "opus" || mode == FakeSubsonicServer.StreamMode.CHUNKED
                        if (chunked || mode == FakeSubsonicServer.StreamMode.CLOSE_DELIMITED) {
                            assertNull(response.header("Content-Length"))
                        }
                        if (chunked) assertEquals("chunked", response.header("Transfer-Encoding"))
                        assertArrayEquals(SyntheticLibrary.albums[0].tracks[1].bytes(format), response.body!!.bytes())
                    }
                }
            }
        }
    }

    @Test fun paginationAndSecondAlbumAndMissingIds() {
        FakeSubsonicServer().use { server ->
            get(server.authenticatedUrl("getAlbumList2") + "&offset=1&size=1").use {
                val body = it.body!!.string()
                assertFalse(body.contains("album-1"))
                assertTrue(body.contains("album-2"))
            }
            get(server.authenticatedUrl("getAlbum") + "&id=album-2").use {
                assertTrue(it.body!!.string().contains("track-3"))
            }
            get(server.authenticatedUrl("getCoverArt") + "&id=album-2").use {
                assertArrayEquals(SyntheticLibrary.albums[1].coverBytes(), it.body!!.bytes())
            }
            get(server.authenticatedUrl("stream") + "&id=track-3").use {
                assertArrayEquals(SyntheticLibrary.albums[1].tracks[0].bytes(), it.body!!.bytes())
            }
            for (endpoint in listOf("getAlbum", "getCoverArt", "stream")) {
                get(server.authenticatedUrl(endpoint) + "&id=missing").use {
                    assertTrue(it.body!!.string().contains("\"code\":70"))
                }
            }
        }
    }
}
