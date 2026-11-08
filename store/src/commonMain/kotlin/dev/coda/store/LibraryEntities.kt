package dev.coda.store

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

// `library.db` (one per account: server + user). Rows keep the server's own object as JSON ([json]) next to the
// indexed columns, so later readers reuse today's screens and sorts unchanged. Every `folded` column is already folded by
// the caller (core's ArtistFilter.fold); FTS MATCH input must be folded too (see [prefixMatch]).

/**
 * Row 1: a stale-while-revalidate copy of a library page (`album/<id>`, `artist/<id>`, `artists`, `albums`, `playlists`,
 * `playlist/<id>`, `home`). The only copy (the snapshot files are gone). [usedAt] is the last read or write (LRU).
 * [pinned]: the page of a kept set (`cache_sets` has its key); it changes in the same transaction as the sets, is never
 * trimmed, and survives a rebuild ([LibraryDatabase.CLEAR_REBUILDABLE]). [stale]: a playlist edited from a menu, out of
 * date until the server's page is written again.
 */
@Entity(tableName = "pages")
data class PageRow(@PrimaryKey val key: String, val json: String, val bytes: Int, val usedAt: Long,
    val pinned: Boolean = false, val stale: Boolean = false)

/**
 * Row 2: a catalogue album (referenced by a cached track or a kept set). [songIds] is the JSON array of all its songs in
 * order; [minimal] marks an entry built from a cached track's own metadata because the album page is not here yet.
 */
@Entity(tableName = "albums", indices = [Index("artistId")])
data class AlbumRow(
    @PrimaryKey val id: String,
    val name: String,
    val sortName: String?,
    val artistId: String?,
    val artistName: String,
    /** JSON array of every artist id the album names (`artistId` and `artists`). */
    val artistIds: String,
    val coverArt: String?,
    val year: Int?,
    val songIds: String,
    val folded: String,
    val json: String,
    val fetchedAt: Long,
    val minimal: Boolean,
)

/** Row 2: a catalogue song with its path (album id, artist id; all artist ids are in [json]). */
@Entity(tableName = "songs", indices = [Index("albumId"), Index("artistId")])
data class SongRow(
    @PrimaryKey val id: String,
    val title: String,
    val albumId: String?,
    val artistId: String?,
    /** JSON array of every artist id the song names (`artistId`, `artists`, `albumArtists`). */
    val artistIds: String,
    val track: Int?,
    val disc: Int?,
    val duration: Int?,
    val folded: String,
    val json: String,
    val minimal: Boolean,
)

/** Row 2: an artist of a catalogue album or song; [trackOnly] for a "feat." artist known only by id and name. */
@Entity(tableName = "artists")
data class ArtistRow(
    @PrimaryKey val id: String,
    val name: String,
    val sortName: String?,
    val imageId: String?,
    val albumCount: Int?,
    val trackOnly: Boolean,
    val folded: String,
    val json: String?,
)

/** Row 2: a kept playlist with its ordered member ids (JSON array). [json] is the server's playlist when its page was here. */
@Entity(tableName = "playlists")
data class PlaylistRow(
    @PrimaryKey val id: String,
    val name: String,
    val owner: String?,
    val coverArt: String?,
    val memberIds: String,
    val json: String?,
)

/** Row 3: which picture belongs to which catalogue item ([kind] `album`, `artist` or `playlist`). */
@Entity(tableName = "images", primaryKeys = ["kind", "ownerId"], indices = [Index("artId")])
data class ImageRef(val kind: String, val ownerId: String, val artId: String)

/** Row 4: an album accent, keyed cover id + size + algorithm version (the server and user are the database's own). */
@Entity(tableName = "accents", primaryKeys = ["coverId", "size", "algorithm"])
data class AccentRow(
    val coverId: String,
    val size: Int,
    val algorithm: Int,
    val red: Double,
    val green: Double,
    val blue: Double,
    val kind: String,
    val usedAt: Long,
)

/**
 * Row 5: an artist photo's colours (DS-11 page, AR-3 years; ARGB), keyed image + the view size they were derived for +
 * algorithm version; [artistId] says whose photo it is.
 */
@Entity(tableName = "artist_looks", primaryKeys = ["imageId", "width", "height", "algorithm"], indices = [Index("artistId")])
data class ArtistLookRow(
    val imageId: String,
    val width: Int,
    val height: Int,
    val algorithm: Int,
    val artistId: String,
    val page: Int,
    val years: Int?,
    val usedAt: Long,
)

// External-content FTS4 indexes, kept in sync by Room's triggers (write with @Upsert, never REPLACE: STORE-0 risk 1).
// unicode61 splits on every non-letter/non-digit, which is what [prefixMatch] assumes.
@Fts4(contentEntity = AlbumRow::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "albums_fts")
data class AlbumFts(val folded: String)

@Fts4(contentEntity = SongRow::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "songs_fts")
data class SongFts(val folded: String)

@Fts4(contentEntity = ArtistRow::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "artists_fts")
data class ArtistFts(val folded: String)

/**
 * Word-prefix MATCH expression for already folded text: "ange de" -> "ange* de*". Empty when nothing searchable is left.
 * It splits where the unicode61 tokenizer splits (anything that is not a letter, a number or a private-use character),
 * so "r.e.m." gives "r* e* m*", the same terms the index holds. Supplementary-plane characters count as separators.
 */
fun prefixMatch(folded: String): String =
    buildList {
        val word = StringBuilder()
        fun flush() {
            if (word.isNotEmpty()) add("$word*")
            word.clear()
        }
        for (c in folded) if (isTokenChar(c)) word.append(c) else flush()
        flush()
    }.joinToString(" ")

private fun isTokenChar(c: Char): Boolean = when (c.category) {
    CharCategory.UPPERCASE_LETTER, CharCategory.LOWERCASE_LETTER, CharCategory.TITLECASE_LETTER,
    CharCategory.MODIFIER_LETTER, CharCategory.OTHER_LETTER,
    CharCategory.DECIMAL_DIGIT_NUMBER, CharCategory.LETTER_NUMBER, CharCategory.OTHER_NUMBER,
    CharCategory.PRIVATE_USE,
    -> true
    else -> false
}
