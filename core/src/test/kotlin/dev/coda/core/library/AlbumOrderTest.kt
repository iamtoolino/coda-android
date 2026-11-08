package dev.coda.core.library

import dev.coda.core.subsonic.Album
import dev.coda.core.subsonic.AlbumListType
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/** The client-side sorts (DS-9 ds-9b): keys are computed once per album, and the order is what the per-comparison version gave. */
class AlbumOrderTest {
    private val now = Instant.parse("2026-10-20T12:00:00Z")
    private fun library(n: Int, seed: Int = 3): List<Album> {
        val random = Random(seed)
        return (0 until n).map { i ->
            Album("a$i", listOf("Zed", "alpha", "Beta", "gamma")[random.nextInt(4)] + " ${random.nextInt(30)}",
                created = when (random.nextInt(6)) { 0 -> null; 1 -> "garbage"; 2 -> now.minus(random.nextLong(40), ChronoUnit.DAYS).toString()
                    3 -> OffsetDateTime.parse("2026-09-01T10:00:00+02:00").plusDays(random.nextLong(30)).toString()
                    else -> now.minus(random.nextLong(900_000), ChronoUnit.MINUTES).toString() },
                year = if (random.nextInt(5) == 0) null else 1990 + random.nextInt(35),
                played = if (random.nextBoolean()) now.minus(random.nextLong(5000), ChronoUnit.HOURS).toString() else null,
                playCount = if (random.nextInt(3) == 0) null else random.nextInt(8), userRating = if (random.nextBoolean()) random.nextInt(6) else null)
        }
    }
    private fun instant(value: String?): Instant? = AlbumSectioner.parseDate(value, java.time.ZoneId.of("UTC"))?.toInstant()

    @Test fun derivedOrdersMatchTheReferenceComparators() {
        val albums = library(600)
        val reference = mapOf(
            AlbumListType.NEWEST to albums.sortedWith(compareByDescending<Album, Instant?>(nullsFirst()) { instant(it.created) }.thenBy { it.id }),
            AlbumListType.RECENT_RELEASES to albums.sortedWith(compareByDescending<Album, Int?>(nullsFirst()) { it.releaseYear }
                .thenBy { it.name.lowercase() }.thenBy { it.id }),
            AlbumListType.RECENTLY_PLAYED to albums.filter { instant(it.played) != null }
                .sortedWith(compareByDescending<Album, Instant?>(nullsFirst()) { instant(it.played) }.thenBy { it.id }),
            AlbumListType.MOST_PLAYED to albums.filter { (it.playCount ?: 0) > 0 }
                .sortedWith(compareByDescending<Album> { it.playCount ?: 0 }.thenBy { it.name.lowercase() }.thenBy { it.id }),
            AlbumListType.TOP_RATED to albums.filter { (it.userRating ?: 0) > 0 }
                .sortedWith(compareByDescending<Album> { it.userRating ?: 0 }.thenBy { it.name.lowercase() }.thenBy { it.id }),
        )
        reference.forEach { (type, expected) ->
            assertEquals(type.name, expected.map { it.id }, AlbumOrder.derive(type, albums).map { it.id })
            assertTrue(type.name, expected.isNotEmpty())
        }
        assertEquals(albums, AlbumOrder.derive(AlbumListType.RANDOM, albums))
    }
}
