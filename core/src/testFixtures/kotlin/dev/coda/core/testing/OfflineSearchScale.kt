package dev.coda.core.testing

import dev.coda.core.cache.CachedTrack
import dev.coda.core.cache.TrackMeta
import dev.coda.core.library.CatalogueCollector
import dev.coda.core.library.LibraryStore
import dev.coda.core.library.OfflineCatalogue
import dev.coda.core.playback.StreamFormat
import dev.coda.core.subsonic.Album
import dev.coda.core.subsonic.AlbumPage
import dev.coda.core.subsonic.Artist
import dev.coda.core.subsonic.ArtistReference
import dev.coda.core.subsonic.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * A synthetic cached library of [songs] songs on [albums] albums by [artists] artists (every 10th song features one
 * of 50 guests) and offline Search measured on it. Names come from a word list; only a handful of titles hold a "q" word,
 * so "q", "qua" and "quartz" are the rare queries and "s", "sil" and "silver" the common ones. JVM test and device test share it.
 */
object OfflineSearchScale {
    private val words = ("silver silence silk summer sky stone song shadow sun river light love night dream fire heart rain " +
        "blue gold city road home wild ocean echo glass storm winter garden velvet neon paper island morning cold " +
        "bright dark violet amber copper iron hollow golden secret lonely broken electric distant").split(" ")

    data class Library(val onPhone: Set<String>, val albumIds: List<String>)

    /** Fills [store] the way the app does: the artists page saved, then a catalogue run over the cache that reads each album. */
    suspend fun fill(store: LibraryStore, songs: Int = 20_000, albums: Int = 1_600, artists: Int = 600): Library {
        val rnd = kotlin.random.Random(1)
        fun name(n: Int) = (1..n).joinToString(" ") { words[rnd.nextInt(words.size)].replaceFirstChar(Char::uppercase) }
        val bands = (0 until artists).map { Artist("ar-%04d".format(it), if (it == 7) "Quinn Quartzman" else name(2)) }
        val guests = (0 until 50).map { ArtistReference("ft-%03d".format(it), name(2)) }
        val perAlbum = songs / albums
        val extra = songs % albums
        var next = 0
        val pages = (0 until albums).map { a ->
            val band = bands[a % artists]
            val album = Album("al-%05d".format(a), if (a == 11) "Quartz Hours" else name(1 + rnd.nextInt(3)), artist = band.name,
                artistId = band.id, songCount = perAlbum + if (a < extra) 1 else 0, year = 1960 + a % 60)
            AlbumPage(album, (1..album.songCount!!).map { k ->
                val n = next++
                val credit = if (n % 10 == 3) guests[n / 10 % guests.size] else ArtistReference(band.id, band.name)
                val title = if (n % 4_000 == 7) "Quartzite " + name(1) else name(1 + rnd.nextInt(4))
                Song("s-%06d".format(n), title, album = album.name, artist = credit.name, albumId = album.id, artistId = credit.id,
                    track = k, discNumber = 1, duration = 200, artists = listOf(credit))
            })
        }
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }
        store.mirrorPage("artists", json.encodeToString(JsonObject.serializer(),
            JsonObject(mapOf("artists" to JsonArray(bands.map { json.encodeToJsonElement(it) })))))
        store.awaitWrites()
        val byId = pages.associateBy { it.album.id }
        val records = pages.flatMap { it.songs }.map { CachedTrack(it.id, "${it.id}.orig", StreamFormat.RAW, 10, 0, meta = TrackMeta.from(it)) }
        CatalogueCollector(store, { id -> byId.getValue(id) }, maxFetchesPerRun = Int.MAX_VALUE).collect(records, emptyList()) { true }
        return Library(records.mapTo(HashSet()) { it.songId }, pages.map { it.album.id })
    }

    /** One query's numbers: median and worst time of a search, results, a browse read's wait. */
    data class Row(val query: String, val medianMs: Double, val maxMs: Double, val artists: Int, val albums: Int, val songs: Int,
        val browseIdleMs: Double, val browseDuringMs: Double) {
        override fun toString() = "q=%-8s median=%7.1f ms  max=%7.1f ms  results=%d/%d/%d  browse idle=%.1f ms during=%.1f ms"
            .format("\"$query\"", medianMs, maxMs, artists, albums, songs, browseIdleMs, browseDuringMs)
    }

    val queries = listOf("s", "sil", "silver", "q", "qua", "quartz")

    /**
     * Each query [runs] times after a warm-up. The browse probe is an album page read ([OfflineCatalogue.album]) started 2 ms into a search: its time
     * against the same read idle is how long the library lock keeps browsing waiting.
     */
    suspend fun measure(store: LibraryStore, library: Library, runs: Int = 9): List<Row> {
        val catalogue = OfflineCatalogue(store)
        val shelf = catalogue.shelf(library.onPhone)
        val page = library.albumIds[library.albumIds.size / 2]
        repeat(3) { catalogue.search("silver", shelf, library.onPhone); checkNotNull(catalogue.album(page, library.onPhone)) }
        return queries.map { q ->
            val found = catalogue.search(q, shelf, library.onPhone)
            val times = (1..runs).map { ms { catalogue.search(q, shelf, library.onPhone) } }.sorted()
            val idle = (1..runs).map { ms { catalogue.album(page, library.onPhone) } }.sorted()[runs / 2]
            val during = (1..runs).map {
                coroutineScope {
                    val search = async { catalogue.search(q, shelf, library.onPhone) }
                    delay(2)
                    val t = ms { catalogue.album(page, library.onPhone) }
                    search.await()
                    t
                }
            }.sorted()[runs / 2]
            Row(q, times[runs / 2], times.last(), found.artists.size, found.albums.size, found.songs.size, idle, during)
        }
    }

    private inline fun ms(block: () -> Unit): Double { val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1e6 }
}
