package dev.coda.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

// The cover store's index (was the append-only `music/<key>/covers/index` TSV). Cache data like the other
// `cache_*` tables ([LibraryDatabase.CACHE_TABLES]); the cover image files stay files next to it.

/** The server's coverArt id (stamp) a stored cover copy ([name], `<sha1>-<size>`) was fetched under, for revalidation. */
@Entity(tableName = "cache_cover_stamps")
data class CoverStampRow(@PrimaryKey val name: String, val stamp: String)

/** An album played on this phone: its 472 and 1440 cover copies are wanted. */
@Entity(tableName = "cache_played_albums")
data class PlayedAlbumRow(@PrimaryKey val albumId: String)

@Dao
interface CoverDao {
    @Query("SELECT * FROM cache_cover_stamps") suspend fun stamps(): List<CoverStampRow>
    @Query("SELECT albumId FROM cache_played_albums") suspend fun played(): List<String>
    @Upsert suspend fun upsertStamp(row: CoverStampRow)
    @Upsert suspend fun upsertPlayed(rows: List<PlayedAlbumRow>)
}
