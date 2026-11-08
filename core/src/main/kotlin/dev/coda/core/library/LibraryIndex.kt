package dev.coda.core.library

import dev.coda.core.subsonic.Album
import dev.coda.core.subsonic.AlbumListType
import dev.coda.core.subsonic.Artist
import java.text.Collator
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * One section of a long library list (DS-9 spec §0.2/§2): [label] for the header, [count] items starting at [start]
 * in the item list (items only, without headers). [stars] is set for Top Rated (the header draws N stars).
 */
data class LibrarySection(val label: String, val count: Int, val start: Int, val stars: Int? = null)

/** Navidrome's default ignored articles; albums carry no server list, so Title uses these like the Artists index does. */
val DefaultIgnoredArticles: List<String> = listOf("The", "El", "La", "Los", "Las", "Le", "Les", "Os", "As", "O", "A")

/** Thresholds: the index features appear from this many items, decided per library load. */
const val ArtistIndexThreshold = 30
const val AlbumSectionThreshold = 60

/** Section letter of a sort key: NFD fold-down plus the non-decomposing letters (Ø, Æ, Œ, ß, Þ, Ł); anything else is "#". */
fun sectionLetter(key: String): Char = AlphaBuckets.letter(key)

/** Server sort name, else the name without one of the server's ignored articles ("The Velvet Orchard" goes under V). */
fun artistSortKey(name: String, sortName: String?, ignoredArticles: List<String>): String {
    sortName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val trimmed = name.trim()
    for (article in ignoredArticles) {
        if (trimmed.length > article.length + 1 && trimmed.startsWith("$article ", ignoreCase = true))
            return trimmed.substring(article.length + 1).trimStart()
    }
    return trimmed
}

/** Artists in index order ("#" first, then A–Z by the folded sort key) with one section per populated letter. */
class ArtistIndex private constructor(val artists: List<Artist>, val sections: List<LibrarySection>) {
    /** Section of the artist at [index], by binary search; -1 when [sections] is empty. */
    fun sectionOf(index: Int): Int = sectionAt(sections, index)

    companion object {
        fun of(artists: List<Artist>, locale: Locale = Locale.getDefault()): ArtistIndex {
            val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
            val keyed = artists.map { artist ->
                val key = artist.sortName?.takeIf { it.isNotBlank() } ?: artist.name
                Triple(artist, sectionLetter(key), AlphaBuckets.key(key))
            }
            val sorted = keyed.sortedWith(compareBy<Triple<Artist, Char, String>>({ rank(it.second) })
                .thenComparator { a, b -> collator.compare(a.third, b.third) }
                .thenBy { it.first.name }.thenBy { it.first.id })
            return ArtistIndex(sorted.map { it.first }, runs(sorted.map { it.second.toString() }))
        }
    }
}

internal fun rank(letter: Char) = if (letter == '#') 0 else letter - 'A' + 1

/** One section per run of equal labels, in list order. */
internal fun runs(labels: List<String>): List<LibrarySection> {
    val sections = mutableListOf<LibrarySection>()
    var start = 0
    for (i in 1..labels.size) {
        if (i == labels.size || labels[i] != labels[start]) {
            sections += LibrarySection(labels[start], i - start, start)
            start = i
        }
    }
    return sections
}

internal fun sectionAt(sections: List<LibrarySection>, index: Int): Int {
    if (sections.isEmpty()) return -1
    var low = 0; var high = sections.lastIndex
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (sections[mid].start <= index) low = mid else high = mid - 1
    }
    return low
}

/** Case- and diacritic-insensitive substring filter (spec §1 Filter); [Match.range] indexes the original name. */
object ArtistFilter {
    data class Match(val artist: Artist, val range: IntRange)

    /** A name folded once: [text] is the folded string, [origin] maps each folded char to its index in the original. */
    class Folded internal constructor(val text: String, internal val origin: IntArray, private val length: Int) {
        /** Range in the original name for a match of [length] folded chars at [at]; trailing combining marks stay inside it. */
        fun range(at: Int, length: Int): IntRange {
            val end = if (at + length < origin.size) origin[at + length] else this.length
            return origin[at] until end
        }
    }

    /** Folds one character to one character (null for a combining mark, which has no place of its own). */
    private fun fold(c: Char): Char? {
        if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) return null
        val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull() ?: c
        return when (val lower = base.lowercaseChar()) {
            'ø' -> 'o'; 'æ' -> 'a'; 'œ' -> 'o'; 'ß' -> 's'; 'þ' -> 't'; 'ł' -> 'l'; 'đ', 'ð' -> 'd'; 'ı' -> 'i'
            else -> lower
        }
    }

    fun fold(text: String): String = folded(text).text

    fun folded(text: String): Folded {
        val out = StringBuilder(text.length)
        val origin = IntArray(text.length)
        text.forEachIndexed { i, c -> fold(c)?.let { origin[out.length] = i; out.append(it) } }
        return Folded(out.toString(), origin.copyOf(out.length), text.length)
    }

    /** The names folded once, for a list that is filtered again on every keystroke. */
    class Index(val artists: List<Artist>) {
        private val names = artists.map { folded(it.name) }
        fun matches(query: String): List<Match> {
            val needle = fold(query.trim())
            if (needle.isEmpty()) return artists.map { Match(it, IntRange.EMPTY) }
            val result = ArrayList<Match>()
            for (i in artists.indices) {
                val at = names[i].text.indexOf(needle)
                if (at >= 0) result += Match(artists[i], names[i].range(at, needle.length))
            }
            return result
        }
    }

    fun matches(artists: List<Artist>, query: String): List<Match> = Index(artists).matches(query)
}

/** "Recently played" faces: artists of the recently played albums, de-duplicated, compilations skipped. */
fun recentArtists(recentAlbums: List<Album>, library: List<Artist>, limit: Int = 10): List<Artist> {
    val byId = library.associateBy { it.id }
    val seen = HashSet<String>()
    val result = mutableListOf<Artist>()
    for (album in recentAlbums) {
        if (album.isCompilation == true || album.artistName.equals("Various Artists", ignoreCase = true)) continue
        val reference = album.artists?.firstOrNull()
        val id = reference?.id ?: album.artistId ?: continue
        if (!seen.add(id)) continue
        result += byId[id] ?: Artist(id, reference?.name ?: album.artistName)
        if (result.size == limit) break
    }
    return result
}

/** Album sorts that carry sections (spec §2 "Sections per sort"). */
enum class AlbumSectionKind { TITLE, ADDED, RELEASED, PLAYED, FREQUENT, RATED, NONE }

object AlbumSectioner {
    /** The key of the Title sort and of its section letters: the server's sort name, else the name without a leading article. */
    fun titleKey(album: Album, ignoredArticles: List<String> = DefaultIgnoredArticles): String =
        artistSortKey(album.name, album.sortName, ignoredArticles)

    /**
     * [albums] in Title order: "#" first, then A-Z by the folded [titleKey]. The list the Title sections are cut from,
     * so a section is never split whatever order the server returned (Navidrome ignores articles only for some sorts).
     */
    fun titleOrder(albums: List<Album>, locale: Locale = Locale.getDefault(),
        ignoredArticles: List<String> = DefaultIgnoredArticles): List<Album> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
        class Keyed(val album: Album, val letter: Char, val key: String)
        return albums.map { val k = titleKey(it, ignoredArticles); Keyed(it, sectionLetter(k), AlphaBuckets.key(k)) }
            .sortedWith(compareBy<Keyed>({ rank(it.letter) }).thenComparator { a, b -> collator.compare(a.key, b.key) }
                .thenBy { it.album.name }.thenBy { it.album.id })
            .map { it.album }
    }

    /** Sections for [albums] in list order; none below [AlbumSectionThreshold] albums or for Random. Empty sections never appear. */
    fun sections(kind: AlbumSectionKind, albums: List<Album>, now: ZonedDateTime = ZonedDateTime.now(),
        locale: Locale = Locale.getDefault()): List<LibrarySection> {
        if (albums.size < AlbumSectionThreshold || kind == AlbumSectionKind.NONE) return emptyList()
        val labels: List<Label> = when (kind) {
            AlbumSectionKind.TITLE -> albums.map { Label(sectionLetter(titleKey(it)).toString()) }
            AlbumSectionKind.ADDED -> albums.map { dateLabel(parse(it.created, now.zone), now, locale, days = false) }
            AlbumSectionKind.PLAYED -> albums.map { dateLabel(parse(it.played, now.zone), now, locale, days = true) }
            AlbumSectionKind.RELEASED -> albums.map { yearLabel(it.releaseYear, now.year) }
            AlbumSectionKind.FREQUENT -> albums.map { playLabel(it.playCount ?: 0) }
            AlbumSectionKind.RATED -> albums.map { (it.userRating ?: 0).let { stars ->
                if (stars in 1..5) Label("$stars", stars = stars) else Label("Unrated") } }
            AlbumSectionKind.NONE -> return emptyList()
        }
        val sections = mutableListOf<LibrarySection>()
        var start = 0
        for (i in 1..labels.size) {
            if (i == labels.size || labels[i] != labels[start]) {
                val l = labels[start]
                sections += LibrarySection(l.text, i - start, start, l.stars)
                start = i
            }
        }
        return sections
    }

    private data class Label(val text: String, val stars: Int? = null)

    internal fun parseDate(value: String?, zone: ZoneId): ZonedDateTime? = parse(value, zone)

    private fun parse(value: String?, zone: ZoneId): ZonedDateTime? {
        if (value.isNullOrBlank()) return null
        return try { OffsetDateTime.parse(value).atZoneSameInstant(zone) } catch (_: Exception) {
            try { Instant.parse(value).atZone(zone) } catch (_: Exception) {
                try { LocalDate.parse(value.take(10)).atStartOfDay(zone) } catch (_: Exception) { null }
            }
        }
    }

    private fun month(date: ZonedDateTime, locale: Locale, style: TextStyle) = date.month.getDisplayName(style, locale)

    /**
     * Recently Added: This week (since Monday 00:00) · Earlier in {Month} · earlier months of this year · years.
     * Recently Played adds Today and Yesterday in front.
     */
    private fun dateLabel(date: ZonedDateTime?, now: ZonedDateTime, locale: Locale, days: Boolean): Label {
        if (date == null) return Label("Unknown date")
        val today = now.toLocalDate()
        val day = date.toLocalDate()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return when {
            days && !day.isBefore(today) -> Label("Today")
            days && day == today.minusDays(1) -> Label("Yesterday")
            !day.isBefore(monday) -> Label("This week")
            day.year == today.year && day.month == today.month -> Label("Earlier in ${month(date, locale, TextStyle.FULL)}")
            day.year == today.year -> Label(month(date, locale, TextStyle.FULL))
            else -> Label("${day.year}")
        }
    }

    private fun yearLabel(year: Int?, current: Int): Label = when {
        year == null -> Label("Unknown year")
        year >= current - 9 -> Label("$year")
        else -> Label("${year / 10 * 10}s")
    }

    private fun playLabel(count: Int): Label = when {
        count >= 100 -> Label("100+ plays")
        count >= 50 -> Label("50–99 plays")
        count >= 20 -> Label("20–49 plays")
        count >= 10 -> Label("10–19 plays")
        count >= 1 -> Label("1–9 plays")
        else -> Label("Not played")
    }
}

/**
 * The order and membership of each album list type, derived from one complete library list (so a sort change within the
 * freshness window needs no request). Follows Navidrome's getAlbumList2: Recently Played lists albums with a play date,
 * Most Played those with plays, Top Rated those with a rating.
 */
object AlbumOrder {
    /** True for list types that contain the whole library (a complete one can serve every other type). */
    fun isWholeLibrary(type: AlbumListType) = type == AlbumListType.ALPHABETICAL || type == AlbumListType.NEWEST

    /**
     * The sort keys (dates, lowercase names) are computed once per album, not per comparison: a comparator that parses its
     * dates took ~4 s for 1,800 albums on the emulator (the sort switch showed the old list that long).
     */
    fun derive(type: AlbumListType, library: List<Album>, locale: Locale = Locale.getDefault()): List<Album> = when (type) {
        AlbumListType.ALPHABETICAL -> AlbumSectioner.titleOrder(library, locale)
        AlbumListType.NEWEST -> library.map { Keyed(it, instant(it.created)) }
            .sortedWith(compareByDescending<Keyed<Instant?>, Instant?>(nullsFirst()) { it.key }.thenBy { it.album.id }).map { it.album }
        AlbumListType.RECENT_RELEASES -> library.map { Keyed(it, it.releaseYear to it.name.lowercase()) }
            .sortedWith(compareByDescending<Keyed<Pair<Int?, String>>, Int?>(nullsFirst()) { it.key.first }
                .thenBy { it.key.second }.thenBy { it.album.id }).map { it.album }
        AlbumListType.RECENTLY_PLAYED -> library.mapNotNull { a -> instant(a.played)?.let { Keyed(a, it) } }
            .sortedWith(compareByDescending<Keyed<Instant>> { it.key }.thenBy { it.album.id }).map { it.album }
        AlbumListType.MOST_PLAYED -> library.filter { (it.playCount ?: 0) > 0 }.map { Keyed(it, it.name.lowercase()) }
            .sortedWith(compareByDescending<Keyed<String>> { it.album.playCount ?: 0 }.thenBy { it.key }.thenBy { it.album.id }).map { it.album }
        AlbumListType.TOP_RATED -> library.filter { (it.userRating ?: 0) > 0 }.map { Keyed(it, it.name.lowercase()) }
            .sortedWith(compareByDescending<Keyed<String>> { it.album.userRating ?: 0 }.thenBy { it.key }.thenBy { it.album.id }).map { it.album }
        AlbumListType.RANDOM -> library
    }

    private class Keyed<K>(val album: Album, val key: K)

    private fun instant(value: String?): Instant? = AlbumSectioner.parseDate(value, ZoneId.of("UTC"))?.toInstant()
}
