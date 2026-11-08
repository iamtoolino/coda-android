package dev.coda.store

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** Row 1: library page copies (the only copy; [SnapshotStore] in `:core` reads and writes them). */
@Dao
interface PageDao {
    /** Writes a page from the server: pinned when a kept set has its key, no longer stale. */
    @Query("INSERT OR REPLACE INTO pages (`key`, json, bytes, usedAt, pinned, stale) VALUES (:key, :json, :bytes, :usedAt, " +
        "EXISTS (SELECT 1 FROM cache_sets WHERE `key` = :key), 0)")
    suspend fun put(key: String, json: String, bytes: Int, usedAt: Long)
    @Query("SELECT * FROM pages WHERE `key` = :key") suspend fun get(key: String): PageRow?
    /** A read: the page becomes the most recently used (LRU). */
    @Query("UPDATE pages SET usedAt = :usedAt WHERE `key` = :key") suspend fun touch(key: String, usedAt: Long)
    @Query("SELECT COALESCE(SUM(bytes), 0) FROM pages") suspend fun totalBytes(): Long
    /** Least recently used first: the trim order. */
    @Query("SELECT `key`, bytes, pinned FROM pages ORDER BY usedAt, `key`") suspend fun byAge(): List<PageSize>
    @Query("DELETE FROM pages WHERE `key` IN (:keys)") suspend fun delete(keys: List<String>)
    @Query("SELECT json FROM pages WHERE pinned = 1") suspend fun pinnedJson(): List<String>
    /** The keys of the saved playlist pages (`playlist/<id>`): the playlists whose song list the phone knows. */
    @Query("SELECT `key` FROM pages WHERE `key` LIKE 'playlist/%'") suspend fun playlistKeys(): List<String>
    /** Up to [limit] pages after [after] in key order (offline search reads them in chunks). */
    @Query("SELECT `key`, json FROM pages WHERE `key` > :after ORDER BY `key` LIMIT :limit")
    suspend fun after(after: String, limit: Int): List<PageJson>
    @Query("UPDATE pages SET stale = 1 WHERE `key` = :key") suspend fun markStale(key: String)
    @Query("SELECT stale FROM pages WHERE `key` = :key") suspend fun stale(key: String): Boolean?
    /** [PageRow.pinned] follows the kept sets. Run in the transaction that changed `cache_sets`. */
    @Query("UPDATE pages SET pinned = (`key` IN (SELECT `key` FROM cache_sets)) WHERE pinned != (`key` IN (SELECT `key` FROM cache_sets))")
    suspend fun syncPins()
}

data class PageSize(val key: String, val bytes: Int, val pinned: Boolean)
data class PageJson(val key: String, val json: String)
data class SongRef(val id: String, val albumId: String?, val artistIds: String)
data class AlbumRef(val id: String, val artistIds: String)
/** An offline Search match: the row id and its display name (song title, album or artist name). */
data class SearchHit(val id: String, val name: String)

/** A song's album and its artist ids (JSON array), for offline Search's artists. */
data class SongArtistIds(val albumId: String?, val artistIds: String)

/** Rows 2 and 3: the catalogue and its picture references. */
@Dao
interface CatalogueDao {
    @Upsert suspend fun upsertAlbums(rows: List<AlbumRow>)
    @Upsert suspend fun upsertSongs(rows: List<SongRow>)
    @Upsert suspend fun upsertArtists(rows: List<ArtistRow>)
    @Upsert suspend fun upsertPlaylists(rows: List<PlaylistRow>)
    @Upsert suspend fun upsertImages(rows: List<ImageRef>)

    @Query("SELECT * FROM albums WHERE id = :id") suspend fun album(id: String): AlbumRow?
    @Query("SELECT * FROM albums ORDER BY id") suspend fun albums(): List<AlbumRow>
    @Query("SELECT id FROM albums WHERE minimal = 0") suspend fun fullAlbumIds(): List<String>
    @Query("SELECT * FROM songs WHERE id = :id") suspend fun song(id: String): SongRow?
    @Query("SELECT * FROM songs WHERE albumId = :albumId ORDER BY disc, track, id") suspend fun songsOf(albumId: String): List<SongRow>
    @Query("SELECT * FROM songs ORDER BY id") suspend fun songs(): List<SongRow>
    @Query("SELECT id, albumId, artistIds FROM songs") suspend fun songRefs(): List<SongRef>
    @Query("SELECT id, artistIds FROM albums") suspend fun albumRefs(): List<AlbumRef>
    /** Songs whose artist ids may name [pattern] (`%"id"%`; the caller checks the decoded ids): an artist page offline. */
    @Query("SELECT id, albumId, artistIds FROM songs WHERE artistIds LIKE :pattern") suspend fun songRefsNaming(pattern: String): List<SongRef>
    @Query("SELECT id FROM artists WHERE trackOnly = 0") suspend fun fullArtistIds(): List<String>
    @Query("SELECT * FROM artists WHERE id = :id") suspend fun artist(id: String): ArtistRow?
    @Query("SELECT * FROM artists ORDER BY id") suspend fun artists(): List<ArtistRow>
    @Query("SELECT * FROM playlists WHERE id = :id") suspend fun playlist(id: String): PlaylistRow?
    @Query("SELECT * FROM playlists ORDER BY id") suspend fun playlists(): List<PlaylistRow>
    @Query("SELECT * FROM images ORDER BY kind, ownerId") suspend fun images(): List<ImageRef>

    @Query("DELETE FROM albums WHERE id IN (:ids)") suspend fun deleteAlbums(ids: List<String>)
    @Query("DELETE FROM songs WHERE id IN (:ids)") suspend fun deleteSongs(ids: List<String>)
    @Query("DELETE FROM artists WHERE id IN (:ids)") suspend fun deleteArtists(ids: List<String>)
    @Query("DELETE FROM playlists WHERE id IN (:ids)") suspend fun deletePlaylists(ids: List<String>)
    @Query("DELETE FROM images WHERE kind = :kind AND ownerId IN (:ids)") suspend fun deleteImages(kind: String, ids: List<String>)

    @Query("SELECT albums.* FROM albums JOIN albums_fts ON albums.rowid = albums_fts.rowid WHERE albums_fts MATCH :match ORDER BY albums.name, albums.id LIMIT :limit")
    suspend fun searchAlbums(match: String, limit: Int): List<AlbumRow>
    @Query("SELECT songs.* FROM songs JOIN songs_fts ON songs.rowid = songs_fts.rowid WHERE songs_fts MATCH :match ORDER BY songs.title, songs.id LIMIT :limit")
    suspend fun searchSongs(match: String, limit: Int): List<SongRow>
    @Query("SELECT artists.* FROM artists JOIN artists_fts ON artists.rowid = artists_fts.rowid WHERE artists_fts MATCH :match ORDER BY artists.name, artists.id LIMIT :limit")
    suspend fun searchArtists(match: String, limit: Int): List<ArtistRow>

    // Offline Search reads each kind's matches once (ids and names only, no IN list per chunk of ids on the phone);
    // the caller keeps those on the phone, orders them and reads the rows of the first few.
    @Query("SELECT songs.id AS id, songs.title AS name FROM songs JOIN songs_fts ON songs.rowid = songs_fts.rowid WHERE songs_fts MATCH :match")
    suspend fun songHits(match: String): List<SearchHit>
    @Query("SELECT albums.id AS id, albums.name AS name FROM albums JOIN albums_fts ON albums.rowid = albums_fts.rowid WHERE albums_fts MATCH :match")
    suspend fun albumHits(match: String): List<SearchHit>
    @Query("SELECT artists.id AS id, artists.name AS name FROM artists JOIN artists_fts ON artists.rowid = artists_fts.rowid WHERE artists_fts MATCH :match")
    suspend fun artistHits(match: String): List<SearchHit>
    @Query("SELECT * FROM songs WHERE id IN (:ids)") suspend fun songsIn(ids: List<String>): List<SongRow>
    @Query("SELECT * FROM albums WHERE id IN (:ids)") suspend fun albumsIn(ids: List<String>): List<AlbumRow>
    @Query("SELECT * FROM artists WHERE id IN (:ids)") suspend fun artistsIn(ids: List<String>): List<ArtistRow>
    /** The album and artist id list (JSON) of the songs [ids]: the track artists on the phone, per album. */
    @Query("SELECT albumId, artistIds FROM songs WHERE id IN (:ids)") suspend fun artistIdsOf(ids: List<String>): List<SongArtistIds>
}

/** Rows 4 and 5: persisted colours (MO-1 keeps the memory front cache; this is its one persistent store). */
@Dao
interface ColourDao {
    @Upsert suspend fun upsertAccent(row: AccentRow)
    @Query("SELECT * FROM accents WHERE coverId = :coverId AND size = :size AND algorithm = :algorithm")
    suspend fun accent(coverId: String, size: Int, algorithm: Int): AccentRow?
    @Query("SELECT * FROM accents WHERE algorithm = :algorithm ORDER BY usedAt DESC LIMIT :limit")
    suspend fun recentAccents(algorithm: Int, limit: Int): List<AccentRow>
    @Query("DELETE FROM accents WHERE coverId IN (:coverIds)") suspend fun forgetAccents(coverIds: List<String>)
    @Query("DELETE FROM accents WHERE algorithm != :algorithm") suspend fun dropOtherAccents(algorithm: Int)

    @Upsert suspend fun upsertLook(row: ArtistLookRow)
    @Query("SELECT * FROM artist_looks WHERE algorithm = :algorithm ORDER BY usedAt DESC LIMIT :limit")
    suspend fun recentLooks(algorithm: Int, limit: Int): List<ArtistLookRow>
    @Query("SELECT * FROM artist_looks WHERE imageId = :imageId AND width = :width AND height = :height AND algorithm = :algorithm")
    suspend fun look(imageId: String, width: Int, height: Int, algorithm: Int): ArtistLookRow?
    @Query("DELETE FROM artist_looks WHERE imageId IN (:imageIds)") suspend fun forgetLooks(imageIds: List<String>)
    @Query("DELETE FROM artist_looks WHERE algorithm != :algorithm") suspend fun dropOtherLooks(algorithm: Int)
}
