package dev.coda.core.library

import java.text.Normalizer

/**
 * A-Z buckets for long Albums/Artists lists, so no Android Auto list exceeds the host's item limit.
 *
 * Sort/bucket key: the name with diacritics removed (NFD, combining marks dropped, a few non-decomposing
 * letters mapped), case-folded. This matches the macOS app's case-insensitive name order; a leading
 * "The " is NOT ignored (Swift's `localizedCaseInsensitiveCompare` does not). The bucket letter is the first
 * character of that key when it is A-Z; digits, symbols and non-Latin scripts go to "#", which sorts first.
 */
object AlphaBuckets {
    const val OTHER = '#'
    const val DEFAULT_LIMIT = 100
    const val MIN_LIMIT = 10

    private val extra = mapOf('ø' to "o", 'æ' to "ae", 'œ' to "oe", 'ł' to "l", 'đ' to "d", 'ð' to "d", 'þ' to "th", 'ß' to "ss", 'ı' to "i")

    fun key(name: String): String {
        val folded = Normalizer.normalize(name.trim().lowercase(), Normalizer.Form.NFD)
        return buildString {
            for (c in folded) when {
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> {}
                else -> append(extra[c] ?: c.toString())
            }
        }
    }

    fun letter(name: String): Char = key(name).firstOrNull()?.takeIf { it in 'a'..'z' }?.uppercaseChar() ?: OTHER

    /** Sort order of letters: "#" first, then A-Z. */
    private fun rank(letter: Char) = if (letter == OTHER) 0 else letter - 'A' + 1

    /** The fold key and letter are computed once per item, not inside every comparison; same order as before. */
    fun <T> sorted(items: List<T>, name: (T) -> String, id: (T) -> String): List<T> {
        class Keyed(val item: T, val name: String, val key: String) {
            val rank = rank(key.firstOrNull()?.takeIf { it in 'a'..'z' }?.uppercaseChar() ?: OTHER)
            val id = id(item)
        }
        return items.map { val n = name(it); Keyed(it, n, key(n)) }
            .sortedWith(compareBy<Keyed>({ it.rank }, { it.key }, { it.name }, { it.id })).map { it.item }
    }

    /** A folder for one populated letter, holding [count] items. */
    data class Bucket(val letter: Char, val count: Int) {
        val id: String get() = letter.toString()
        val title: String get() = letter.toString()
    }

    /** One folder per populated letter, including oversized letters (browsed in numbered parts). */
    fun partition(counts: Map<Char, Int>): List<Bucket> =
        counts.filterValues { it > 0 }.keys.sortedBy(::rank).map { Bucket(it, counts.getValue(it)) }

    /** Parses a single-letter bucket id; ranges and malformed ids are rejected. */
    fun parse(id: String): Char? = id.singleOrNull()?.takeIf { it == OTHER || it in 'A'..'Z' }

    fun <T> inLetter(sorted: List<T>, name: (T) -> String, bucketLetter: Char): List<T> =
        sorted.filter { letter(name(it)) == bucketLetter }

    fun chunkTitle(letter: Char, chunk: Int, size: Int, total: Int): String =
        "$letter ${chunk * size + 1}–${minOf(total, (chunk + 1) * size)}"
}
