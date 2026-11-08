package dev.coda.core.library

import dev.coda.core.cache.CachedTrack
import dev.coda.core.cache.KeptItem
import dev.coda.core.cache.TrackMeta
import dev.coda.core.subsonic.Album
import dev.coda.core.subsonic.AlbumPage
import dev.coda.core.subsonic.Artist
import dev.coda.core.subsonic.ArtistReference
import dev.coda.core.subsonic.Playlist
import dev.coda.core.subsonic.SearchResults
import dev.coda.core.subsonic.Song
import dev.coda.core.subsonic.SubsonicError
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import dev.coda.store.AlbumRow
import dev.coda.store.ArtistRow
import dev.coda.store.ImageRef
import dev.coda.store.LibraryDatabase
import dev.coda.store.PlaylistRow
import dev.coda.store.SongRow
import dev.coda.store.prefixMatch
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The catalogue of the music on the phone (track → album → artist, with ids; picture references), kept in
 * `library.db`. It is the part of the database that cached tracks or kept sets refer to.
 *
 * [collect] runs after the music cache changed (a track landed by read-ahead, play, "Keep offline" or adoption; a set was
 * kept, renewed or removed). It finds the referenced albums that have no full entry yet, takes each from the album page
 * copy when one is saved (most albums cost no request), otherwise asks the server once per album, serially, only while
 * it answers. A cached track whose album could not be fetched yet gets a minimal entry from its own metadata, so it is
 * never invisible offline. Artists come from the saved `artists` page (getArtists at every start), else as id + name.
 * Then the clean-up: entries no cached track or kept set refers to any more leave the catalogue (their page copies
 * become ordinary pages again and are trimmed like the rest).
 */
class CatalogueCollector(
    private val store: LibraryStore,
    /** One getAlbum; throws when the server does not answer. */
    private val fetchAlbum: suspend (albumId: String) -> AlbumPage,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** A failed album request is not repeated before this (an album gone from the server must not be asked every minute). */
    private val retryMs: Long = 10 * 60_000L,
    /** At most this many albums are asked per run; the rest wait for the next pass ([Result.more]). */
    private val maxFetchesPerRun: Int = 200,
    /**
     * A run stops after this many failures in a row (server half-down; not-found answers never count): first-time requests
     * and retries each have their own budget of this size. [Result.retryAtMs] says when to come back.
     */
    private val maxConsecutiveFailures: Int = 3,
    /** Tests: runs inside an album's ingest transaction after its songs, before its row. */
    internal val beforeAlbumRow: suspend () -> Unit = {},
) {
    /** Album id to the time its last request failed (this process only). */
    private val failedAt = HashMap<String, Long>()

    /**
     * What one run did. [pending]: referenced albums still without a full entry. When to run again is not "pending > 0"
     * (an album gone from the server would keep every pass busy): see [dueAgain]. [more]: the run's cap was reached, go on
     * right away. [needsServer]: albums were left because the server was not usable; run again when it is. [retryAtMs]:
     * the earliest time a failed album may be asked again (null: none waits for a back-off).
     */
    data class Result(
        val fromPages: Int = 0, val fetched: Int = 0, val failed: Int = 0, val pending: Int = 0, val minimal: Int = 0, val removedAlbums: Int = 0,
        val more: Boolean = false, val needsServer: Boolean = false, val retryAtMs: Long? = null,
        /** Full entries taken again from a page a screen saved with new contents. */
        val refreshed: Int = 0,
    ) {
        /** Whether another pass is worth running now: the cache changed, a cap or back-off ended, or the server came back. */
        fun dueAgain(cacheChanged: Boolean, usable: Boolean, now: Long) =
            cacheChanged || more || (needsServer && usable) || (retryAtMs?.let { now >= it } == true)
    }

    /**
     * [records] are the cache's tracks and [sets] its kept sets. [reachable] is the cache's server reachability: while
     * false no request is made. Never throws (except cancellation).
     */
    suspend fun collect(records: List<CachedTrack>, sets: List<KeptItem>, reachable: () -> Boolean): Result {
        val refs = References.of(records, sets)
        var result = Result()
        // 1. Albums without a full entry: from the saved page when there is one. A full entry whose page a screen
        // saved with new contents (rename, tracks added or removed) is taken from that page again.
        // The changed set lives in memory only, so the store's first pass compares all saved pages (a page saved just
        // before a process death), and a pass that fails or is cancelled here gives its work back.
        var changed = store.takeChangedAlbums()
        val taken = changed
        var reconcile = store.takeReconcile()
        val missing = try {
            var full: Set<String> = emptySet()
            val stillMissing = store.use<List<String>?>(null) { db ->
                full = db.catalogue().fullAlbumIds().toSet()
                val stillMissing = mutableListOf<String>()
                for (id in refs.albums) {
                    val refresh = id in full
                    if (refresh && id !in changed) continue
                    val page = db.pages().get("album/$id")?.json?.let { decode<AlbumSnapshot>(it) }
                    if (page != null) {
                        ingestAlbum(db, AlbumPage(page.album, page.songs), if (refresh) refs else null)
                        result = if (refresh) result.copy(refreshed = result.refreshed + 1) else result.copy(fromPages = result.fromPages + 1)
                    }
                    else if (!refresh) stillMissing += id
                }
                stillMissing
            }
            if (stillMissing != null) changed = emptySet()
            // The compare walks the library in chunks, each its own store use, so a screen's read gets the store
            // in between; only the albums that differ are taken again.
            if (stillMissing != null && reconcile) {
                var ok = true
                for (chunk in refs.albums.filter { it in full && it !in taken }.chunked(RECONCILE_CHUNK)) {
                    ok = store.use(false) { db ->
                        for (id in chunk) {
                            val page = db.pages().get("album/$id")?.json?.let { decode<AlbumSnapshot>(it) } ?: continue
                            if (!differs(db, page)) continue
                            ingestAlbum(db, AlbumPage(page.album, page.songs), refs)
                            result = result.copy(refreshed = result.refreshed + 1)
                        }
                        true
                    }
                    if (!ok) break
                }
                if (ok) reconcile = false
            }
            stillMissing ?: emptyList()
        } catch (e: CancellationException) {
            store.requeue(changed, reconcile)
            throw e
        }
        if (changed.isNotEmpty() || reconcile) store.requeue(changed, reconcile)
        // 2. The rest from the server, one at a time, only while it answers. A run is capped, and stops after a few
        // failures in a row; what is left waits for the next pass.
        var asked = 0
        var streak = 0
        var retryStreak = 0
        var unusable = false
        var capped = false
        // Never-tried albums first, then the retries: a retry cannot hide a new album, and a server that answers ping but
        // fails every getAlbum costs at most [maxConsecutiveFailures] retry requests per run.
        for (id in missing.sortedBy { it in failedAt }) {
            if (failedAt[id]?.let { nowMs() - it < retryMs } == true) continue
            val retry = id in failedAt
            if (!reachable()) { unusable = true; break }
            if (streak >= maxConsecutiveFailures || (retry && retryStreak >= maxConsecutiveFailures)) break
            if (asked >= maxFetchesPerRun) { capped = true; break }
            asked++
            // A "not found" answer says nothing about the server being half-down (the album is gone): it never counts towards a stop.
            val page = try { fetchAlbum(id) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                failedAt[id] = nowMs()
                if (e !is SubsonicError.NotFound) { if (retry) retryStreak++ else streak++ }
                result = result.copy(failed = result.failed + 1); continue
            }
            streak = 0
            retryStreak = 0
            failedAt.remove(id)
            store.use(Unit) { db -> ingestAlbum(db, page) }
            store.mirrorPage("album/${page.album.id}", json.encodeToString(AlbumSnapshot(page.album, page.songs)))
            result = result.copy(fetched = result.fetched + 1)
        }
        val waiting = missing.mapNotNull { failedAt[it] }.minOrNull()?.plus(retryMs)
        result = result.copy(pending = missing.size - result.fetched, more = capped, needsServer = unusable, retryAtMs = waiting)
        store.awaitWrites()
        // 3. Minimal entries, artists, clean-up and trim, then the playlists (after the trim, so a playlist whose
        // page copy the trim took leaves in the same pass).
        store.use(Unit) { db ->
            result = result.copy(minimal = ingestMinimal(db, refs))
            ingestArtists(db)
            result = result.copy(removedAlbums = cleanUp(db, refs))
            store.trimPages(db, refresh = true)
            ingestPlaylists(db, sets, records.mapTo(HashSet()) { it.songId })
        }
        return result
    }

    /** Offline search over the catalogue (FTS4 word prefix on core's fold). Read by OF-1c; empty for a blank query. */
    suspend fun search(query: String, artistCount: Int = 16, albumCount: Int = 24, songCount: Int = 40): SearchResults {
        val match = prefixMatch(ArtistFilter.fold(query))
        if (match.isEmpty()) return SearchResults()
        return store.use(SearchResults()) { db ->
            val c = db.catalogue()
            SearchResults(
                artists = c.searchArtists(match, artistCount).map { row -> row.json?.let { decode<Artist>(it) } ?: Artist(row.id, row.name) },
                albums = c.searchAlbums(match, albumCount).mapNotNull { decode<Album>(it.json) },
                songs = c.searchSongs(match, songCount).mapNotNull { decode<Song>(it.json) },
            )
        }
    }

    /**
     * One album, its songs and its picture reference in one transaction, the album row (what makes it "full") last.
     * [refresh]: a full entry taken again; its songs the page no longer has leave unless the cache refers to them.
     * Those stay members too (Rex R1): a track the server dropped or re-id'd that is still on the phone keeps its album
     * page row and its count, and the album stays in the offline lists.
     */
    private suspend fun ingestAlbum(db: LibraryDatabase, page: AlbumPage, refresh: References? = null) {
        val album = page.album
        val now = nowMs()
        db.useWriterConnection { it.immediateTransaction {
            var members = page.songs.map { it.id }
            if (refresh != null) {
                val kept = members.toHashSet()
                fun here(id: String) = id in refresh.songs || id in refresh.metas
                db.catalogue().songsOf(album.id).map { it.id }
                    .filter { it !in kept && !here(it) }
                    .takeIf { it.isNotEmpty() }?.let { db.catalogue().deleteSongs(it) }
                val old = db.catalogue().album(album.id)?.songIds?.let { decode(ListSerializer(String.serializer()), it) }.orEmpty()
                members = members + old.filter { it !in kept && here(it) }
            }
            db.catalogue().upsertSongs(page.songs.map { song(it, minimal = false) })
            beforeAlbumRow()
            db.catalogue().upsertAlbums(listOf(AlbumRow(album.id, album.name, album.sortName, album.artistId, album.artistName,
                ids(albumArtistIds(album)), album.serverCoverArt, album.releaseYear, ids(members),
                ArtistFilter.fold("${album.name} ${album.artistName}"), json.encodeToString(album), now, minimal = false)))
            album.serverCoverArt?.let { db.catalogue().upsertImages(listOf(ImageRef(ALBUM, album.id, it))) }
        } }
        store.addProtected(listOf("album/${album.id}") + albumArtistIds(album).map { "artist/$it" })
    }

    /**
     * Whether a saved page differs from the full entry it was taken from, by the rule of a screen's page save
     * (LibraryStore.writePage: play data aside). Members the cache keeps beyond the page are no difference.
     */
    private suspend fun differs(db: LibraryDatabase, page: AlbumSnapshot): Boolean {
        val row = db.catalogue().album(page.album.id) ?: return true
        val album = decode<Album>(row.json) ?: return true
        fun Album.view() = copy(playCount = null, played = null)
        if (album.view() != page.album.view()) return true
        val ids = page.songs.map { it.id }
        if (decodeIds(row.songIds).take(ids.size) != ids) return true
        // Cheap checks first; the song rows only when those match. By id: a song's own album id may name another album.
        val stored = ids.chunked(ID_CHUNK).flatMap { db.catalogue().songsIn(it) }.associate { it.id to it.json }
        return page.songs.any { song -> stored[song.id]?.let { decode<Song>(it) } != song }
    }

    /** Cached tracks (and kept tracks) with no song entry: one from their own metadata, and their album's if it has none. */
    private suspend fun ingestMinimal(db: LibraryDatabase, refs: References): Int {
        val known = db.catalogue().songRefs().mapTo(HashSet()) { it.id }
        val fresh = refs.metas.filterKeys { it !in known }
        if (fresh.isEmpty()) return 0
        db.catalogue().upsertSongs(fresh.map { (id, meta) -> song(meta.toSong(id), minimal = true) })
        val now = nowMs()
        fresh.entries.groupBy { it.value.albumId }.forEach { (albumId, entries) ->
            if (albumId.isNullOrEmpty()) return@forEach
            val existing = db.catalogue().album(albumId)
            if (existing != null && !existing.minimal) return@forEach
            val meta = entries.first().value
            val members = (existing?.songIds?.let { decode(ListSerializer(String.serializer()), it) }.orEmpty() +
                entries.sortedWith(compareBy({ it.value.disc ?: 0 }, { it.value.track ?: 0 })).map { it.key }).distinct()
            val album = Album(albumId, meta.album, artist = meta.albumArtist ?: meta.artist, coverArt = meta.coverArtId, year = meta.year)
            db.catalogue().upsertAlbums(listOf(AlbumRow(albumId, album.name, null, null, album.artistName, "[]", album.serverCoverArt,
                album.year, ids(members), ArtistFilter.fold("${album.name} ${album.artistName}"), json.encodeToString(album), now, minimal = true)))
            album.serverCoverArt?.let { db.catalogue().upsertImages(listOf(ImageRef(ALBUM, albumId, it))) }
        }
        return fresh.size
    }

    /**
     * Every artist id the catalogue names gets an entry: full from the saved artists list, else id + name ("feat.").
     * A later run upgrades an id + name entry once the list has the artist. JSON is decoded only for new names.
     */
    private suspend fun ingestArtists(db: LibraryDatabase) {
        val c = db.catalogue()
        val referenced = (c.albumRefs().flatMap { decodeIds(it.artistIds) } + c.songRefs().flatMap { decodeIds(it.artistIds) }).toSet()
        val existing = c.artists().associate { it.id to it.trackOnly }
        val fresh = referenced - existing.keys
        val partial = existing.filterValues { it }.keys.filter { it in referenced }
        if (fresh.isEmpty() && partial.isEmpty()) return
        val list = db.pages().get("artists")?.json?.let { decode<ArtistsSnapshot>(it) }?.artists.orEmpty().associateBy { it.id }
        val unnamed = fresh.filterNot { it in list }.toSet()
        val names = HashMap<String, String>()
        if (unnamed.isNotEmpty()) {
            fun note(id: String?, name: String) { if (id != null && id in unnamed) names.putIfAbsent(id, name) }
            fun note(refs: List<ArtistReference>?) = refs.orEmpty().forEach { note(it.id, it.name) }
            for (row in c.albums()) if (decodeIds(row.artistIds).any { it in unnamed }) decode<Album>(row.json)?.let { a -> note(a.artistId, a.artistName); note(a.artists) }
            for (row in c.songs()) if (decodeIds(row.artistIds).any { it in unnamed }) decode<Song>(row.json)?.let { s -> note(s.artistId, s.artistName); note(s.artists); note(s.albumArtists) }
        }
        val rows = (fresh + partial.filter { it in list }).map { id ->
            val artist = list[id]
            if (artist != null) ArtistRow(id, artist.name, artist.sortName, artist.coverArt, artist.albumCount, trackOnly = false,
                ArtistFilter.fold(artist.name), json.encodeToString(artist))
            else (names[id] ?: "").let { name -> ArtistRow(id, name, null, null, null, trackOnly = true, ArtistFilter.fold(name), null) }
        }
        if (rows.isEmpty()) return
        c.upsertArtists(rows)
        c.upsertImages(rows.mapNotNull { row -> row.imageId?.let { ImageRef(ARTIST, row.id, it) } })
    }

    /**
     * Kept playlists: from their saved page, else from the kept set's own track list. Every other
     * playlist whose page copy is saved (opened online once) and that has at least one song among the cached [onPhone]
     * tracks, from that page; the offline views apply the threshold ([OfflineCatalogue.playlistShown]). The rest leave:
     * a playlist no longer kept whose page copy went (trimmed like any ordinary page) or whose songs all left the phone.
     * A page-less row is always a kept one (the readers rely on that, [OfflineCatalogue]).
     */
    private suspend fun ingestPlaylists(db: LibraryDatabase, sets: List<KeptItem>, onPhone: Set<String>) {
        val kept = sets.filter { it.key.startsWith(PLAYLIST) }
        val keptKeys = kept.mapTo(HashSet()) { it.key }
        val rows = kept.map { set ->
            val id = set.key.removePrefix(PLAYLIST)
            val page = db.pages().get(set.key)?.json?.let { decode<PlaylistSnapshot>(it) }
            if (page != null) PlaylistRow(id, page.playlist.name, page.playlist.owner, page.playlist.coverArt,
                ids(page.entries.map { it.id }), json.encodeToString(page.playlist))
            else PlaylistRow(id, set.title.orEmpty(), null, null, ids(set.trackIds), null)
        }.toMutableList()
        for (key in db.pages().playlistKeys()) {
            if (key in keptKeys || onPhone.isEmpty()) continue
            // Only the ids of the page's songs are decoded (a long playlist's page is large).
            val page = db.pages().get(key)?.json?.let { decode<PlaylistIds>(it) } ?: continue
            val members = page.entries.map { it.id }
            if (members.none { it in onPhone }) continue
            rows += PlaylistRow(key.removePrefix(PLAYLIST), page.playlist.name, page.playlist.owner, page.playlist.coverArt,
                ids(members), json.encodeToString(page.playlist))
        }
        val here = rows.mapTo(HashSet()) { it.id }
        val gone = db.catalogue().playlists().map { it.id }.filter { it !in here }
        gone.chunked(CHUNK).forEach { db.catalogue().deletePlaylists(it); db.catalogue().deleteImages(PLAYLIST_KIND, it) }
        db.catalogue().upsertPlaylists(rows)
        db.catalogue().upsertImages(rows.mapNotNull { row -> row.coverArt?.let { ImageRef(PLAYLIST_KIND, row.id, it) } })
    }

    /** A saved playlist page read for its song ids only (the song objects are skipped). */
    @kotlinx.serialization.Serializable
    private data class PlaylistIds(val playlist: Playlist, val entries: List<EntryId> = emptyList())
    @kotlinx.serialization.Serializable
    private data class EntryId(val id: String)

    /** Drops what nothing refers to any more. Returns the number of albums that left. */
    private suspend fun cleanUp(db: LibraryDatabase, refs: References): Int {
        val c = db.catalogue()
        val goneAlbums = c.albumRefs().map { it.id }.filter { it !in refs.albums }
        val songs = c.songRefs()
        val goneSongs = songs.filter { (it.albumId == null || it.albumId !in refs.albums) && it.id !in refs.songs && it.id !in refs.metas }.map { it.id }
        goneAlbums.chunked(CHUNK).forEach { c.deleteAlbums(it); c.deleteImages(ALBUM, it) }
        goneSongs.chunked(CHUNK).forEach { c.deleteSongs(it) }
        val namedArtists = (c.albumRefs().flatMap { decodeIds(it.artistIds) } + c.songRefs().flatMap { decodeIds(it.artistIds) }).toSet()
        val goneArtists = c.artists().map { it.id }.filter { it !in namedArtists }
        goneArtists.chunked(CHUNK).forEach { c.deleteArtists(it); c.deleteImages(ARTIST, it) }
        return goneAlbums.size
    }

    /** What the cache refers to: album ids, song ids with their metadata, kept set keys. */
    private class References(val albums: Set<String>, val songs: Set<String>, val metas: Map<String, TrackMeta>, val sets: Set<String>) {
        companion object {
            fun of(records: List<CachedTrack>, sets: List<KeptItem>): References {
                val metas = LinkedHashMap<String, TrackMeta>()
                records.forEach { r -> r.meta?.let { metas[r.songId] = it } }
                sets.forEach { s -> s.tracks.forEach { t -> t.meta?.let { metas.putIfAbsent(t.id, it) } } }
                val albums = LinkedHashSet<String>()
                sets.forEach { if (it.key.startsWith(ALBUM_SET)) albums += it.key.removePrefix(ALBUM_SET) }
                metas.values.forEach { m -> m.albumId?.takeIf { it.isNotEmpty() }?.let(albums::add) }
                val songs = records.map { it.songId }.toSet() + sets.flatMap { it.trackIds }
                return References(albums, songs, metas, sets.map { it.key }.toSet())
            }
        }
    }

    companion object {
        /** Albums compared per store use on the first pass; song ids per IN query. */
        private const val RECONCILE_CHUNK = 100
        private const val ID_CHUNK = 500
        // Kept set keys (OfflineLibrary.ALBUM / PLAYLIST); image kinds.
        private const val ALBUM_SET = "album/"
        private const val PLAYLIST = "playlist/"
        const val ALBUM = "album"
        const val ARTIST = "artist"
        const val PLAYLIST_KIND = "playlist"
        private const val CHUNK = 500
        internal val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        /**
         * Page copies the catalogue refers to (its albums, kept playlists, its albums' artists): never trimmed. The
         * other playlists in the catalogue are not protected; their page copies are trimmed as before.
         */
        internal suspend fun protectedPages(db: LibraryDatabase): Set<String> {
            val c = db.catalogue()
            val kept = db.cache().setKeys().toSet()
            return buildSet {
                c.albumRefs().forEach { a -> add("album/${a.id}"); decodeIds(a.artistIds).forEach { add("artist/$it") } }
                c.playlists().forEach { if ("playlist/${it.id}" in kept) add("playlist/${it.id}") }
                add("artists")
            }
        }

        private fun ids(list: List<String>) = json.encodeToString(list)
        private fun decodeIds(text: String): List<String> = decode(ListSerializer(String.serializer()), text).orEmpty()
        private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, text: String): T? = runCatching { json.decodeFromString(serializer, text) }.getOrNull()
        private inline fun <reified T> decode(text: String): T? = runCatching { json.decodeFromString<T>(text) }.getOrNull()

        private fun albumArtistIds(album: Album): List<String> = (listOfNotNull(album.artistId) + album.artists.orEmpty().map { it.id }).filter { it.isNotEmpty() }.distinct()
        private fun songArtistIds(song: Song): List<String> =
            (listOfNotNull(song.artistId) + song.artists.orEmpty().map { it.id } + song.albumArtists.orEmpty().map { it.id }).filter { it.isNotEmpty() }.distinct()

        private fun song(song: Song, minimal: Boolean) = SongRow(song.id, song.title, song.albumId, song.artistId, ids(songArtistIds(song)),
            song.track, song.discNumber, song.duration, ArtistFilter.fold("${song.title} ${song.artistName} ${song.album.orEmpty()}"),
            json.encodeToString(song), minimal)

        private fun TrackMeta.toSong(id: String) = Song(id, title, album = album, artist = artist, albumId = albumId,
            coverArt = coverArtId, track = track, discNumber = disc, duration = durationSeconds, year = year, displayAlbumArtist = albumArtist,
            replayGain = replayGain)
    }
}
