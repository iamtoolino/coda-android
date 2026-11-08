package dev.coda.core.subsonic

import dev.coda.core.playback.ReplayGainMetadata
import dev.coda.core.testing.FakeSubsonicServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ScrobbleClientTest {
    @Test fun requestMatchesSwiftScrobbleRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""))
            val client = SubsonicClient(SubsonicConfig(server.url("/navidrome").toString(), "listener", "secret"))
            client.scrobble(" song/1 ", true, 1_721_234_567_890)
            val url = server.takeRequest().requestUrl!!
            assertEquals("/navidrome/rest/scrobble.view", url.encodedPath)
            assertEquals("song/1", url.queryParameter("id"))
            assertEquals("true", url.queryParameter("submission"))
            assertEquals("1721234567890", url.queryParameter("time"))
            assertEquals("listener", url.queryParameter("u"))
        }
    }

    @Test fun nowPlayingAndSubmissionReachTheFakeServer() = runBlocking {
        FakeSubsonicServer().use { server ->
            val client = SubsonicClient(SubsonicConfig(server.baseUrl, "synthetic", "synthetic-password"))
            client.scrobble("track-1", false, 10); client.scrobble("track-1", true, -5)
            assertEquals(listOf(FakeSubsonicServer.Scrobble("track-1", false, 10), FakeSubsonicServer.Scrobble("track-1", true, 0)),
                server.scrobbles.toList())
            server.failScrobbles = true
            val error = runCatching { client.scrobble("track-1", true, 1) }.exceptionOrNull()
            assertEquals(503, (error as SubsonicError.Http).status)
            assertThrows(SubsonicError.InvalidConfiguration::class.java) { runBlocking { client.scrobble("  ", true, 1) } }
            Unit
        }
    }

    @Test fun songReplayGainDecodesLossilyLikeSwift() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val rows = javaClass.getResourceAsStream("/scrobble/swift-golden.tsv")!!.bufferedReader().readLines()
            .filter { it.startsWith("lossy\t") }.map { it.split('\t') }
        assertEquals(6, rows.size)
        fun number(token: String) = token.takeIf { it != "-" }?.toDouble()
        for (r in rows) {
            val song = json.decodeFromString<Song>("""{"id":"s","title":"t","replayGain":${r[1]}}""")
            val expected = ReplayGainMetadata(number(r[2]), number(r[3]), number(r[4]), number(r[5]))
            assertEquals(r.toString(), expected, song.replayGain)
        }
        assertNull(json.decodeFromString<Song>("""{"id":"s","title":"t","replayGain":"loud"}""").replayGain)
        assertNull(json.decodeFromString<Song>("""{"id":"s","title":"t"}""").replayGain)
    }
}
