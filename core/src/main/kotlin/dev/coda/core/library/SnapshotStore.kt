package dev.coda.core.library

import dev.coda.core.subsonic.*
import java.text.Normalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Home shelves that survive a restart (the Continue Listening shelf is rebuilt from bookmarks). */
@Serializable
data class HomeShelves(
    val albums: List<Album> = emptyList(), val recentReleases: List<Album> = emptyList(),
    val recentlyPlayed: List<Album> = emptyList(), val playlists: List<Playlist> = emptyList(),
)

@Serializable internal data class AlbumSnapshot(val album: Album, val songs: List<Song>)
@Serializable internal data class ArtistSnapshot(val artist: Artist, val albums: List<Album>)
@Serializable internal data class ArtistsSnapshot(val artists: List<Artist>)
@Serializable internal data class AlbumsSnapshot(val allAlbums: List<Album>)
@Serializable internal data class PlaylistsSnapshot(val allPlaylists: List<Playlist>)
@Serializable internal data class PlaylistSnapshot(val playlist: Playlist, val entries: List<Song>)

/**
 * Where [SnapshotStore] keeps its pages: the `pages` table of the account's `library.db` ([LibraryStore]). Never
 * throws: a page is an optimisation, a failed read is null and a failed write is dropped.
 */
interface PageStore {
    /** The page's JSON, or null; a read makes it the most recently used. */
    suspend fun page(key: String): String?
    /** Writes the page (no longer stale), then trims the least recently used unpinned pages to the limit. */
    suspend fun putPage(key: String, json: String)
    /** The JSON of the pinned pages (those of kept sets, see `PageRow.pinned`). */
    suspend fun pinnedPages(): List<String>
    /**
     * Up to [limit] pages after [after] in key order, as (key, JSON) (offline search reads every page this way, a
     * chunk at a time, so neither the heap nor the store's lock holds them all at once). Empty: no more pages.
     */
    suspend fun pagesAfter(after: String, limit: Int): List<Pair<String, String>>
    suspend fun markStale(key: String)
    suspend fun isStale(key: String): Boolean
    /** Moves on every page write by anyone (the catalogue writes pages too): [SnapshotStore]'s search index follows it. */
    val pageRevision: Int
}

/**
 * Library snapshots: one row per logical key in the account's `library.db` (`pages`), LRU-bounded, pinned
 * while a kept set has the key. One instance per [LibraryStore] ([LibraryStore.snapshots]). Snapshots hold library
 * metadata only (never credentials or URLs).
 */
class SnapshotStore(private val pages: PageStore) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    /** The search index and the [PageStore.pageRevision] it was built at. */
    @Volatile private var index: Pair<Int, List<Any>>? = null

    private suspend inline fun <reified T> read(key: String): T? =
        pages.page(key)?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }

    private suspend inline fun <reified T> write(key: String, value: T) {
        val text = try { json.encodeToString<T>(value) } catch (_: Exception) { return }
        pages.putPage(key, text)
    }

    suspend fun album(id: String): AlbumPage? = read<AlbumSnapshot>("album/$id")?.let { AlbumPage(it.album, it.songs) }
    suspend fun putAlbum(page: AlbumPage) = write("album/${page.album.id}", AlbumSnapshot(page.album, page.songs))
    suspend fun artist(id: String): ArtistPage? = read<ArtistSnapshot>("artist/$id")?.let { ArtistPage(it.artist, it.albums) }
    suspend fun putArtist(page: ArtistPage) = write("artist/${page.artist.id}", ArtistSnapshot(page.artist, page.albums))
    suspend fun artists(): List<Artist>? = read<ArtistsSnapshot>("artists")?.artists
    suspend fun putArtists(artists: List<Artist>) = write("artists", ArtistsSnapshot(artists))
    /** The Albums and Playlists routes. */
    suspend fun albums(): List<Album>? {
        val saved = read<AlbumsSnapshot>("albums")?.allAlbums
        val pinned = pinnedPages<AlbumSnapshot>().map { it.album }
        return if (saved == null && pinned.isEmpty()) null else (saved.orEmpty() + pinned).distinctBy { it.id }
    }
    suspend fun putAlbums(albums: List<Album>) = write("albums", AlbumsSnapshot(albums))
    suspend fun playlists(): List<Playlist>? {
        val saved = read<PlaylistsSnapshot>("playlists")?.allPlaylists
        val pinned = pinnedPages<PlaylistSnapshot>().map { it.playlist }
        return if (saved == null && pinned.isEmpty()) null else (saved.orEmpty() + pinned).distinctBy { it.id }
    }
    private suspend inline fun <reified T> pinnedPages(): List<T> =
        pages.pinnedPages().mapNotNull { runCatching { json.decodeFromString<T>(it) }.getOrNull() }
    suspend fun putPlaylists(playlists: List<Playlist>) = write("playlists", PlaylistsSnapshot(playlists))
    suspend fun playlist(id: String): PlaylistPage? = read<PlaylistSnapshot>("playlist/$id")?.let { PlaylistPage(it.playlist, it.entries) }
    /** Also clears [playlistIsStale] (any page write does). */
    suspend fun putPlaylist(page: PlaylistPage) = write("playlist/${page.playlist.id}", PlaylistSnapshot(page.playlist, page.songs))
    /**
     * A playlist edited from a menu: its saved contents are out of date until the server's are saved ([putPlaylist]).
     * Persisted (the page row's `stale` column), so it survives the screen being recreated and the process dying.
     */
    suspend fun markPlaylistStale(id: String) = pages.markStale("playlist/$id")
    suspend fun playlistIsStale(id: String): Boolean = pages.isStale("playlist/$id")
    suspend fun home(): HomeShelves? = read<HomeShelves>("home")
    suspend fun putHome(shelves: HomeShelves) = write("home", shelves)

    /** Everything the snapshots know, for offline search. Built lazily, again after any page write. */
    private suspend fun items(): List<Any> {
        val revision = pages.pageRevision
        index?.takeIf { it.first == revision }?.let { return it.second }
        val items = ArrayList<Any>()
        var after = ""
        while (true) {
            val chunk = pages.pagesAfter(after, CHUNK)
            if (chunk.isEmpty()) break
            after = chunk.last().first
            decode(chunk.map { it.second }, items)
            if (chunk.size < CHUNK) break
        }
        index = revision to items
        return items
    }

    private fun decode(texts: List<String>, into: MutableList<Any>) = with(into) {
            texts.forEach { text ->
                // A page decodes as exactly one shape; the first that parses wins.
                runCatching { json.decodeFromString<AlbumSnapshot>(text) }.getOrNull()?.let { add(it.album); addAll(it.songs); return@forEach }
                runCatching { json.decodeFromString<ArtistSnapshot>(text) }.getOrNull()?.let { add(it.artist); addAll(it.albums); return@forEach }
                runCatching { json.decodeFromString<ArtistsSnapshot>(text) }.getOrNull()?.let { addAll(it.artists); return@forEach }
                runCatching { json.decodeFromString<AlbumsSnapshot>(text) }.getOrNull()?.let { addAll(it.allAlbums); return@forEach }
                runCatching { json.decodeFromString<PlaylistSnapshot>(text) }.getOrNull()?.let { addAll(it.entries); return@forEach }
                runCatching { json.decodeFromString<PlaylistsSnapshot>(text) }.getOrNull()?.let { return@forEach }
                runCatching { json.decodeFromString<HomeShelves>(text) }.getOrNull()?.let {
                    addAll(it.albums); addAll(it.recentReleases); addAll(it.recentlyPlayed)
                }
            }
    }

    private companion object {
        /** Pages per read for offline search. */
        const val CHUNK = 100
    }

    /** Case- and diacritic-insensitive match over snapshot items; every word of [query] must occur. */
    suspend fun search(query: String, artistCount: Int = 16, albumCount: Int = 24, songCount: Int = 40): SearchResults {
        val words = fold(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return SearchResults()
        fun matches(vararg fields: String?): Boolean {
            val haystack = fold(fields.filterNotNull().joinToString(" "))
            return words.all { it in haystack }
        }
        val all = items()
        return SearchResults(
            artists = all.filterIsInstance<Artist>().distinctBy { it.id }.filter { matches(it.name) }.take(artistCount),
            albums = all.filterIsInstance<Album>().distinctBy { it.id }.filter { matches(it.name, it.artistName) }.take(albumCount),
            songs = all.filterIsInstance<Song>().distinctBy { it.id }.filter { matches(it.title, it.artistName, it.album) }.take(songCount),
        )
    }

    private fun fold(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase().trim()
}
