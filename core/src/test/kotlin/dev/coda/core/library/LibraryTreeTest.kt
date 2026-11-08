package dev.coda.core.library

import dev.coda.core.subsonic.SubsonicClient
import dev.coda.core.subsonic.SubsonicConfig
import dev.coda.core.testing.FakeSubsonicServer
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class LibraryTreeTest {
    private fun <T> withTree(block: suspend (LibraryTree) -> T): T = runBlocking {
        FakeSubsonicServer().use { server ->
            block(LibraryTree(SubsonicClient(SubsonicConfig(server.baseUrl, "synthetic", "synthetic-password"))))
        }
    }

    @Test fun rootHasFourTabsAndHomeHasTheMacShelves() = withTree { tree ->
        assertEquals(listOf("home", "albums", "artists", "playlists"), tree.children("root", 0, 50)!!.map { it.id })
        assertEquals(listOf("Home", "Albums", "Artists", "Playlists"), tree.children("root", 0, 50)!!.map { it.title })
        assertEquals(listOf("Recently Added", "Recent Releases", "Recently Played", "Continue Listening", "Playlists"),
            tree.children("home", 0, 50)!!.map { it.title })
        assertEquals(listOf("home/newest", "home/byYear", "home/recent", "home/continue", "playlists"),
            tree.children("home", 0, 50)!!.map { it.id })
        assertTrue(tree.children("root", 0, 50)!!.all { it.browsable && !it.playable })
    }

    @Test fun albumsAreBrowsableOnlyAndTracksAreLeaves() = withTree { tree ->
        val albums = tree.children("home/newest", 0, 50)!!
        assertEquals(listOf("album/album-1", "album/album-2"), albums.map { it.id })
        // Android Auto plays playable items on tap: albums must open their track list.
        assertTrue(albums.all { it.browsable && !it.playable && it.kind == LibraryNode.Kind.ALBUM })
        assertEquals("Synthetic Artist", albums[0].subtitle)
        assertEquals("album-1", albums[0].artworkId)
        val tracks = tree.children("album/album-1", 0, 50)!!
        assertEquals(listOf("track/track-1@album/album-1", "track/track-2@album/album-1"), tracks.map { it.id })
        assertTrue(tracks.all { it.playable && !it.browsable })
        assertEquals(LibraryNode.ChildStyle.LIST, tree.children("root", 0, 5)!![1].childStyle) // Albums tab: bucket folders have no cover
        assertNull(tree.children("track/track-1@album/album-1", 0, 50))
        assertNull(tree.children("nonsense", 0, 50))
    }

    @Test fun nodesWithoutACoverCarryNoArtworkId() = runBlocking {
        FakeSubsonicServer().use { server ->
            server.coverlessAlbumIds = setOf("album-2")
            val tree = LibraryTree(SubsonicClient(SubsonicConfig(server.baseUrl, "synthetic", "synthetic-password")))
            val albums = tree.children("home/newest", 0, 50)!!.associateBy { it.id }
            assertEquals("album-1", albums["album/album-1"]!!.artworkId)
            // Android Auto then shows its own default: no artwork URI is built for a null id.
            assertNull(albums["album/album-2"]!!.artworkId)
            assertNull(tree.item("album/album-2")!!.artworkId)
            val coverlessTracks = tree.children("album/album-2", 0, 50)!!
            val coveredTracks = tree.children("album/album-1", 0, 50)!!
            assertEquals(1, coverlessTracks.size)
            assertEquals(2, coveredTracks.size)
            assertTrue(coverlessTracks.all { it.artworkId == null })
            assertTrue(coveredTracks.all { it.artworkId == "album-1" })
            val playlist = tree.children("playlist/playlist-1", 0, 50)!!.associateBy { it.song!!.id }
            assertEquals("album-1", playlist["track-1"]!!.artworkId)
            assertNull(playlist["track-3"]!!.artworkId)
        }
    }

    @Test fun artistsLeadToAlbums() = withTree { tree ->
        val artist = tree.children("artists", 0, 50)!!.single()
        assertEquals("artist/artist-1", artist.id)
        assertTrue(artist.browsable && !artist.playable)
        assertEquals(setOf("album/album-1", "album/album-2"), tree.children(artist.id, 0, 50)!!.map { it.id }.toSet())
    }

    @Test fun playlistsAndContinueListening() = withTree { tree ->
        val playlist = tree.children("playlists", 0, 50)!!.single()
        assertEquals("playlist/playlist-1", playlist.id)
        assertEquals("2 songs", playlist.subtitle)
        assertEquals(listOf("track/track-1@playlist/playlist-1", "track/track-3@playlist/playlist-1"),
            tree.children(playlist.id, 0, 50)!!.map { it.id })
        val resume = tree.children("home/continue", 0, 50)!!.single()
        assertEquals("continue/album-1@track-2", resume.id)
        assertTrue(resume.playable)
    }

    @Test fun clientSidePagingSlicesAndPastTheEndIsEmpty() = withTree { tree ->
        assertEquals(listOf("home", "albums"), tree.children("root", 0, 2)!!.map { it.id })
        assertEquals(listOf("artists", "playlists"), tree.children("root", 1, 2)!!.map { it.id })
        assertEquals(emptyList<String>(), tree.children("root", 2, 2)!!.map { it.id })
        assertEquals(listOf("track/track-2@album/album-1"), tree.children("album/album-1", 1, 1)!!.map { it.id })
        // Negative pages and absurd sizes are clamped, never thrown.
        assertEquals(4, tree.children("root", -3, Int.MAX_VALUE)!!.size)
    }

    @Test fun serverSidePagingForwardsOffsetSizeAndType() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val tree = LibraryTree(SubsonicClient(SubsonicConfig(server.url("/").toString(), "synthetic", "synthetic")))
            for ((id, type) in listOf("home/newest" to "newest", "home/byYear" to "byYear",
                "home/recent" to "recent")) {
                server.enqueue(MockResponse().setBody(
                    """{"subsonic-response":{"status":"ok","version":"1.16.1","albumList2":{"album":[{"id":"a","name":"A"}]}}}"""))
                assertEquals(listOf("album/a"), tree.children(id, 3, 25)!!.map { it.id })
                val url = server.takeRequest().requestUrl!!
                assertEquals(type, url.queryParameter("type"))
                assertEquals("25", url.queryParameter("size"))
                assertEquals("75", url.queryParameter("offset"))
            }
        }
    }

    @Test fun playingATrackQueuesItsContainerFromThatTrack() = withTree { tree ->
        val album = tree.playback("track/track-2@album/album-1")!!
        assertEquals(listOf("track-1", "track-2"), album.songs.map { it.id })
        assertEquals(1, album.startIndex)
        assertEquals("album-1", album.album!!.id)
        assertNull(album.continuationAlbumId)
        val playlist = tree.playback("track/track-3@playlist/playlist-1")!!
        assertEquals(listOf("track-1", "track-3"), playlist.songs.map { it.id })
        assertEquals(1, playlist.startIndex)
        assertNull(playlist.album)
        assertEquals(0, tree.playback("album/album-2")!!.startIndex)
        assertEquals(2, tree.playback("playlist/playlist-1")!!.songs.size)
        assertNull(tree.playback("track/track-9@album/album-1"))
        assertNull(tree.playback("track/malformed"))
        assertNull(tree.playback("artist/artist-1"))
    }

    @Test fun continueListeningResumesTheSavedTrackWithAnExplicitContinuationHint() = withTree { tree ->
        val resume = tree.playback("continue/album-1@track-2")!!
        assertEquals(listOf("track-1", "track-2"), resume.songs.map { it.id })
        assertEquals(1, resume.startIndex)
        assertEquals("album-1", resume.continuationAlbumId)
        assertEquals(0, tree.playback("continue/album-1@missing")!!.startIndex)
    }

    @Test fun searchReturnsAlbumsSongsAndArtistsAndPages() = withTree { tree ->
        val results = tree.search("synthetic", 0, 50)
        assertEquals(listOf("album/album-1", "album/album-2", "track/track-1@album/album-1", "artist/artist-1"), results.map { it.id })
        assertEquals(listOf("album/album-2"), tree.search("synthetic", 1, 1).map { it.id })
        assertTrue(tree.search("zzz", 0, 50).isEmpty())
        assertTrue(tree.search("  ", 0, 50).isEmpty())
    }

    @Test fun itemLookupResolvesEveryIdKind() = withTree { tree ->
        for (id in listOf("root", "home", "home/continue", "albums", "album/album-1", "artist/artist-1",
            "playlist/playlist-1", "track/track-2@album/album-1", "continue/album-1@track-2")) {
            assertEquals(id, tree.item(id)!!.id)
        }
        assertNull(tree.item("mystery"))
        assertNull(tree.item("playlist/nope"))
        assertTrue(LibraryTree.isTreeId("track/x@album/y"))
        assertFalse(LibraryTree.isTreeId("song/x"))
    }
    @Test fun largePagesPreserveOffsetsAndSplitServerRequestsAt500() = runBlocking {
        val requests = mutableListOf<Pair<Int, Int>>()
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val url = request.requestUrl!!
                    val start = url.queryParameter("offset")!!.toInt()
                    val size = url.queryParameter("size")!!.toInt()
                    requests += start to size
                    val albums = (start until minOf(start + size, 2250)).joinToString { """{"id":"a$it","name":"Album $it"}""" }
                    return MockResponse().setBody("""{"subsonic-response":{"status":"ok","albumList2":{"album":[$albums]}}}""")
                }
            }
            server.start()
            val tree = LibraryTree(SubsonicClient(SubsonicConfig(server.url("/").toString(), "synthetic", "synthetic")))
            val page = tree.children("home/newest", 1, 1000)!!
            assertEquals(1000, page.size)
            assertEquals("album/a1000", page.first().id)
            assertEquals("album/a1999", page.last().id)
            assertEquals(listOf(1000 to 500, 1500 to 500), requests)
            requests.clear()
            val tail = tree.children("home/newest", 2, 1000)!!
            assertEquals(250, tail.size)
            assertEquals(listOf(2000 to 500), requests)
            assertTrue(tree.children("home/newest", Int.MAX_VALUE, 1000)!!.isEmpty())
            val visited = mutableListOf<String>()
            var parent = "home/newest"
            repeat(23) {
                val nodes = tree.children(parent, 0, Int.MAX_VALUE)!!
                assertTrue(nodes.size <= 101)
                visited += nodes.filter { it.kind != LibraryNode.Kind.FOLDER }.map { it.id }
                nodes.lastOrNull()?.takeIf { it.kind == LibraryNode.Kind.FOLDER }?.let {
                    assertEquals(it.id, tree.item(it.id)!!.id)
                    parent = it.id
                }
            }
            assertEquals((0 until 2250).map { "album/a$it" }, visited)
        }
    }

    @Test fun largeClientSideCollectionsRemainReachableAndUseRequestedOffsets() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val songs = (0 until 1200).joinToString { """{"id":"s$it","title":"Song $it"}""" }
                    val artists = (0 until 1200).joinToString { """{"id":"a$it","name":"Artist ${it.toString().padStart(4, '0')}"}""" }
                    val field = if (request.path!!.startsWith("/rest/getPlaylist"))
                        """"playlist":{"id":"p","name":"Mix","entry":[$songs]}"""
                    else """"artists":{"index":[{"artist":[$artists]}]}"""
                    return MockResponse().setBody("""{"subsonic-response":{"status":"ok",$field}}""")
                }
            }
            server.start()
            val tree = LibraryTree(SubsonicClient(SubsonicConfig(server.url("/").toString(), "synthetic", "synthetic")))
            for (parent in listOf("playlist/p")) {
                val modern = tree.children(parent, 1, 1000)!!
                assertEquals(200, modern.size)
                assertTrue(modern.first().id.contains("1000"))
                val leaves = mutableListOf<LibraryNode>()
                var id = parent
                repeat(12) {
                    val nodes = tree.children(id, 0, Int.MAX_VALUE)!!
                    assertTrue(nodes.size <= 101)
                    leaves += nodes.filter { it.kind != LibraryNode.Kind.FOLDER }
                    nodes.lastOrNull()?.takeIf { it.kind == LibraryNode.Kind.FOLDER }?.let { id = it.id }
                }
                assertEquals(1200, leaves.map { it.id }.distinct().size)
            }
        }
    }


    // ---- P5-2: A-Z buckets -------------------------------------------------------------------

    private fun <T> withBigLibrary(albums: Int, artists: Int, limit: Int = 100, block: suspend (LibraryTree) -> T): T = runBlocking {
        FakeSubsonicServer().use { server ->
            server.albumList = FakeSubsonicServer.generatedAlbums(albums)
            server.artistList = FakeSubsonicServer.generatedArtists(artists)
            block(LibraryTree(SubsonicClient(SubsonicConfig(server.baseUrl, "synthetic", "synthetic-password")), limit))
        }
    }

    /** Walks every folder below [id]; returns the leaf nodes and checks the limit on every list. */
    private suspend fun walk(tree: LibraryTree, id: String, limit: Int, out: MutableList<LibraryNode> = mutableListOf()): List<LibraryNode> {
        val nodes = tree.children(id, 0, Int.MAX_VALUE)!!
        assertTrue("$id has ${nodes.size}", nodes.size <= limit)
        for (n in nodes) if (n.kind == LibraryNode.Kind.FOLDER) {
            assertEquals(n.id, tree.item(n.id)!!.id)
            walk(tree, n.id, limit, out)
        } else out += n
        return out
    }

    @Test fun smallLibrariesStayFlat() = withBigLibrary(60, 40) { tree ->
        assertTrue(tree.children("albums", 0, 100)!!.all { it.kind == LibraryNode.Kind.ALBUM })
        assertEquals(60, tree.children("albums", 0, 100)!!.size)
        assertEquals(40, tree.children("artists", 0, Int.MAX_VALUE)!!.size)
        assertEquals(10, tree.children("albums", 1, 50)!!.size) // paging still works on a flat list
    }

    @Test fun emptyLibrariesHaveNoChildren() = withBigLibrary(0, 0) { tree ->
        assertTrue(tree.children("albums", 0, 100)!!.isEmpty())
        assertTrue(tree.children("artists", 0, 100)!!.isEmpty())
    }

    @Test fun largeAlbumLibraryIsBucketedAndEveryAlbumIsReachableOnceInOrder() = withBigLibrary(2000, 800) { tree ->
        val top = tree.children("albums", 0, Int.MAX_VALUE)!!
        assertTrue(top.all { it.kind == LibraryNode.Kind.FOLDER && it.browsable && !it.playable })
        val expectedLetters = FakeSubsonicServer.generatedAlbums(2000).map { AlphaBuckets.letter(it.name) }.distinct().sorted()
        assertEquals(expectedLetters.map { it.toString() }, top.map { it.title })
        assertEquals("#", top.first().title.first().toString())
        val leaves = walk(tree, "albums", 100)
        assertEquals(2000, leaves.size)
        assertEquals(2000, leaves.map { it.id }.distinct().size)
        assertEquals(leaves.map { it.title }, AlphaBuckets.sorted(leaves.map { it.title }, { it }, { it }))
        // Same for artists, and a paged host sees the same buckets.
        assertEquals(800, walk(tree, "artists", 100).size)
        val artistLetters = FakeSubsonicServer.generatedArtists(800).map { AlphaBuckets.letter(it.name) }.distinct().sorted()
        assertEquals(artistLetters.map { it.toString() }, tree.children("artists", 0, 100)!!.map { it.title })
        assertEquals(top.map { it.id }, tree.children("albums", 0, 50)!!.map { it.id })
    }

    @Test fun aSingleHugeLetterIsSplitIntoNumberedChunks() = runBlocking {
        FakeSubsonicServer().use { server ->
            server.albumList = (0 until 450).map { dev.coda.core.subsonic.Album("x$it", "Zed ${it.toString().padStart(3, '0')}") } +
                (0 until 30).map { dev.coda.core.subsonic.Album("b$it", "Bee $it") }
            val tree = LibraryTree(SubsonicClient(SubsonicConfig(server.baseUrl, "synthetic", "synthetic-password")), 100)
            val top = tree.children("albums", 0, Int.MAX_VALUE)!!
            assertEquals(listOf("B", "Z"), top.map { it.title })
            assertEquals(listOf("Z 1\u2013100", "Z 101\u2013200", "Z 201\u2013300", "Z 301\u2013400", "Z 401\u2013450"),
                tree.children("albums/b/Z", 0, Int.MAX_VALUE)!!.map { it.title })
            assertEquals(450 + 30, walk(tree, "albums", 100).size)
            assertEquals("album/x400", tree.children("albums/b/Z/4", 0, Int.MAX_VALUE)!!.first().id)
        }
    }

    @Test fun malformedBucketIdsAreUnknown() = withBigLibrary(500, 10) { tree ->
        for (id in listOf("albums/b/", "albums/b/AA", "albums/b/Z-A", "albums/b/a", "albums/b/A/x", "albums/b/A-C", "albums/b/A-C/1", "albums/b/A/1/2"))
            assertNull(id, tree.children(id, 0, 10))
        assertNull(tree.item("albums/b/Q-"))
    }

    // A bucket id the current tree wouldn't produce (an online bucket asked offline, or a letter or chunk that no
    // longer exists) answers the current tree's own Albums/Artists list, never that letter filtered under the old count.
    @Test fun anOutdatedBucketIdAnswersTheCurrentPlainList() = withBigLibrary(60, 40) { tree ->
        val plain = tree.children("albums", 0, 100)!!.map { it.id }
        assertEquals(60, plain.size)
        for (id in listOf("albums/b/A", "albums/b/C", "albums/b/A/1"))
            assertEquals(id, plain, tree.children(id, 0, 100)!!.map { it.id })
        assertEquals(tree.children("artists", 0, 100)!!.map { it.id }, tree.children("artists/b/A", 0, 100)!!.map { it.id })
    }

    @Test fun anOutdatedLetterOrChunkInABucketedTreeAnswersTheCurrentBuckets() = runBlocking {
        FakeSubsonicServer().use { server ->
            server.albumList = (0 until 450).map { dev.coda.core.subsonic.Album("x$it", "Zed ${it.toString().padStart(3, '0')}") } +
                (0 until 30).map { dev.coda.core.subsonic.Album("b$it", "Bee $it") }
            val tree = LibraryTree(SubsonicClient(SubsonicConfig(server.baseUrl, "synthetic", "synthetic-password")), 100)
            val buckets = tree.children("albums", 0, Int.MAX_VALUE)!!.map { it.id }
            assertEquals(listOf("albums/b/B", "albums/b/Z"), buckets)
            // Letter Q is empty, B has no chunks, Z has five.
            for (id in listOf("albums/b/Q", "albums/b/B/0", "albums/b/Z/5"))
                assertEquals(id, buckets, tree.children(id, 0, Int.MAX_VALUE)!!.map { it.id })
            // Current buckets still work.
            assertEquals(30, tree.children("albums/b/B", 0, Int.MAX_VALUE)!!.size)
            assertEquals("album/x400", tree.children("albums/b/Z/4", 0, Int.MAX_VALUE)!!.first().id)
        }
    }

    @Test fun listLimitIsConfigurable() = withBigLibrary(250, 5, limit = 40) { tree ->
        assertEquals(250, walk(tree, "albums", 40).size)
        assertTrue(tree.children("albums", 0, Int.MAX_VALUE)!!.all { it.kind == LibraryNode.Kind.FOLDER })
    }

    @Test fun bucketLettersFollowTheDocumentedSortKey() {
        fun l(s: String) = AlphaBuckets.letter(s)
        assertEquals('A', l("abba")); assertEquals('A', l("\u00c4rzte")); assertEquals('E', l("\u00c9clair"))
        assertEquals('O', l("\u00d8st")); assertEquals('A', l("\u00c5sa")); assertEquals('S', l("\u00dfeta"))
        assertEquals('#', l("7 Seas")); assertEquals('#', l("!Bang")); assertEquals('#', l("\u65e5\u672c")); assertEquals('#', l(""))
        assertEquals('T', l("The Moon")) // leading article is not ignored
        assertEquals(listOf("7 Seas", "Abba", "\u00c4rzte", "Bee"), AlphaBuckets.sorted(listOf("Bee", "\u00c4rzte", "Abba", "7 Seas"), { it }, { it }).let {
            // "Abba" < "\u00c4rzte" (key "arzte"): same letter, key order
            it
        })
    }

    /** The precomputed-key sort gives the order of the comparator it replaced (key computed per comparison). */
    @Test fun precomputedSortKeysKeepTheOrder() {
        val names = listOf("Bee", "\u00c4rzte", "Abba", "7 Seas", "", "abba", "ABBA", "\u00c9clair", "eclair", "The Moon",
            "\u00dfeta", "seta", "!Bang", "\u65e5\u672c", "  Zed", "zed", "\u00d8st", "ost", "Abba")
        val items = names.mapIndexed { i, n -> n to "id${(names.size - i) % 5}" }
        val reference = items.sortedWith(compareBy<Pair<String, String>>(
            { val l = AlphaBuckets.letter(it.first); if (l == AlphaBuckets.OTHER) 0 else l - 'A' + 1 },
            { AlphaBuckets.key(it.first) }, { it.first }, { it.second }))
        for (order in listOf(items, items.reversed(), items.shuffled(java.util.Random(7))))
            assertEquals(reference, AlphaBuckets.sorted(order, { it.first }, { it.second }))
    }

    /** The A-Z sort and bucketing of a request run on the tree's compute dispatcher, not the caller's. */
    @Test fun bucketRequestsSortOnTheComputeDispatcher() = runBlocking {
        FakeSubsonicServer().use { server ->
            server.albumList = FakeSubsonicServer.generatedAlbums(500)
            val dispatches = java.util.concurrent.atomic.AtomicInteger()
            val compute = object : kotlinx.coroutines.CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                    dispatches.incrementAndGet(); kotlinx.coroutines.Dispatchers.Default.dispatch(context, block)
                }
            }
            val client = SubsonicClient(SubsonicConfig(server.baseUrl, "synthetic", "synthetic-password"))
            val tree = LibraryTree(client.treeSource(), 100, compute = compute)
            val reference = LibraryTree(client, 100)
            val top = tree.children("albums", 0, Int.MAX_VALUE)!!
            assertTrue(dispatches.get() > 0)
            val before = dispatches.get()
            val bucket = tree.children(top[1].id, 0, Int.MAX_VALUE)!!
            assertTrue(dispatches.get() > before)
            // Same lists as before.
            assertEquals(reference.children("albums", 0, Int.MAX_VALUE), top)
            assertEquals(reference.children(top[1].id, 0, Int.MAX_VALUE), bucket)
        }
    }

    @Test fun partitionKeepsEveryPopulatedLetterSeparateRegardlessOfCounts() {
        val counts = mapOf('F' to 1, 'E' to 250, 'D' to 10, 'C' to 50, 'B' to 30, 'A' to 60, '#' to 5, 'Z' to 0)
        val buckets = AlphaBuckets.partition(counts)
        assertEquals(listOf("#", "A", "B", "C", "D", "E", "F"), buckets.map { it.id })
        assertEquals(buckets.map { it.id }, buckets.map { it.title })
        assertTrue(buckets.all { it.id.length == 1 })
        assertEquals(counts.values.sum(), buckets.sumOf { it.count })
        assertEquals(emptyList<AlphaBuckets.Bucket>(), AlphaBuckets.partition(emptyMap()))
    }
}
