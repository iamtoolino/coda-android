package dev.coda.core.library

import dev.coda.core.subsonic.Playlist
import java.text.Collator
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** Sorts of the Playlists tab (PO-approved labels). Sorting is local: `getPlaylists` returns everything at once. */
enum class PlaylistSort(val label: String) {
    NAME("Name"), CHANGED("Recently changed"), CREATED("Recently created"), TRACKS("Most tracks"), LONGEST("Longest")
}

/** Order, letter sections and the metadata line of the Playlists tab. */
object PlaylistOrder {
    /**
     * Name key: like the Albums Title sort, a leading article ("The Mix") does not decide the letter. It uses the shown name
     * ("[Auto] Recently Added" is shown, sorted and sectioned as "Recently Added").
     */
    private fun nameKey(playlist: Playlist) = artistSortKey(PlaylistName.parse(playlist.name).display, null, DefaultIgnoredArticles)

    /**
     * [playlists] in [sort] order. Name is A-Z with "#" first (the Albums Title order); the others are descending by their
     * value, a playlist without one last, and every tie falls back to Name, so the order never depends on the server's.
     */
    fun sort(sort: PlaylistSort, playlists: List<Playlist>, locale: Locale = Locale.getDefault()): List<Playlist> {
        val byName = nameOrder(playlists, locale)
        // sortedBy is stable: equal values keep the Name order above.
        return when (sort) {
            PlaylistSort.NAME -> byName
            PlaylistSort.CHANGED -> newestFirst(byName) { it.changed }
            PlaylistSort.CREATED -> newestFirst(byName) { it.created }
            PlaylistSort.TRACKS -> byName.sortedByDescending { it.songCount ?: 0 }
            PlaylistSort.LONGEST -> byName.sortedByDescending { it.duration ?: 0 }
        }
    }

    /** Each date is parsed once, not once per comparison. */
    private fun newestFirst(playlists: List<Playlist>, date: (Playlist) -> String?): List<Playlist> =
        playlists.map { it to instant(date(it)) }.sortedByDescending { it.second }.map { it.first }

    private fun nameOrder(playlists: List<Playlist>, locale: Locale): List<Playlist> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
        class Keyed(val playlist: Playlist, val letter: Char, val key: String)
        return playlists.map { val k = nameKey(it); Keyed(it, sectionLetter(k), AlphaBuckets.key(k)) }
            .sortedWith(compareBy<Keyed>({ rank(it.letter) }).thenComparator { a, b -> collator.compare(a.key, b.key) }
                .thenBy { it.playlist.name }.thenBy { it.playlist.id })
            .map { it.playlist }
    }

    /** Letter sections of a Name-sorted list, from [AlbumSectionThreshold] playlists up like Albums; none for any other sort. */
    fun sections(sort: PlaylistSort, sorted: List<Playlist>): List<LibrarySection> =
        if (sort != PlaylistSort.NAME || sorted.size < AlbumSectionThreshold) emptyList()
        else runs(sorted.map { sectionLetter(nameKey(it)).toString() })

    /**
     * "24 tracks · 1 h 32 min", plus "· 3 days ago" for Recently changed (its `changed` time) and
     * for Recently created (its `created` time), without an "updated"/"created" word. A part the server did not send is left out.
     */
    /** [tracks]: the count's own words in place of "N tracks" (offline: "21 of 23 tracks"). */
    fun metadata(playlist: Playlist, sort: PlaylistSort, now: Instant = Instant.now(), locale: Locale = Locale.getDefault(),
        tracks: String? = null): String {
        val timeStamp = when (sort) {
            PlaylistSort.CHANGED -> instant(playlist.changed)?.let { relativeTime(it, now) }
            PlaylistSort.CREATED -> instant(playlist.created)?.let { relativeTime(it, now) }
            else -> null
        }
        return listOfNotNull(tracks ?: tracks(playlist.songCount ?: 0, locale), duration(playlist.duration), timeStamp).joinToString(" · ")
    }

    fun tracks(count: Int, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getIntegerInstance(locale).format(count) + if (count == 1) " track" else " tracks"

    /** "1 h 32 min", "45 min", "2 h"; rounded to the minute (as the playlist page does), a second or more is at least a minute. */
    fun duration(seconds: Int?): String? {
        if (seconds == null || seconds <= 0) return null
        val minutes = maxOf(1, (seconds + 30) / 60)
        return when {
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0 -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }

    /** "just now", "5 min ago", "3 h ago", "yesterday", "4 days ago", "2 weeks ago", "3 months ago", "1 year ago". */
    fun relativeTime(then: Instant, now: Instant): String {
        val elapsed = Duration.between(then, now)
        val days = elapsed.toDays()
        return when {
            elapsed.toMinutes() < 1 -> "just now"
            elapsed.toHours() < 1 -> "${elapsed.toMinutes()} min ago"
            days < 1 -> "${elapsed.toHours()} h ago"
            days == 1L -> "yesterday"
            days < 7 -> "$days days ago"
            days < 30 -> plural(days / 7, "week")
            days < 365 -> plural(minOf(days / 30, 11), "month")
            else -> plural(days / 365, "year")
        }
    }

    private fun plural(n: Long, unit: String) = "$n $unit${if (n == 1L) "" else "s"} ago"

    private fun instant(value: String?): Instant? = AlbumSectioner.parseDate(value, ZoneOffset.UTC)?.toInstant()
}
