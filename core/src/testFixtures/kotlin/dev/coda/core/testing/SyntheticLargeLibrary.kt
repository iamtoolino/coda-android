package dev.coda.core.testing

import dev.coda.core.subsonic.Album
import dev.coda.core.subsonic.Artist
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.random.Random

/**
 * A generated large library for DS-9 device tests and screenshots (~800 artists, ~2,000 albums). Every name and cover
 * is synthetic: names are built from fixed word lists, covers are two-tone PNGs generated from the ID. Deterministic for
 * a given [seed]; dates are relative to [now] so the time sections (This week, Today…) are populated.
 */
class SyntheticLargeLibrary(artistCount: Int = 812, val now: Instant = Instant.now(), seed: Int = 9) {
    val artists: List<Artist>
    val albums: List<Album>
    private val albumsByArtist: Map<String, List<Album>>

    init {
        val random = Random(seed)
        val names = LinkedHashSet<String>()
        names += special
        var i = 0
        while (names.size < artistCount) {
            check(i < 100_000) { "Name generator exhausted" }
            val first = firstWords[i % firstWords.size]
            val last = lastWords[(i / firstWords.size + i * 7) % lastWords.size]
            names += when (i % 9) {
                0 -> "The $first $last"
                3 -> "$first $last Ensemble"
                else -> "$first $last"
            }
            i++
        }
        val artistList = mutableListOf<Artist>()
        val albumList = mutableListOf<Album>()
        names.take(artistCount).forEachIndexed { index, name ->
            val id = "lib-artist-$index"
            val releases = when (name) {
                CHRONOLOGY -> (1994..2025).filter { it % 3 != 2 }.map { it }   // 21 releases over 32 years: decade sections and chips
                SHORT_CAREER -> listOf(2021, 2022, 2023)                       // 3 releases: no decade sections
                ONE_RELEASE -> listOf(2024)
                else -> {
                    val count = when (random.nextInt(10)) { in 0..3 -> 1; in 4..6 -> 2; 7 -> 3; 8 -> 4; else -> 5 + random.nextInt(3) }
                    val start = 1965 + random.nextInt(58)
                    (0 until count).map { (start + it * (1 + random.nextInt(4))).coerceAtMost(now.atZone(java.time.ZoneOffset.UTC).year) }
                }
            }
            val own = releases.mapIndexed { r, year ->
                val n = albumList.size + r
                val title = when {
                    name == ONE_RELEASE -> "Songs for the Slow Thaw"
                    n % 97 == 5 -> "The Cartographer's Daughter and Other Songs for Small Harbours (Deluxe Anniversary Edition)"
                    else -> "${titleWords[(n * 5 + r) % titleWords.size]} ${titleNouns[(n * 11 + r * 3) % titleNouns.size]}"
                }
                val addedDays = if (n % 41 == 0) random.nextInt(5) else random.nextInt(2200)
                val played = if (random.nextInt(10) < 4) now.minus((random.nextDouble().let { it * it * 900 }).toLong(), ChronoUnit.HOURS)
                    .minus(random.nextInt(60).toLong(), ChronoUnit.MINUTES) else null
                Album(id = "lib-album-$n", name = title, artist = name, artistId = id, coverArt = "lib-cover-$n",
                    songCount = 2, duration = 2 * 46, year = year,
                    // Album 0 is added "now" so "This week" (since Monday 00:00) exists at any clock, also early on Monday.
                    created = now.minus(addedDays.toLong(), ChronoUnit.DAYS).minus(random.nextInt(1440).toLong(), ChronoUnit.MINUTES)
                        .let { if (n == 0) now else it }.toString(),
                    played = played?.toString(), playCount = if (played == null) 0 else 1 + (random.nextDouble().let { it * it * it } * 260).toInt(),
                    userRating = if (random.nextInt(10) < 3) 1 + random.nextInt(5) else null, genre = genres[n % genres.size])
            }
            albumList += own
            artistList += Artist(id, name, own.size, coverArt = if (index % 5 < 2) "lib-face-$index" else null, genre = genres[index % genres.size])
        }
        artists = artistList
        albums = albumList
        albumsByArtist = albumList.groupBy { it.artistId!! }
    }

    fun artist(id: String): Artist? = artists.firstOrNull { it.id == id }
    fun albumsOf(id: String): List<Album> = albumsByArtist[id].orEmpty()
    fun artistNamed(name: String): Artist = artists.first { it.name == name }

    /** `getAlbumList2` order per type, like Navidrome: only played albums for recent/frequent, only rated for highest. */
    fun albumList(type: String, randomSeed: Int): List<Album> = when (type) {
        "alphabeticalByName" -> albums.sortedWith(compareBy({ dev.coda.core.library.AlphaBuckets.key(it.name).let { k -> if (k.first() in 'a'..'z') "1$k" else "0$k" } }, { it.id }))
        "newest" -> albums.sortedByDescending { it.created }
        "byYear" -> albums.sortedWith(compareByDescending<Album> { it.year ?: 0 }.thenBy { it.name })
        "recent" -> albums.filter { it.played != null }.sortedByDescending { it.played }
        "frequent" -> albums.filter { (it.playCount ?: 0) > 0 }.sortedByDescending { it.playCount }
        "highest" -> albums.filter { (it.userRating ?: 0) > 0 }.sortedByDescending { it.userRating }
        "random" -> albums.shuffled(Random(randomSeed))
        else -> albums
    }

    companion object {
        const val CHRONOLOGY = "Halden Grey"
        const val SHORT_CAREER = "Brass Lantern Club"
        const val ONE_RELEASE = "Juniper Vale"
        const val LONG_NAME = "Ekström & the Long Name Orchestra of the Northern Coast"
        const val IGNORED_ARTICLES = "The El La Los Las Le Les"

        private val special = listOf(CHRONOLOGY, SHORT_CAREER, ONE_RELEASE, LONG_NAME, "2 Minute Lanterns", "40 Watt Harbour",
            "8-Bit Requiem Society", "+Vessel+", "Élodie Marchetti", "Ève Lacroix-Dubois", "Øyvind Strand", "Ægir Collective",
            "Ångström Choir", "Łukasz Wren", "Þóra Lind", "The Velvet Orchard", "日本 Sound", "Iris & the Long Afternoon Choir of the Lower Valley")
        private val firstWords = listOf("Ada", "Aalto", "Brass", "Celeste", "Dunmore", "Emeric", "Fallow", "Faye", "Feral", "Fjóla",
            "Glass", "Halcyon", "Iris", "Juniper", "Kestrel", "Lumen", "Marisol", "Marrow", "Night", "Omar", "Paper", "Quiet",
            "Radiant", "Ragnhild", "Ravel", "Reiko", "Rhodes", "Ríona", "Signal", "Static", "Tamara", "Tidewater", "Umber",
            "Velvet", "Winter", "Yara", "Zephyr", "Ashgrove", "Bellwether", "Copper", "Driftwood", "Ember", "Ferro", "Gale",
            "Harbour", "Ingrid", "Jasper", "Kiln", "Lantern", "Moss", "Nadia", "Orchid", "Pale", "Rowan", "Saltglass", "Tomas",
            "Ursa", "Vesper", "Willow", "Yuki", "Zora", "Ángel", "Éclat", "Öland")
        private val lastWords = listOf("Marrow", "Lantern", "Okafor", "Static", "Ashgrove", "Coast", "Okonkwo", "Lanterns",
            "Sverrisdóttir", "Hymns", "Grey", "Grid", "Vale", "Theory", "Drift", "Ferry", "Haddad", "Engines", "Lowlands", "Bø",
            "Club", "Esteves", "Quist", "Assembly", "Tanabe", "Collective", "Byrne", "Orchard", "Society", "Harbour", "Lind",
            "Rivers", "Bells", "Machines", "Weather Station", "Kvist", "Torday", "Benning", "Sky", "Moon")
        private val titleWords = listOf("Low", "Second", "Quiet", "Copper", "Night", "Glass", "Paper", "Winter", "Signal", "Ghost",
            "Under the", "Ninety", "Hollow", "Midnight", "Mirror", "Moonlit", "Ember", "Salt", "Tide", "North", "Ash", "Velvet", "12",
            "4 A.M.", "Mære", "Mørke", "Ángel", "The Long", "A Map of", "A Field Guide to")
        private val titleNouns = listOf("Tide Engine", "Harbour", "Machines", "Fires", "Orchard", "Lights", "Moon Variations",
            "Bells", "Degrees", "Lullabies", "Arithmetic", "Protocol", "Glass", "Theory", "Kestrel", "Weatherglass", "Copperline",
            "Saltglass", "Rooms", "Afternoon", "Leaving", "Northbound", "Seaglass", "Undertow", "Wren", "Ostinato", "Lumen")
        private val genres = listOf("Post-rock", "Folk", "Chamber pop", "Ambient", "Jazz", "Electronic", "Indie")

        /** A 48 px two-tone PNG cover whose colours derive from [id] (never real artwork). */
        fun cover(id: String): ByteArray {
            val hash = id.hashCode() * -0x61c88647
            val hue = ((hash ushr 3) % 360).toFloat()
            val a = hsv(hue, .55f, .62f); val b = hsv((hue + 25) % 360, .45f, .18f)
            val size = 48
            val style = (hash ushr 9) and 3
            val raw = ByteArrayOutputStream()
            for (y in 0 until size) {
                raw.write(0)
                for (x in 0 until size) {
                    val first = when (style) {
                        0 -> x + y < size
                        1 -> y < size / 2
                        2 -> (x - 30) * (x - 30) + (y - 18) * (y - 18) > 100
                        else -> (y / 8) % 2 == 0
                    }
                    val c = if (first) a else b
                    raw.write(c[0]); raw.write(c[1]); raw.write(c[2])
                }
            }
            val deflater = Deflater().apply { setInput(raw.toByteArray()); finish() }
            val compressed = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            fun chunk(type: String, data: ByteArray) {
                out.write(int(data.size)); val body = type.toByteArray() + data
                out.write(body); out.write(int(CRC32().apply { update(body) }.value.toInt()))
            }
            chunk("IHDR", int(size) + int(size) + byteArrayOf(8, 2, 0, 0, 0))
            chunk("IDAT", compressed.toByteArray())
            chunk("IEND", ByteArray(0))
            return out.toByteArray()
        }

        private fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

        private fun hsv(h: Float, s: Float, v: Float): IntArray {
            val c = v * s; val x = c * (1 - kotlin.math.abs((h / 60) % 2 - 1)); val m = v - c
            val (r, g, b) = when ((h / 60).toInt()) { 0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x) }
            return intArrayOf(((r + m) * 255).toInt(), ((g + m) * 255).toInt(), ((b + m) * 255).toInt())
        }
    }
}
