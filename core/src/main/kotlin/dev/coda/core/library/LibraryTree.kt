package dev.coda.core.library

import dev.coda.core.subsonic.*
import dev.coda.core.sync.albumResumeItems

/** Browse-tree node, independent of Media3 so structure and paging are JVM-testable. */
data class LibraryNode(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val artworkId: String? = null,
    val kind: Kind,
    val browsable: Boolean,
    val playable: Boolean,
    /** How a host should lay out this node's children. */
    val childStyle: ChildStyle? = null,
    val song: Song? = null,
) {
    enum class Kind { FOLDER, ALBUM, ARTIST, PLAYLIST, TRACK, RESUME }
    enum class ChildStyle { GRID, LIST }
}

/** What to queue for a playable node. `album` supplies per-queue-entry artwork and rating, as the phone UI does. */
data class PlaybackRequest(
    val songs: List<Song>,
    val startIndex: Int,
    val album: Album? = null,
    val continuationAlbumId: String? = null,
)

/**
 * What the browse tree reads: the server ([SubsonicClient.treeSource]) or the phone's offline catalogue
 * ([PhoneTreeSource]). The calls and their meaning are the server's; a missing item throws [SubsonicError.NotFound].
 */
interface TreeSource {
    suspend fun getAlbumList2(size: Int, offset: Int, type: AlbumListType): AlbumListPage
    suspend fun getArtists(): List<Artist>
    suspend fun getArtist(id: String): ArtistPage
    suspend fun getAlbum(id: String): AlbumPage
    suspend fun getPlaylists(): List<Playlist>
    suspend fun getPlaylist(id: String): PlaylistPage
    suspend fun getBookmarks(): List<Bookmark>
    suspend fun search3(query: String, artistCount: Int, albumCount: Int, songCount: Int): SearchResults
    /** Home offers Continue Listening (server bookmarks). The phone has none offline, so it hides the shelf, as its Home does. */
    val continueListening: Boolean get() = true
    /** A playlist's subtitle. */
    fun playlistSongs(playlist: Playlist): String? = playlist.songCount?.let(::songsLine)
}

internal fun songsLine(n: Int) = if (n == 1) "1 song" else "$n songs"

fun SubsonicClient.treeSource(): TreeSource = object : TreeSource {
    override suspend fun getAlbumList2(size: Int, offset: Int, type: AlbumListType) = this@treeSource.getAlbumList2(size, offset, type)
    override suspend fun getArtists() = this@treeSource.getArtists()
    override suspend fun getArtist(id: String) = this@treeSource.getArtist(id)
    override suspend fun getAlbum(id: String) = this@treeSource.getAlbum(id)
    override suspend fun getPlaylists() = this@treeSource.getPlaylists()
    override suspend fun getPlaylist(id: String) = this@treeSource.getPlaylist(id)
    override suspend fun getBookmarks() = this@treeSource.getBookmarks()
    override suspend fun search3(query: String, artistCount: Int, albumCount: Int, songCount: Int) =
        this@treeSource.search3(query, artistCount, albumCount, songCount)
}

/** Architecture §13. IDs: `home`, `home/{newest,byYear,recent,continue}`, `albums`, `artists`, `playlists`,
 * `artist/{id}`, `album/{id}`, `playlist/{id}`, `track/{songId}@album/{albumId}`, `track/{songId}@playlist/{id}`,
 * `continue/{albumId}@{resumeSongId}`. */
class LibraryTree(
    private val client: TreeSource,
    /** Largest list a host may show. Albums/Artists above it become A-Z bucket folders. */
    limit: Int = AlphaBuckets.DEFAULT_LIMIT,
    private val index: LibraryIndex = LibraryIndex(),
    /** Where the A-Z sort and bucketing run; never the caller's (Main for the Auto callback). */
    private val compute: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Dispatchers.Default,
) {
    constructor(client: SubsonicClient, limit: Int = AlphaBuckets.DEFAULT_LIMIT, index: LibraryIndex = LibraryIndex()) :
        this(client.treeSource(), limit, index)

    private val limit = limit.coerceAtLeast(AlphaBuckets.MIN_LIMIT)

    companion object {
        const val ROOT = "root"
        const val HOME = "home"
        const val ALBUMS = "albums"
        const val ARTISTS = "artists"
        const val PLAYLISTS = "playlists"
        private const val NEWEST = "home/newest"
        private const val BY_YEAR = "home/byYear"
        private const val RECENT = "home/recent"
        private const val CONTINUE = "home/continue"
        const val MAX_PAGE_SIZE = 500
        const val LEGACY_PAGE_SIZE = AlphaBuckets.DEFAULT_LIMIT // Default host list limit; unpaged replies are cut to the tree's limit.
        private const val CONTINUE_LIMIT = 20
        private const val SEARCH_ARTISTS = 10
        private const val SEARCH_ALBUMS = 20
        private const val SEARCH_SONGS = 40

        /** Items an Auto controller may hand back to the session as playable requests (as opposed to queue items). */
        fun isTreeId(id: String): Boolean = id.startsWith("album/") || id.startsWith("playlist/") ||
            id.startsWith("track/") || id.startsWith("continue/")

        fun trackId(songId: String, containerId: String): String = "track/$songId@$containerId"
        fun albumId(id: String) = "album/$id"
        fun playlistId(id: String) = "playlist/$id"
        fun artistId(id: String) = "artist/$id"
        fun continueId(albumId: String, resumeSongId: String) = "continue/$albumId@$resumeSongId"

        private fun folder(id: String, title: String, style: LibraryNode.ChildStyle?, browsable: Boolean = true) =
            LibraryNode(id, title, kind = LibraryNode.Kind.FOLDER, browsable = browsable, playable = false, childStyle = style)

        val root = folder(ROOT, "Coda", LibraryNode.ChildStyle.LIST)
        /** The four root tabs, in order. */
        val tabs = listOf(
            folder(HOME, "Home", LibraryNode.ChildStyle.LIST),
            // A list: its children are A-Z bucket folders for large libraries, and a grid tile without a cover shows a warning icon on Auto.
            folder(ALBUMS, "Albums", LibraryNode.ChildStyle.LIST),
            folder(ARTISTS, "Artists", LibraryNode.ChildStyle.LIST),
            folder(PLAYLISTS, "Playlists", LibraryNode.ChildStyle.GRID),
        )
        /** macOS order, minus the handoff card. */
        val homeShelves = listOf(
            folder(NEWEST, "Recently Added", LibraryNode.ChildStyle.GRID),
            folder(BY_YEAR, "Recent Releases", LibraryNode.ChildStyle.GRID),
            folder(RECENT, "Recently Played", LibraryNode.ChildStyle.GRID),
            folder(CONTINUE, "Continue Listening", LibraryNode.ChildStyle.GRID),
            folder(PLAYLISTS, "Playlists", LibraryNode.ChildStyle.GRID),
        )
        private val shelfTypes = mapOf(NEWEST to AlbumListType.NEWEST, BY_YEAR to AlbumListType.RECENT_RELEASES,
            RECENT to AlbumListType.RECENTLY_PLAYED)

        private fun page(list: List<LibraryNode>, page: Int, pageSize: Int): List<LibraryNode> {
            val size = pageSize.coerceAtLeast(1)
            return list.drop(offset(page, size)).take(size)
        }
        private fun offset(page: Int, size: Int): Int =
            page.coerceAtLeast(0).toLong().times(size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    // Albums and playlists are browsable only: Android Auto plays any item flagged playable when it is tapped, even a
    // browsable one, so a playable album could never show its tracks (DHU walk). Their tracks play the container
    // from that track; `playback()` still accepts album and playlist ids (voice, resume, other controllers).
    fun albumNode(album: Album) = LibraryNode(albumId(album.id), album.name,
        listOfNotNull(album.artistName.takeIf { it.isNotEmpty() }, album.releaseYear?.toString()).joinToString(" · ").ifEmpty { null },
        album.artworkId, LibraryNode.Kind.ALBUM, browsable = true, playable = false, childStyle = LibraryNode.ChildStyle.LIST)

    fun playlistNode(playlist: Playlist) = LibraryNode(playlistId(playlist.id), playlist.name,
        client.playlistSongs(playlist), playlist.coverArt,
        LibraryNode.Kind.PLAYLIST, browsable = true, playable = false, childStyle = LibraryNode.ChildStyle.LIST)

    private fun artistNode(artist: Artist) = LibraryNode(artistId(artist.id), artist.name,
        artist.albumCount?.let { if (it == 1) "1 album" else "$it albums" }, artist.coverArt,
        LibraryNode.Kind.ARTIST, browsable = true, playable = false, childStyle = LibraryNode.ChildStyle.GRID)

    fun trackNode(song: Song, containerId: String) = LibraryNode(trackId(song.id, containerId), song.title,
        song.artistName.takeIf { it.isNotEmpty() }, song.albumArtworkId, LibraryNode.Kind.TRACK,
        browsable = false, playable = true, song = song)

    private fun resumeNode(item: dev.coda.core.sync.AlbumResumeItem) = LibraryNode(
        continueId(item.album.id, item.resumeSongId), item.album.name, item.album.artistName.takeIf { it.isNotEmpty() },
        item.album.artworkId, LibraryNode.Kind.RESUME, browsable = false, playable = true)

    /** One node for onGetItem; null when the ID is unknown or no longer exists on the server. */
    suspend fun item(id: String): LibraryNode? = when {
        id == ROOT -> root
        id.startsWith("range/") -> range(id)?.let { folder(id, "More", LibraryNode.ChildStyle.LIST) }
        bucketTitle(id) != null -> folder(id, bucketTitle(id)!!, if (id.startsWith("$ALBUMS/")) LibraryNode.ChildStyle.GRID else LibraryNode.ChildStyle.LIST)
        else -> tabs.find { it.id == id } ?: homeShelves.find { it.id == id } ?: when {
            id.startsWith("album/") -> client.getAlbum(id.removePrefix("album/")).let { albumNode(it.album) }
            id.startsWith("artist/") -> client.getArtist(id.removePrefix("artist/")).let { artistNode(it.artist) }
            id.startsWith("playlist/") -> client.getPlaylists().find { it.id == id.removePrefix("playlist/") }?.let(::playlistNode)
            id.startsWith("track/") -> splitTrack(id)?.let { (songId, container) ->
                playback(id)?.songs?.find { it.id == songId }?.let { trackNode(it, container) }
            }
            id.startsWith("continue/") -> client.getAlbum(id.removePrefix("continue/").substringBeforeLast('@')).let {
                resumeNode(dev.coda.core.sync.AlbumResumeItem(it.album, "", id.substringAfterLast('@'), ""))
            }
            else -> null
        }
    }

    /** Children for onGetChildren, 0-based `page`. Null means the ID has no children (unknown or a leaf). */
    suspend fun children(id: String, page: Int, pageSize: Int): List<LibraryNode>? {
        val (parent, base) = if (id.startsWith("range/")) range(id) ?: return null else id to 0
        val size = pageSize.coerceAtLeast(1)
        val start = base.toLong() + page.coerceAtLeast(0).toLong() * size
        if (start >= Int.MAX_VALUE) return emptyList()
        // Legacy subscriptions are unpaged. Keep each binder reply bounded, with an explicit way forward.
        val unpaged = size == Int.MAX_VALUE
        val nodes = childrenRange(parent, start.toInt(), if (unpaged) limit + 1 else size) ?: return null
        return if (unpaged && nodes.size > limit) nodes.take(limit) +
            folder("range/${start + limit}/$parent", "More", LibraryNode.ChildStyle.LIST)
        else nodes
    }

    private fun range(id: String): Pair<String, Int>? {
        val parts = id.split('/', limit = 3)
        val start = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val parent = parts.getOrNull(2)?.takeIf { it.isNotEmpty() && !it.startsWith("range/") } ?: return null
        return parent to start
    }

    private suspend fun childrenRange(id: String, start: Int, size: Int): List<LibraryNode>? {
        bucketLists(id)?.let { return it.drop(start).take(size) }
        shelfTypes[id]?.let { type ->
            val result = mutableListOf<LibraryNode>()
            while (result.size < size) {
                val next = start.toLong() + result.size
                if (next >= Int.MAX_VALUE) break
                val batchSize = minOf(MAX_PAGE_SIZE, size - result.size, Int.MAX_VALUE - next.toInt())
                val batch = client.getAlbumList2(batchSize, next.toInt(), type).albums
                result += batch.map(::albumNode)
                if (batch.size < batchSize) break
            }
            return result
        }
        val nodes = when {
            id == ROOT -> tabs
            id == HOME -> if (client.continueListening) homeShelves else homeShelves.filter { it.id != CONTINUE }
            id == CONTINUE -> albumResumeItems(client.getBookmarks(), CONTINUE_LIMIT).map(::resumeNode)
            id == PLAYLISTS -> client.getPlaylists().map(::playlistNode)
            id.startsWith("artist/") -> client.getArtist(id.removePrefix("artist/")).albums.map(::albumNode)
            id.startsWith("album/") -> client.getAlbum(id.removePrefix("album/")).songs.map { trackNode(it, id) }
            id.startsWith("playlist/") -> client.getPlaylist(id.removePrefix("playlist/")).songs.map { trackNode(it, id) }
            else -> return null
        }
        return nodes.drop(start).take(size)
    }

    private fun bucketTitle(id: String): String? {
        val rest = listOf(ALBUMS, ARTISTS).firstOrNull { id.startsWith("$it/b/") }?.let { id.removePrefix("$it/b/") } ?: return null
        val parts = rest.split('/')
        val letter = AlphaBuckets.parse(parts[0]) ?: return null
        return when (parts.size) {
            1 -> letter.toString()
            2 -> parts[1].toIntOrNull()?.takeIf { it >= 0 }?.let { "$letter (${it + 1})" }
            else -> null
        }
    }

    /** Albums/Artists tab and their bucket folders; null when [id] is neither. */
    private suspend fun bucketLists(id: String): List<LibraryNode>? = when {
        id == ALBUMS || id.startsWith("$ALBUMS/b/") -> bucketed(ALBUMS, id, { index.albums(client) }, { it.name }, { it.id }, ::albumNode)
        id == ARTISTS || id.startsWith("$ARTISTS/b/") -> bucketed(ARTISTS, id, { index.artists(client) }, { it.name }, { it.id }, ::artistNode)
        else -> null
    }

    private suspend fun <T> bucketed(root: String, id: String, load: suspend () -> List<T>, name: (T) -> String,
        ident: (T) -> String, node: (T) -> LibraryNode): List<LibraryNode>? {
        val style = if (root == ALBUMS) LibraryNode.ChildStyle.GRID else LibraryNode.ChildStyle.LIST
        if (id != root && bucketTitle(id) == null) return null
        val loaded = load()
        return kotlinx.coroutines.withContext(compute) { bucketNodes(root, id, style, AlphaBuckets.sorted(loaded, name, ident), name, node) }
    }

    private fun <T> bucketNodes(root: String, id: String, style: LibraryNode.ChildStyle, sorted: List<T>, name: (T) -> String,
        node: (T) -> LibraryNode): List<LibraryNode>? {
        fun top(): List<LibraryNode> {
            if (sorted.size <= limit) return sorted.map(node)
            val counts = sorted.groupingBy { AlphaBuckets.letter(name(it)) }.eachCount()
            return AlphaBuckets.partition(counts).map {
                // A letter above the limit holds chunk folders (no cover): list. Otherwise it holds items: the root's style.
                folder("$root/b/${it.id}", it.title, if (it.count > limit) LibraryNode.ChildStyle.LIST else style).copy(subtitle = if (it.count == 1) "1 item" else "${it.count} items")
            }
        }
        if (id == root) return top()
        val parts = id.removePrefix("$root/b/").split('/')
        val letter = AlphaBuckets.parse(parts[0]) ?: return null
        val inside = AlphaBuckets.inLetter(sorted, name, letter)
        val chunk = if (parts.size == 2) parts[1].toIntOrNull() ?: return null else null
        val chunks = minOf(limit, (inside.size + limit - 1) / limit)
        // A bucket the current tree doesn't have (an online bucket asked of the phone's tree, a letter or chunk that's
        // gone) answers the current tree's own list, not that letter filtered under the count the car still shows.
        val current = sorted.size > limit && inside.isNotEmpty() &&
            (chunk == null || (inside.size > limit && chunk < chunks))
        if (!current) return top()
        if (chunk != null) return inside.drop(chunk * limit).take(limit).map(node)
        if (inside.size <= limit) return inside.map(node)
        // One letter above the limit: numbered sub-folders of at most [limit] items (more than limit*limit items are cut).
        return (0 until chunks).map { n ->
            folder("$root/b/${parts[0]}/$n", AlphaBuckets.chunkTitle(letter, n, limit, inside.size), style)
        }
    }

    /** Albums, then songs, then artists; songs play from their album so the queue keeps the canonical album order. */
    suspend fun search(query: String, page: Int, pageSize: Int): List<LibraryNode> {
        val results = client.search3(query, SEARCH_ARTISTS, SEARCH_ALBUMS, SEARCH_SONGS)
        return page(results.albums.map(::albumNode) +
            results.songs.mapNotNull { song -> song.albumId?.takeIf { it.isNotBlank() }?.let { trackNode(song, albumId(it)) } } +
            results.artists.map(::artistNode), page, pageSize)
    }

    /** Selecting a track plays its container from that track; an album or playlist plays from the top. */
    suspend fun playback(id: String): PlaybackRequest? = when {
        id.startsWith("album/") -> client.getAlbum(id.removePrefix("album/")).let { PlaybackRequest(it.songs, 0, it.album) }
        id.startsWith("playlist/") -> client.getPlaylist(id.removePrefix("playlist/")).let { PlaybackRequest(it.songs, 0) }
        id.startsWith("continue/") -> {
            val albumId = id.removePrefix("continue/").substringBeforeLast('@')
            val page = client.getAlbum(albumId)
            PlaybackRequest(page.songs, page.songs.indexOfFirst { it.id == id.substringAfterLast('@') }.coerceAtLeast(0),
                page.album, continuationAlbumId = albumId)
        }
        id.startsWith("track/") -> splitTrack(id)?.let { (songId, container) ->
            when {
                container.startsWith("album/") -> client.getAlbum(container.removePrefix("album/")).let { page ->
                    page.songs.indexOfFirst { it.id == songId }.takeIf { it >= 0 }?.let { PlaybackRequest(page.songs, it, page.album) }
                }
                container.startsWith("playlist/") -> client.getPlaylist(container.removePrefix("playlist/")).let { page ->
                    page.songs.indexOfFirst { it.id == songId }.takeIf { it >= 0 }?.let { PlaybackRequest(page.songs, it) }
                }
                else -> null
            }
        }
        else -> null
    }

    /** `track/{songId}@{container}` -> songId to container. */
    private fun splitTrack(id: String): Pair<String, String>? {
        val body = id.removePrefix("track/")
        val at = body.indexOf('@').takeIf { it > 0 && it < body.lastIndex } ?: return null
        return body.substring(0, at) to body.substring(at + 1)
    }
}

/** Short-lived cache of the complete Albums/Artists lists, so browsing buckets does not refetch for every folder. */
class LibraryIndex(private val nowMs: () -> Long = System::currentTimeMillis, private val ttlMs: Long = 60_000, private val maxAlbums: Int = 20_000) {
    private var albumsAt = 0L; private var albumList: List<Album>? = null
    private var artistsAt = 0L; private var artistList: List<Artist>? = null

    suspend fun albums(client: TreeSource): List<Album> {
        albumList?.takeIf { nowMs() - albumsAt < ttlMs }?.let { return it }
        val all = mutableListOf<Album>()
        while (all.size < maxAlbums) {
            val batch = client.getAlbumList2(LibraryTree.MAX_PAGE_SIZE, all.size, AlbumListType.ALPHABETICAL).albums
            all += batch
            if (batch.size < LibraryTree.MAX_PAGE_SIZE) break
        }
        return all.also { albumList = it; albumsAt = nowMs() }
    }

    suspend fun artists(client: TreeSource): List<Artist> {
        artistList?.takeIf { nowMs() - artistsAt < ttlMs }?.let { return it }
        return client.getArtists().also { artistList = it; artistsAt = nowMs() }
    }
}
