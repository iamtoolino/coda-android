package dev.coda.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

// The music cache's index (was `music/<key>/index.json`, `downloads/queue.json`, `downloads/replacements-cancelled.json`).
// These tables are authoritative: unlike the catalogue they are never dropped to be fetched again (see
// [LibraryDatabase.CACHE_TABLES]). Dates are plain wall-clock ms. Each row keeps the minimal metadata its track needs to
// play and be listed offline (JSON written by `:core`), so it does not depend on the catalogue tables.

/**
 * The one state row (id 0). Its presence means "an index exists": a database without it (new, recreated, quarantined)
 * is "no index", and the cache then adopts the audio files it finds instead of deleting them. [writer] names the cache
 * instance that wrote last, so a writer whose rows may be stale rewrites them all.
 */
@Entity(tableName = "cache_state")
data class CacheStateRow(
    @PrimaryKey val id: Int = 0,
    val writer: String,
    /** JSON `RemovedForSpace`, or null. */
    val removedForSpace: String?,
    /** JSON `KeptItem` of the running queue download, or null. */
    val queueDownload: String?,
)

/** A cached audio file (`CachedTrack`). [format] and [meta] are JSON. */
@Entity(tableName = "cache_tracks")
data class CacheTrackRow(
    @PrimaryKey val songId: String,
    val file: String,
    /** `RAW` or `OPUS_128`: what arrived. */
    val kind: String,
    val bytes: Long,
    val addedAt: Long,
    val lastPlayedAt: Long?,
    val playCount: Int,
    val contentType: String?,
    val format: String,
    val meta: String?,
)

/** A kept set (`KeptItem` JSON) at its place in the list. */
@Entity(tableName = "cache_sets")
data class CacheSetRow(@PrimaryKey val key: String, val position: Int, val addedAt: Long, val item: String)

/** A track a kept set still waits for. */
@Entity(tableName = "cache_pending", primaryKeys = ["setKey", "songId"])
data class CachePendingRow(val setKey: String, val songId: String)

/** An audio file in `legacy/` whose song is not known yet (found without an index); adopted when the song is seen. */
@Entity(tableName = "cache_legacy")
data class CacheLegacyRow(@PrimaryKey val name: String, val bytes: Long, val datedAt: Long)

/** An Opus copy whose replacement by the original the user cancelled (DL-1 Cancel). */
@Entity(tableName = "cache_replacements_cancelled")
data class CacheCancelledRow(@PrimaryKey val songId: String)

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache_state WHERE id = 0") suspend fun state(): CacheStateRow?
    @Upsert suspend fun upsertState(row: CacheStateRow)
    @Query("SELECT * FROM cache_tracks") suspend fun tracks(): List<CacheTrackRow>
    @Query("SELECT * FROM cache_sets ORDER BY position") suspend fun sets(): List<CacheSetRow>
    @Query("SELECT * FROM cache_pending") suspend fun pending(): List<CachePendingRow>
    @Query("SELECT * FROM cache_legacy") suspend fun legacy(): List<CacheLegacyRow>
    @Query("SELECT songId FROM cache_replacements_cancelled") suspend fun cancelled(): List<String>

    @Upsert suspend fun upsertTracks(rows: List<CacheTrackRow>)
    @Upsert suspend fun upsertSets(rows: List<CacheSetRow>)
    @Upsert suspend fun upsertPending(rows: List<CachePendingRow>)
    @Upsert suspend fun upsertLegacy(rows: List<CacheLegacyRow>)
    @Upsert suspend fun upsertCancelled(rows: List<CacheCancelledRow>)

    @Query("DELETE FROM cache_tracks WHERE songId IN (:ids)") suspend fun deleteTracks(ids: List<String>)
    @Query("DELETE FROM cache_sets WHERE `key` IN (:keys)") suspend fun deleteSets(keys: List<String>)
    @Query("DELETE FROM cache_pending WHERE setKey = :setKey AND songId IN (:ids)") suspend fun deletePending(setKey: String, ids: List<String>)
    @Query("DELETE FROM cache_legacy WHERE name IN (:names)") suspend fun deleteLegacy(names: List<String>)
    @Query("DELETE FROM cache_replacements_cancelled WHERE songId IN (:ids)") suspend fun deleteCancelled(ids: List<String>)

    @Query("SELECT songId FROM cache_tracks") suspend fun trackIds(): List<String>
    @Query("SELECT `key` FROM cache_sets") suspend fun setKeys(): List<String>
    @Query("DELETE FROM cache_pending") suspend fun clearPending()
    @Query("DELETE FROM cache_legacy") suspend fun clearLegacy()
    @Query("DELETE FROM cache_replacements_cancelled") suspend fun clearCancelled()
}
