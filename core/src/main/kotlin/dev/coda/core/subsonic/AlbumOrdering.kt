package dev.coda.core.subsonic

import java.text.Normalizer

object AlbumOrdering {
    /** Swift's tuple comparison; complete ties preserve the server's order. */
    fun canonical(songs: List<Song>): List<Song> = songs.sortedWith(
        compareBy<Song> { it.discNumber ?: 1 }
            .thenBy { it.track ?: Int.MAX_VALUE }
            .thenComparator { left, right ->
                compareSwiftStrings(left.title.lowercase(), right.title.lowercase())
            },
    )

    private fun compareSwiftStrings(left: String, right: String): Int {
        // Swift compares canonically equivalent strings equally, then orders Unicode scalars.
        val a = Normalizer.normalize(left, Normalizer.Form.NFC)
        val b = Normalizer.normalize(right, Normalizer.Form.NFC)
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)
            if (x != y) return x.compareTo(y)
            i += Character.charCount(x)
            j += Character.charCount(y)
        }
        return (a.length - i).compareTo(b.length - j)
    }
}

fun resolvedAlbumTotalCount(
    headerValue: String?,
    offset: Int,
    returnedCount: Int,
    requestedSize: Int,
): Int? {
    val loadedCount = maxOf(0, offset) + maxOf(0, returnedCount)
    if (returnedCount < requestedSize) return loadedCount
    return headerValue?.trim()?.toIntOrNull()?.takeIf { it >= loadedCount }
}
