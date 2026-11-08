package dev.coda.core.subsonic

import dev.coda.core.testing.FakeSubsonicServer
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class HomeClientTest {
    @Test fun saveQueuePostsRepeatedIdsAndClientIdentityAndCanClearQueue() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = SubsonicClient(SubsonicConfig(server.url("/").toString(), "synthetic", "synthetic",
                clientName = "Coda on Test Phone"))
            for (ids in listOf(listOf("a", "b", "a"), emptyList())) {
                server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok"}}"""))
                client.savePlayQueue(ids, 99, 12345)
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("/rest/savePlayQueue.view", request.path)
                val form = server.url("/").newBuilder().encodedQuery(request.body.readUtf8()).build()
                assertEquals(ids, form.queryParameterValues("id"))
                assertEquals("Coda on Test Phone", form.queryParameter("c"))
                assertEquals(if (ids.isEmpty()) null else "a", form.queryParameter("current"))
                assertEquals(if (ids.isEmpty()) null else "12345", form.queryParameter("position"))
            }
        }
    }
    @Test fun queryTypesAndEmptyResponses() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = SubsonicClient(SubsonicConfig(server.url("/").toString(), "synthetic", "synthetic"))
            for (type in AlbumListType.entries) {
                server.enqueue(ok("\"albumList2\":{}"))
                assertTrue(client.getAlbumList2(type = type).albums.isEmpty())
                val url = server.takeRequest().requestUrl!!
                assertEquals(type.apiValue, url.queryParameter("type"))
                assertEquals(if (type == AlbumListType.RECENT_RELEASES) "3000" else null, url.queryParameter("fromYear"))
                assertEquals(if (type == AlbumListType.RECENT_RELEASES) "0" else null, url.queryParameter("toYear"))
            }
            server.enqueue(ok("\"playlists\":{}")); assertTrue(client.getPlaylists().isEmpty())
            assertEquals("/rest/getPlaylists.view", server.takeRequest().requestUrl!!.encodedPath)
            server.enqueue(ok("\"bookmarks\":{}")); assertTrue(client.getBookmarks().isEmpty())
            assertEquals("/rest/getBookmarks.view", server.takeRequest().requestUrl!!.encodedPath)
            server.enqueue(ok("\"playQueue\":{}")); assertNull(client.getPlayQueue()!!.currentSong)
            assertEquals("/rest/getPlayQueue.view", server.takeRequest().requestUrl!!.encodedPath)
            server.enqueue(ok("\"unknown\":true")); assertNull(client.getPlayQueue())
        }
    }

    @Test fun homeMetadataAndQueueIndexFallback() = runBlocking {
        FakeSubsonicServer().use { server ->
            val client = SubsonicClient(SubsonicConfig(server.baseUrl, "synthetic", "synthetic-password"))
            val playlist = client.getPlaylists().single()
            assertEquals("playlist-1", playlist.id); assertEquals(2, playlist.songCount)
            val bookmark = client.getBookmarks().single()
            assertNull(bookmark.position); assertEquals("album-1", bookmark.entry!!.albumId)
            assertTrue(bookmark.comment!!.contains("album-resume-bookmark"))
            val queue = client.getPlayQueue()!!
            assertEquals("track-1", queue.currentSong!!.id); assertEquals(500L, queue.position)
            assertEquals("Coda on Mac", queue.changedBy)
            val songs = listOf(Song("a", "A"), Song("b", "B"))
            assertEquals(1, PlayQueue(current = "b", currentIndex = -1, entry = songs).resolvedCurrentIndex)
            assertEquals(0, PlayQueue(current = "b", currentIndex = 0, entry = songs).resolvedCurrentIndex)
            assertEquals(0, PlayQueue(current = "missing", entry = songs).resolvedCurrentIndex)
            server.rejectedEndpoints = setOf("getBookmarks", "getPlayQueue", "getPlaylists")
            for (call in listOf<suspend () -> Any?>(client::getBookmarks, client::getPlayQueue, client::getPlaylists)) {
                try { call(); fail("Expected unauthorized") } catch (_: SubsonicError.Unauthorized) { }
            }
        }
    }
    private fun ok(fields: String) = MockResponse().setBody("""{"subsonic-response":{"status":"ok",$fields}}""")
}
