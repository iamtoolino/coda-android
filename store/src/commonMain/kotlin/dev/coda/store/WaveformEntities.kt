package dev.coda.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

// The per-song waveforms (were `music/<key>/waveforms/*.waveform`, aged by file mtime). Rebuildable (computed again
// from the audio) but not catalogue data, so they are in [LibraryDatabase.CACHE_TABLES]: a catalogue rebuild keeps them.

/** One song's waveform: [peaks] is the 808-byte blob `:core` writes; [datedAt] is max(last played, computed), wall-clock ms. */
// The (datedAt, songId) index covers the date reads and both rules, so they never page through the blobs.
@Entity(tableName = "waveforms", indices = [Index("datedAt", "songId")])
class WaveformRow(@PrimaryKey val songId: String, val peaks: ByteArray, val datedAt: Long)

class WaveformDate(val songId: String, val datedAt: Long)

@Dao
interface WaveformDao {
    @Query("SELECT songId, datedAt FROM waveforms") suspend fun dates(): List<WaveformDate>
    @Query("SELECT * FROM waveforms WHERE songId IN (:ids)") suspend fun rows(ids: List<String>): List<WaveformRow>
    @Upsert suspend fun upsert(row: WaveformRow)
    /** A play renews the date; never moves it back. */
    @Query("UPDATE waveforms SET datedAt = MAX(datedAt, :at) WHERE songId = :songId") suspend fun renew(songId: String, at: Long)
    @Query("DELETE FROM waveforms WHERE songId IN (:ids)") suspend fun delete(ids: List<String>)

    /** The age rule: rows dated at or before [cutoff]. */
    @Query("SELECT songId FROM waveforms WHERE datedAt <= :cutoff") suspend fun expired(cutoff: Long): List<String>
    @Query("DELETE FROM waveforms WHERE datedAt <= :cutoff") suspend fun deleteExpired(cutoff: Long)

    /** The cap rule: the oldest rows beyond [cap]. */
    @Query("SELECT songId FROM waveforms ORDER BY datedAt, songId LIMIT MAX(0, (SELECT COUNT(*) FROM waveforms) - :cap)")
    suspend fun overCap(cap: Int): List<String>
    @Query("DELETE FROM waveforms WHERE songId IN (SELECT songId FROM waveforms ORDER BY datedAt, songId " +
        "LIMIT MAX(0, (SELECT COUNT(*) FROM waveforms) - :cap))")
    suspend fun deleteOverCap(cap: Int)
}
