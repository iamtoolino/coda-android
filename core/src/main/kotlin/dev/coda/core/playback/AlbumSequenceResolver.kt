package dev.coda.core.playback

data class AlbumSequenceContext(
    val albumId: String?,
    val continuesPrevious: Boolean,
    val continuesNext: Boolean,
    val isExplicitContinuation: Boolean = false,
) {
    val isAlbumSequence: Boolean get() = continuesPrevious || continuesNext
}

data class AlbumSequenceGroup(val range: IntRange, val albumId: String?) {
    val isAlbumSequence: Boolean get() = albumId != null && range.count() > 1
}

/** Port of AlbumSequenceResolver.swift. Queue ends never become neighbours. */
object AlbumSequenceResolver {
    fun context(queue: List<QueueEntry>, at: Int): AlbumSequenceContext? {
        val entry = queue.getOrNull(at) ?: return null
        return context(entry.albumId, queue.getOrNull(at - 1)?.albumId,
            queue.getOrNull(at + 1)?.albumId, entry.albumContinuationId)
    }

    fun context(
        albumId: String?,
        previousAlbumId: String?,
        nextAlbumId: String?,
        continuationAlbumId: String? = null,
    ): AlbumSequenceContext {
        val identity = normalizedAlbumId(albumId)
        return AlbumSequenceContext(
            identity,
            identity != null && identity == normalizedAlbumId(previousAlbumId),
            identity != null && identity == normalizedAlbumId(nextAlbumId),
            identity != null && identity == normalizedAlbumId(continuationAlbumId),
        )
    }

    fun groups(albumIds: List<String?>): List<AlbumSequenceGroup> {
        if (albumIds.isEmpty()) return emptyList()
        val identities = albumIds.map(::normalizedAlbumId)
        val result = mutableListOf<AlbumSequenceGroup>()
        var start = 0
        for (index in 1 until identities.size) {
            if (context(identities[index], identities[index - 1], null).continuesPrevious) continue
            result += AlbumSequenceGroup(start until index, identities[start])
            start = index
        }
        result += AlbumSequenceGroup(start until identities.size, identities[start])
        return result
    }

    fun normalizedAlbumId(id: String?): String? =
        id?.trim(::isFoundationWhitespaceAndNewline)?.takeIf { it.isNotEmpty() }

    // Foundation includes NEL and zero-width space, and preserves U+001C...U+001F.
    // Kotlin/JVM's default trim differs; keep the Swift identity semantics (golden-tested).
    private fun isFoundationWhitespaceAndNewline(char: Char): Boolean = when (char.code) {
        in 0x0009..0x000D, 0x0020, 0x0085, 0x00A0, 0x1680,
        in 0x2000..0x200B, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> false
    }
}
