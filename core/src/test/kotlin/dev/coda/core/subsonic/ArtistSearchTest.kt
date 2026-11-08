package dev.coda.core.subsonic

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ArtistSearchTest {
    @Test fun projectionSharesPrimaryNewestAndExcludesAppearancesById() {
        val artist = Artist("a", "Artist")
        val own = Album("own", "Own", artistId = "a", year = 2000)
        val guest = Album("guest", "Guest", artistId = "other", year = 2026)
        val projection = ArtistProjection.from(ArtistPage(artist, listOf(guest, own)), listOf(own, guest))
        assertEquals(listOf(own), projection.oldest)
        assertEquals(own, projection.newestRelease)
        assertEquals(listOf(guest), projection.appearances)
        assertSame(projection.newestRelease, projection.layout(true).albums.first())
        assertSame(projection.layout(false), projection.layout(false))
        assertSame(projection.layout(true).yearLabels, projection.layout(true).yearLabels)
    }
    /** Timeline: only the first row of a year shows it; undated releases sort last with "–" (once). */
    @Test fun timelineYearLabels() {
        val albums = listOf(Album("a", "A", year = 1999), Album("b", "B", year = 2001), Album("c", "C", year = 2001),
            Album("u1", "U1"), Album("u2", "U2"))
        val oldest = DiscographyLayout(ArtistDiscography.sorted(albums))
        assertEquals(listOf("1999", "2001", null, "–", null), oldest.yearLabels)
        val newest = DiscographyLayout(ArtistDiscography.sorted(albums, true))
        assertEquals(listOf("a", "u1", "u2"), listOf(newest.albums[2].id, newest.albums[3].id, newest.albums[4].id))
        assertEquals(listOf("2001", null, "1999", "–", null), newest.yearLabels)
        assertEquals(listOf("x"), DiscographyLayout(listOf(Album("x", "X", year = 2020))).albums.map { it.id })
        assertEquals(listOf("2020"), DiscographyLayout(listOf(Album("x", "X", year = 2020))).yearLabels)
        assertTrue(DiscographyLayout(emptyList()).yearLabels.isEmpty())
    }
    /** "Appears on" follows the page's year order. */
    @Test fun appearancesFollowTheSort() {
        val artist = Artist("a", "Artist")
        val old = Album("old", "Old", artistId = "x", year = 1990)
        val new = Album("new", "New", artistId = "y", year = 2020)
        val projection = ArtistProjection.from(ArtistPage(artist, listOf(new, old)))
        assertEquals(listOf("old", "new"), projection.appearancesLayout(false).albums.map { it.id })
        assertEquals(listOf("new", "old"), projection.appearancesLayout(true).albums.map { it.id })
    }
    @Test fun appearancesUseArtistIdentityRatherThanCompilationFlag() {
        val artist = Artist("a", "Artist")
        assertTrue(ArtistDiscography.isPrimaryRelease(Album("own", "Own compilation", artistId = "a", isCompilation = true), artist))
        assertFalse(ArtistDiscography.isPrimaryRelease(Album("guest", "Guest", artistId = "other"), artist))
        assertTrue(ArtistDiscography.isPrimaryRelease(Album("legacy", "Legacy", artist = "ARTIST"), artist))
    }
    @Test fun chronologyMatchesSwiftGeneratedGolden() {
        val fixture = Json.parseToJsonElement(javaClass.getResource("/artist/swift-sort.json")!!.readText()).jsonObject
        val albums = Json.decodeFromJsonElement<List<Album>>(fixture.getValue("albums"))
        val expected = fixture.getValue("oldest").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(expected, ArtistDiscography.sorted(albums).map { it.id })
        val newest = ArtistDiscography.sorted(albums, true)
        assertEquals(expected.filterNot { it.startsWith("unknown") }.reversed() + listOf("unknown-a", "unknown-z"), newest.map { it.id })
    }
    @Test fun swiftClientQueriesAndOptionalFields() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = SubsonicClient(SubsonicConfig(server.url("/").toString(), "synthetic", "synthetic"))
            server.enqueue(ok(""""artists":{"index":[{"artist":[{"id":"b","name":"beta"}]},{"artist":[{"id":"a","name":"Alpha"}]}]}"""))
            assertEquals(listOf("a", "b"), client.getArtists().map { it.id })
            assertEquals("/rest/getArtists.view", server.takeRequest().requestUrl!!.encodedPath)
            server.enqueue(ok(""""artist":{"id":"a","name":"Alpha","coverArt":"ar-a","artistImageUrl":"https://example.invalid/photo","album":[{"id":"late","name":"Late","year":2024},{"id":"early","name":"Early","year":1990}]}"""))
            val page = client.getArtist("a")
            assertEquals(listOf("early", "late"), page.albums.map { it.id }); assertEquals("ar-a", page.artist.coverArt)
            assertEquals("a", server.takeRequest().requestUrl!!.queryParameter("id"))
            assertEquals(SearchResults(), client.search3(" \n ")); assertEquals(2, server.requestCount)
            server.enqueue(ok(""""searchResult3":{"artist":[{"id":"zero","name":"Zero","albumCount":0},{"id":"unknown","name":"Unknown"}],"album":[{"id":"a","name":"Album"}],"song":[{"id":"s","title":"Song","artist":"Legacy","displayArtist":"A feat. B"}]}"""))
            val result = client.search3("  A & B  ", -5, 200, 40)
            assertEquals(listOf("unknown"), result.artists.map { it.id }); assertEquals("A feat. B", result.songs.single().artistName)
            assertEquals("a", result.albums.single().id)
            val url = server.takeRequest().requestUrl!!
            assertEquals("/rest/search3.view", url.encodedPath); assertEquals("A & B", url.queryParameter("query"))
            assertEquals("0", url.queryParameter("artistCount")); assertEquals("100", url.queryParameter("albumCount")); assertEquals("40", url.queryParameter("songCount"))
            server.enqueue(ok(""""searchResult3":{}""")); assertEquals(SearchResults(), client.search3("missing"))
            server.takeRequest()
            server.enqueue(ok(""""searchResult3":{"artist":null,"album":null,"song":null}""")); assertEquals(SearchResults(), client.search3("nulls")); server.takeRequest()
            server.enqueue(ok(""""artists":{}""")); assertTrue(client.getArtists().isEmpty()); server.takeRequest()
            server.enqueue(ok(""""artist":{"id":"empty","name":"Empty"}""")); assertTrue(client.getArtist("empty").albums.isEmpty()); server.takeRequest()
            server.enqueue(ok(""""other":true"""))
            try { client.getArtist("missing"); fail("Missing detail must fail") } catch (_: SubsonicError.Server) { }
        }
    }
    @Test fun searchDefaultLimitsAndAuthenticationErrors() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = SubsonicClient(SubsonicConfig(server.url("/").toString(), "synthetic", "synthetic"))
            server.enqueue(ok(""""searchResult3":{}""")); client.search3("test")
            val url = server.takeRequest().requestUrl!!
            assertEquals(listOf("16", "24", "40"), listOf("artistCount", "albumCount", "songCount").map { url.queryParameter(it) })
            for (call in listOf<suspend () -> Any>({ client.getArtists() }, { client.getArtist("a") }, { client.search3("test") })) {
                server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"failed","error":{"code":40}}}"""))
                try { call(); fail("Expected unauthorized") } catch (_: SubsonicError.Unauthorized) { }
            }
        }
    }
    private fun ok(fields: String) = MockResponse().setBody("""{"subsonic-response":{"status":"ok",$fields}}""")
}
