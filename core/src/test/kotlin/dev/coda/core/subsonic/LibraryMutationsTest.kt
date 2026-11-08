package dev.coda.core.subsonic

import dev.coda.core.testing.FakeSubsonicServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LibraryMutationsTest {
    @Test fun playlistRenameAndDeleteUseTheServerMutationEndpoints() = runBlocking {
        FakeSubsonicServer().use { server ->
            val client = SubsonicClient(SubsonicConfig(server.baseUrl, "synthetic", "synthetic-password", "Test"))
            client.renamePlaylist("playlist-1", "  Albums & singles  ")
            assertEquals("Albums & singles", server.playlistName)
            client.deletePlaylist("playlist-1")
            assertTrue(server.playlistDeleted)
            server.failingEndpoints = setOf("updatePlaylist", "deletePlaylist")
            try { client.renamePlaylist("playlist-1", "Retry"); fail("Expected failure") } catch (_: SubsonicError.Http) { }
            try { client.deletePlaylist("playlist-1"); fail("Expected failure") } catch (_: SubsonicError.Http) { }
        }
    }

    @Test fun ratingsAndPlaylistPreserveDuplicatesAndOrder() = runBlocking {
        FakeSubsonicServer().use { server ->
            val client = SubsonicClient(SubsonicConfig(server.baseUrl, "synthetic", "synthetic-password", "Test"))
            client.setRating("album-1", 5); client.setRating("album-1", 0)
            assertEquals(listOf(FakeSubsonicServer.Rating("album-1", 5), FakeSubsonicServer.Rating("album-1", 0)), server.ratings)
            val ids = listOf("track-2", "track-1", "track-2")
            val playlist = client.createPlaylist("  Queue & test  ", ids)
            assertEquals("playlist-1", playlist.id)
            assertEquals(listOf(FakeSubsonicServer.CreatedPlaylist("Queue & test", ids)), server.createdPlaylists)
            server.failRatings = true
            try { client.setRating("album-1", 2); fail("Expected failure") } catch (_: SubsonicError.Http) { }
            server.failPlaylistCreation = true
            try { client.createPlaylist("Retry", ids); fail("Expected failure") } catch (_: SubsonicError.Http) { }
            assertEquals(1, server.createdPlaylists.size)
        }
    }
}
