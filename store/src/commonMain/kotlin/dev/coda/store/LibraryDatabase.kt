package dev.coda.store

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.SQLiteDriver
import kotlin.coroutines.CoroutineContext

/**
 * `library.db`, one file per account. It is durable. It holds the music cache's index ([CACHE_TABLES]), which
 * is authoritative, beside copies of server data and derived colours ([REBUILDABLE_TABLES]), which a migration may clear
 * to be fetched again. There is no destructive fallback. V1 is frozen, so every schema change bumps [VERSION] with
 * a real migration, and the exported schema of each version is in `store/schemas/`. Recreating the whole file stays only the
 * last resort for a damaged file (`LibraryStore`): without the cache's state row it reads as "no index", and the cache
 * then adopts the audio it finds instead of deleting it.
 */
@Database(
    entities = [
        PageRow::class, AlbumRow::class, SongRow::class, ArtistRow::class, PlaylistRow::class, ImageRef::class,
        AccentRow::class, ArtistLookRow::class, AlbumFts::class, SongFts::class, ArtistFts::class,
        CacheStateRow::class, CacheTrackRow::class, CacheSetRow::class, CachePendingRow::class, CacheLegacyRow::class,
        CacheCancelledRow::class, WaveformRow::class,
        CoverStampRow::class, PlayedAlbumRow::class,
    ],
    version = LibraryDatabase.VERSION,
    exportSchema = true,
)
@ConstructedBy(LibraryDatabaseConstructor::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun pages(): PageDao
    abstract fun catalogue(): CatalogueDao
    abstract fun colours(): ColourDao
    abstract fun cache(): CacheDao
    abstract fun waveforms(): WaveformDao
    abstract fun covers(): CoverDao

    companion object {
        const val VERSION = 1

        /**
         * Copies of server data and derived colours: a migration may clear these ([CLEAR_REBUILDABLE]), and they fill again
         * from the server. `pages` stays here although its pinned rows (the pages of kept sets) are cache data: the
         * other pages are a 20 MB LRU of refetchable copies, so clearing it keeps only the pinned rows.
         */
        val REBUILDABLE_TABLES = listOf("pages", "albums", "songs", "artists", "playlists", "images", "accents", "artist_looks",
            "albums_fts", "songs_fts", "artists_fts")

        /**
         * The music cache's index: never dropped or cleared by a migration (the music on the phone depends on it). * also `waveforms`, which could be computed again but is no catalogue data, so a catalogue rebuild keeps it.
         */
        val CACHE_TABLES = listOf("cache_state", "cache_tracks", "cache_sets", "cache_pending", "cache_legacy",
            "cache_replacements_cancelled", "waveforms",
            "cache_cover_stamps", "cache_played_albums")

        /** What a migration runs to clear [REBUILDABLE_TABLES]: everything except the pinned pages (the kept sets' pages). */
        val CLEAR_REBUILDABLE: List<String> get() = REBUILDABLE_TABLES.map { table ->
            if (table == "pages") "DELETE FROM pages WHERE pinned = 0" else "DELETE FROM `$table`"
        }

        /** Finishes a platform builder: AndroidSQLiteDriver on the phone, BundledSQLiteDriver in JVM tests. */
        fun open(builder: Builder<LibraryDatabase>, driver: SQLiteDriver, queryContext: CoroutineContext): LibraryDatabase =
            builder.setDriver(driver).setQueryCoroutineContext(queryContext).build()
    }
}

// Room's KSP processor generates the actual object for each target.
@Suppress("KotlinNoActualForExpect")
expect object LibraryDatabaseConstructor : RoomDatabaseConstructor<LibraryDatabase> {
    override fun initialize(): LibraryDatabase
}
