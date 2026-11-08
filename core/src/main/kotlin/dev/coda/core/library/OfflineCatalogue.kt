package dev.coda.core.library

import dev.coda.core.subsonic.Album
import dev.coda.core.subsonic.AlbumListType
import dev.coda.core.subsonic.AlbumOrdering
import dev.coda.core.subsonic.AlbumPage
import dev.coda.core.subsonic.Artist
import dev.coda.core.subsonic.ArtistPage
import dev.coda.core.subsonic.Playlist
import dev.coda.core.subsonic.PlaylistPage
import dev.coda.core.subsonic.SearchResults
import dev.coda.core.subsonic.Song
import dev.coda.store.AlbumRow
import dev.coda.store.CatalogueDao
import dev.coda.store.LibraryDatabase
import dev.coda.store.SearchHit
import dev.coda.store.prefixMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.util.Locale

/**
 * The views offline, read from `library.db`'s catalogue and the music cache. Only what's on the phone:
 * an album is here when at least one of its tracks is cached (partial albums too, with "7 of 12"); the
 * Artists tab lists their album artists; playlists are the kept ones and those the phone knows with enough of
 * their songs here ([playlistShown]). [onPhone] is the set of cached song ids.
 * Rows carry the server's own objects, so today's sorts ([AlbumOrder.derive]) and projections ([dev.coda.core.subsonic.ArtistProjection])
 * work on them unchanged. Never throws: a database problem reads as nothing on the phone.
 */
class OfflineCatalogue(
    private val store: LibraryStore,
    /** Whether the artwork store has a usable copy of a cover id; null leaves playlist covers as saved. */
    private val storedCover: ((String) -> Boolean)? = null,
) {
    /**
     * How much of an album or playlist is on the phone. [partial]: "7 of 12" is shown. [known]: false when [total] is only
     * what is here (a minimal album entry, its album page not fetched yet): no "of" label, and never complete.
     */
    data class Count(val onPhone: Int, val total: Int, val known: Boolean = true) {
        val partial: Boolean get() = onPhone < total
        /** Every track is here, by a known total: what "Only complete albums" keeps. */
        val complete: Boolean get() = known && !partial
        val label: String get() = "$onPhone of $total"
    }

    /**
     * The albums on the phone (catalogue order, by id) with their counts and, for those played on the phone, the latest
     * play of one of their tracks here ([played], wall-clock ms: the phone's play dates). [artistIds]: every artist
     * with an album or a track here (album artists and the cached songs' artists), read once per shelf for Search.
     * [albumArtistIds]: the same per album on the shelf, so [completeOnly] keeps only the artists of the albums that stay.
     */
    data class Shelf(val albums: List<Album>, val counts: Map<String, Count>, val played: Map<String, Long> = emptyMap(),
        val artistIds: Set<String> = emptySet(), val albumArtistIds: Map<String, Set<String>> = emptyMap()) {
        /**
         * The Albums tab in [type]'s order; Random is shuffled with [random]. Recently Played: the albums played on
         * this phone by its own play dates, each carrying that date as [Album.played] so the sections read it too.
         */
        fun ordered(type: AlbumListType, random: kotlin.random.Random = kotlin.random.Random, locale: Locale = Locale.getDefault()) = when (type) {
            AlbumListType.RANDOM -> albums.shuffled(random)
            AlbumListType.RECENTLY_PLAYED -> recentlyPlayed(Int.MAX_VALUE).map { it.copy(played = java.time.Instant.ofEpochMilli(played.getValue(it.id)).toString()) }
            else -> AlbumOrder.derive(type, albums, locale)
        }
        /** Home's and the Artists tab's Recently Played offline: the albums played on this phone, latest first. */
        fun recentlyPlayed(limit: Int): List<Album> = albums.filter { it.id in played }
            .sortedWith(compareByDescending<Album> { played.getValue(it.id) }.thenBy { it.id }).take(limit)
        val ids: Set<String> get() = counts.keys

        /**
         * "Only complete albums": only the albums with every track on the phone (kept or just cached, e.g. played), so
         * the lists, the Artists tab and Search drop the partial "7 of 12" ones and their counts follow. [artistIds] are
         * those of the albums that stay (album and track artists): Search and "Go to artist" never offer an artist whose
         * page would be empty.
         */
        fun completeOnly(): Shelf {
            val keep = counts.filterValues { it.complete }
            val artists = albumArtistIds.filterKeys { it in keep }
            return Shelf(albums.filter { it.id in keep }, keep, played.filterKeys { it in keep }, artists.values.flatten().toSet(), artists)
        }
    }

    /** [playedAt]: song id to its last play on the phone (wall clock), for [Shelf.played]. */
    suspend fun shelf(onPhone: Set<String>, playedAt: Map<String, Long> = emptyMap()): Shelf =
        store.use(Shelf(emptyList(), emptyMap())) { db -> shelf(db, onPhone, playedAt) }

    /** Album artists of the albums on the phone, each with the number of its own albums here. */
    suspend fun artists(onPhone: Set<String>): List<Artist> = artists(shelf(onPhone))

    /** As [artists], from a shelf already read (the albums are decoded once per reload). */
    suspend fun artists(shelf: Shelf): List<Artist> = store.use(emptyList()) { db ->
        val owners = LinkedHashMap<String, Int>()
        val names = HashMap<String, String>()
        for (album in shelf.albums) for ((id, name) in albumArtists(album)) {
            owners[id] = (owners[id] ?: 0) + 1
            names.putIfAbsent(id, name)
        }
        owners.map { (id, count) -> artist(db, id, names[id].orEmpty()).copy(albumCount = count) }
    }

    /** One album with all its tracks (missing ones are dimmed by the page), or null when none of it is on the phone. */
    suspend fun album(id: String, onPhone: Set<String>): Pair<AlbumPage, Count>? = store.use(null) { db ->
        val row = db.catalogue().album(id) ?: return@use null
        val album = decode<Album>(row.json) ?: return@use null
        val count = count(row, album, onPhone).takeIf { it.onPhone > 0 } ?: return@use null
        val byId = db.catalogue().songsOf(id).mapNotNull { r -> decode<Song>(r.json) }.associateBy { it.id }
        // Only the album's own track list: a song the server dropped since must not add a row the count doesn't know.
        // Disc/track order as online (Swift parity): a minimal album's list grows in cache-arrival order.
        AlbumPage(album, AlbumOrdering.canonical(ids(row.songIds).mapNotNull { byId[it] })) to count
    }

    /**
     * An artist's page from the phone: own albums (the artist is an album artist) plus "Appears on" (another artist's
     * album with a track by this one). Null when nothing of theirs is here.
     */
    suspend fun artist(id: String, onPhone: Set<String>): Pair<ArtistPage, List<Album>>? = artist(id, shelf(onPhone))

    /** As [artist], from a shelf already read; only the songs naming this artist are read. */
    suspend fun artist(id: String, shelf: Shelf): Pair<ArtistPage, List<Album>>? = store.use(null) { db ->
        val own = shelf.albums.filter { album -> albumArtists(album).any { it.first == id } }
        val ownIds = own.mapTo(HashSet()) { it.id }
        val shelfIds = shelf.albums.associateBy { it.id }
        val appears = db.catalogue().songRefsNaming(likeId(id))
            .filter { it.albumId != null && it.albumId !in ownIds && id in ids(it.artistIds) }
            .mapNotNull { shelfIds[it.albumId] }.distinctBy { it.id }
        if (own.isEmpty() && appears.isEmpty()) return@use null
        val name = own.firstNotNullOfOrNull { a -> albumArtists(a).firstOrNull { it.first == id }?.second }
            ?: appears.firstNotNullOfOrNull { a -> songArtistName(db, a.id, id) }.orEmpty()
        ArtistPage(artist(db, id, name).copy(albumCount = own.size), own) to appears
    }

    /**
     * The playlists shown offline, by name, with their counts: the kept ones and the others by [playlistShown]
     * under "Only complete albums" ([completeOnly]).
     */
    suspend fun playlists(onPhone: Set<String>, completeOnly: Boolean = false): Pair<List<Playlist>, Map<String, Count>> =
        store.use(emptyList<Playlist>() to emptyMap()) { db ->
            val counts = HashMap<String, Count>()
            val list = shownPlaylists(db, onPhone, completeOnly).map { (row, members, count) ->
                counts[row.id] = count
                (row.json?.let { decode<Playlist>(it) } ?: Playlist(row.id, row.name, owner = row.owner, coverArt = row.coverArt))
                    .copy(songCount = members.size).let { cover(db, it, members) }
            }.sortedBy { it.name.lowercase() }
            list to counts
        }

    /**
     * What of the playlists the on-phone track set alone can change, for the car's reloads: each playlist
     * shown without "Only complete albums", with whether it is shown with it too. A song more or less that moves no
     * playlist across a threshold changes nothing here.
     */
    suspend fun playlistPlaces(onPhone: Set<String>): Map<String, Boolean> = store.use(emptyMap()) { db ->
        val strict = shownPlaylists(db, onPhone, completeOnly = true).mapTo(HashSet()) { it.row.id }
        shownPlaylists(db, onPhone, completeOnly = false).associate { it.row.id to (it.row.id in strict) }
    }

    private data class Shown(val row: dev.coda.store.PlaylistRow, val members: List<String>, val count: Count)

    /**
     * The catalogue's playlists that [playlistShown] keeps. Kept: the kept sets' keys, or a row without the server's
     * playlist (only a kept set makes one, from its own track list). Known: the playlist's page copy is still saved (it can
     * be trimmed between two catalogue passes).
     */
    private suspend fun shownPlaylists(db: LibraryDatabase, onPhone: Set<String>, completeOnly: Boolean): List<Shown> {
        val rows = db.catalogue().playlists()
        if (rows.isEmpty()) return emptyList()
        val kept = db.cache().setKeys().toSet()
        val saved = db.pages().playlistKeys().toSet()
        return rows.mapNotNull { row ->
            val members = ids(row.memberIds)
            val count = Count(members.count { it in onPhone }, members.size)
            val key = "playlist/${row.id}"
            Shown(row, members, count).takeIf { playlistShown(key in kept || row.json == null, key in saved, count, completeOnly) }
        }
    }

    /**
     * A playlist shown offline with every member (missing ones dimmed by the page). The rows and the count both follow the
     * catalogue's members (as the Playlists tab counts them); each member's song comes from the saved page
     * when it has it, else from the catalogue. [completeOnly] false by default, as a partial album's page opens by
     * id whatever "Only complete albums" says.
     */
    suspend fun playlist(id: String, onPhone: Set<String>, completeOnly: Boolean = false): Pair<PlaylistPage, Count>? = store.use(null) { db ->
        val row = db.catalogue().playlist(id) ?: return@use null
        val saved = db.pages().get("playlist/$id")?.json?.let { decode<PlaylistSnapshot>(it) }
        val members = ids(row.memberIds)
        val count = Count(members.count { it in onPhone }, members.size)
        val kept = row.json == null || "playlist/$id" in db.cache().setKeys()
        if (!playlistShown(kept, saved != null, count, completeOnly)) return@use null
        val savedSongs = saved?.entries.orEmpty().associateBy { it.id }
        val songs = members.mapNotNull { m -> savedSongs[m] ?: db.catalogue().song(m)?.json?.let { decode<Song>(it) } }
        val playlist = saved?.playlist ?: row.json?.let { decode<Playlist>(it) } ?: Playlist(row.id, row.name, owner = row.owner, coverArt = row.coverArt)
        val covered = storedCover?.let { stored -> playlist.copy(coverArt = PlaylistCover.offline(playlist.coverArt, songs.asSequence(), stored)) } ?: playlist
        PlaylistPage(covered, songs) to count
    }

    /**
     * A playlist page's header offline: up to four stored song covers in playlist order (the online mosaic, stored covers
     * only), else the page's cover from [playlist] (its own stored cover, or none: the placeholder). Null without a store.
     */
    fun headerCovers(page: PlaylistPage): List<String>? = storedCover?.let { stored ->
        PlaylistCover.storedSongCovers(page.songs.asSequence(), stored).take(HEADER_COVERS).toList()
            .ifEmpty { listOfNotNull(page.playlist.coverArt) }
    }

    /** The Playlists tab's and the car's cover ([PlaylistCover]): members read a few at a time, up to the first stored cover. */
    private suspend fun cover(db: LibraryDatabase, playlist: Playlist, members: List<String>): Playlist {
        val stored = storedCover ?: return playlist
        if (playlist.coverArt?.let(stored) == true) return playlist
        val songCover = members.chunked(COVER_CHUNK).firstNotNullOfOrNull { chunk ->
            val rows = db.catalogue().songsIn(chunk).associateBy { it.id }
            PlaylistCover.offline(null, chunk.asSequence().mapNotNull { id -> rows[id]?.json?.let { decode<Song>(it) } }, stored)
        }
        return playlist.copy(coverArt = songCover)
    }

    /**
     * Search offline: the catalogue's FTS4 index (word prefix on core's fold), restricted to what's on the phone:
     * songs that are here, albums with a track here ([shelf]), and artists with an album or a track here
     * ([Shelf.artistIds]). The restriction comes before the limit (a large library with a small cache
     * must still find every match here). Per kind one FTS read of the matches' ids and names, filtered and ordered
     * here, then one read of the first [limit] rows: six statements whatever the cache size (was one per 500 ids on the
     * phone, 46 at 20,000 songs), and the library lock is released between them and while ordering and decoding.
     */
    suspend fun search(query: String, shelf: Shelf, onPhone: Set<String>, artistCount: Int = 16, albumCount: Int = 24,
        songCount: Int = 40): SearchResults = withContext(Dispatchers.Default) {
        // The filtering, ordering and decoding of up to ~20k hits stay off the callers' main thread.
        val match = prefixMatch(ArtistFilter.fold(query))
        if (match.isEmpty()) return@withContext SearchResults()
        val artists = first(shelf.artistIds, artistCount, { it.artistHits(match) }, { c, ids -> c.artistsIn(ids) }, { it.id })
            .map { row -> row.json?.let { decode<Artist>(it) } ?: Artist(row.id, row.name) }
        val albums = first(shelf.ids, albumCount, { it.albumHits(match) }, { c, ids -> c.albumsIn(ids) }, { it.id })
            .mapNotNull { decode<Album>(it.json) }
        val songs = first(onPhone, songCount, { it.songHits(match) }, { c, ids -> c.songsIn(ids) }, { it.id })
            .mapNotNull { decode<Song>(it.json) }
        SearchResults(artists = artists, albums = albums, songs = songs)
    }

    /** The rows of the first [limit] matches in [here] by name, then id; each read takes the lock on its own. */
    private suspend fun <R> first(here: Set<String>, limit: Int, hits: suspend (CatalogueDao) -> List<SearchHit>,
        rows: suspend (CatalogueDao, List<String>) -> List<R>, id: (R) -> String): List<R> {
        if (here.isEmpty() || limit <= 0) return emptyList()
        val found = store.use(emptyList()) { db -> hits(db.catalogue()) }.filter { it.id in here }
        val ids = found.sortedWith(compareBy<SearchHit> { it.name }.thenBy { it.id }).take(limit).map { it.id }
        if (ids.isEmpty()) return emptyList()
        val byId = store.use(emptyList()) { db -> rows(db.catalogue(), ids) }.associateBy(id)
        return ids.mapNotNull { byId[it] }
    }

    private suspend fun shelf(db: LibraryDatabase, onPhone: Set<String>, playedAt: Map<String, Long>): Shelf {
        val albums = ArrayList<Album>()
        val counts = HashMap<String, Count>()
        val played = HashMap<String, Long>()
        val artistIds = HashSet<String>()
        val byAlbum = HashMap<String, MutableSet<String>>()
        for (row in db.catalogue().albums()) {
            val members = ids(row.songIds)
            // Decode only the albums with a track here (large libraries).
            if (members.none { it in onPhone }) continue
            val album = decode<Album>(row.json) ?: continue
            albums += album
            counts[album.id] = count(row, album, onPhone, members)
            members.filter { it in onPhone }.mapNotNull { playedAt[it] }.maxOrNull()?.let { played[album.id] = it }
            albumArtists(album).mapTo(byAlbum.getOrPut(album.id) { HashSet() }) { it.first }
        }
        // The cached songs' own artists (track-only artists too): one indexed read per chunk, not a scan per artist.
        for (chunk in onPhone.sorted().chunked(ID_CHUNK)) for (song in db.catalogue().artistIdsOf(chunk)) {
            val found = ids(song.artistIds)
            artistIds += found
            song.albumId?.let { byAlbum[it] }?.addAll(found)
        }
        for (set in byAlbum.values) { set.remove(""); artistIds += set }
        artistIds.remove("")
        return Shelf(albums, counts, played, artistIds, byAlbum)
    }

    /**
     * All the album's tracks against those on the phone. A minimal entry knows only its cached tracks (its track metadata
     * carries no album total), so its total is unknown until the album page arrives.
     */
    private fun count(row: AlbumRow, album: Album, onPhone: Set<String>, members: List<String> = ids(row.songIds)): Count {
        val here = members.count { it in onPhone }
        return Count(here, maxOf(members.size, if (row.minimal) 0 else album.songCount ?: 0, here), known = !row.minimal)
    }

    private suspend fun artist(db: LibraryDatabase, id: String, name: String): Artist {
        val row = db.catalogue().artist(id)
        return row?.json?.let { decode<Artist>(it) } ?: Artist(id, row?.name?.takeIf { it.isNotEmpty() } ?: name, coverArt = row?.imageId)
    }

    private suspend fun songArtistName(db: LibraryDatabase, albumId: String, artistId: String): String? =
        db.catalogue().songsOf(albumId).firstNotNullOfOrNull { row ->
            decode<Song>(row.json)?.let { s ->
                if (s.artistId == artistId) s.artistName
                else s.artists.orEmpty().firstOrNull { it.id == artistId }?.name
            }
        }

    companion object {
        /** With "Only complete albums", a playlist not kept needs this many of its songs on the phone (and half). */
        const val MIN_PLAYLIST_SONGS = 3

        /**
         * RV2-11, the one offline playlist rule (the Playlists tab, the playlist page, Home's shelf and the
         * car): a [kept] ("Prefer offline") playlist always shows. Another shows only when the phone knows its song list
         * ([known]: its page copy is saved), and then: without [completeOnly], with at least one song on the phone; with it, with at
         * least half of its songs and at least [MIN_PLAYLIST_SONGS] on the phone.
         */
        fun playlistShown(kept: Boolean, known: Boolean, count: Count, completeOnly: Boolean): Boolean = when {
            kept -> true
            !known -> false
            completeOnly -> count.onPhone >= MIN_PLAYLIST_SONGS && count.onPhone * 2 >= count.total
            else -> count.onPhone >= 1
        }

        private val json = CatalogueCollector.json
        /** Ids per `IN (…)` list: well under SQLite's bind-variable limit. */
        private const val ID_CHUNK = 500
        /** A LIKE pattern for an id inside a JSON array of ids (the caller checks the decoded ids). */
        private fun likeId(id: String) = "%" + json.encodeToString(String.serializer(), id) + "%"
        /** The album's artists as (id, name): `artistId` with its display name, then the `artists` list. */
        internal fun albumArtists(album: Album): List<Pair<String, String>> =
            (listOfNotNull(album.artistId?.takeIf { it.isNotEmpty() }?.let { it to album.artistName }) +
                album.artists.orEmpty().filter { it.id.isNotEmpty() }.map { it.id to it.name }).distinctBy { it.first }
        private const val COVER_CHUNK = 50
        private const val HEADER_COVERS = 4
        private fun ids(text: String): List<String> = runCatching { json.decodeFromString(ListSerializer(String.serializer()), text) }.getOrDefault(emptyList())
        private inline fun <reified T> decode(text: String): T? = runCatching { json.decodeFromString<T>(text) }.getOrNull()
    }
}
