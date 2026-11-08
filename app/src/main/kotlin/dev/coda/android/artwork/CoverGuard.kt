package dev.coda.android.artwork

/**
 * Size guard for every cover that leaves the app towards Android Auto or the system UI: bitmap bytes in
 * session metadata and the content-provider files behind browse artwork. A server may ignore `size` and return
 * the original (a 3000 px PNG, a 5 MB JPEG); a host decoding that, or a Binder parcel carrying it, can crash
 * the head unit. Covers above the edge or byte limit are re-encoded smaller, never dropped; only undecodable
 * data yields null.
 */
object CoverGuard {
    /** Provider edge ceiling; browse lists request 288 px. Session bytes have a separate ceiling. */
    const val MAX_EDGE = 512
    const val SESSION_MAX_EDGE = 1024
    /** Browse files stay far below Binder-sized data; a 288 px JPEG is about 30 KiB. */
    const val BROWSE_MAX_BYTES = 256 * 1024
    const val MIN_EDGE = 64

    /** Edges tried, largest first, for a cover that must fit [maxEdge]. */
    fun edges(maxEdge: Int): List<Int> =
        listOf(maxEdge, maxEdge * 3 / 4, maxEdge / 2, maxEdge / 4, MIN_EDGE).filter { it in MIN_EDGE..maxEdge }.distinct()

    /** [edgeOf] gives the longest edge in px (null: not an image), [shrink] re-encodes to at most that edge (null: failed). */
    fun fit(
        bytes: ByteArray,
        maxEdge: Int,
        maxBytes: Int,
        shrink: (ByteArray, Int) -> ByteArray? = ::shrinkJpeg,
        edgeOf: (ByteArray) -> Int? = ::longestEdge,
        edges: List<Int> = edges(maxEdge),
    ): ByteArray? {
        val longest = edgeOf(bytes)?.takeIf { it > 0 } ?: return null
        if (longest <= maxEdge && bytes.size <= maxBytes) return bytes
        for (edge in edges) {
            val small = try { shrink(bytes, edge) } catch (_: OutOfMemoryError) { null }
            if (small != null && small.size in 1..maxBytes) return small
        }
        return null
    }

    fun longestEdge(bytes: ByteArray): Int? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return maxOf(bounds.outWidth, bounds.outHeight).takeIf { it > 0 }
    }

    /** Decode with sampling and re-encode as JPEG, longest edge at most [edge]. */
    fun shrinkJpeg(bytes: ByteArray, edge: Int): ByteArray? {
        val longest = longestEdge(bytes) ?: return null
        var sample = 1
        while (longest / (sample * 2) >= edge) sample *= 2
        val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scaled = if (maxOf(decoded.width, decoded.height) > edge) {
            val ratio = edge.toFloat() / maxOf(decoded.width, decoded.height)
            android.graphics.Bitmap.createScaledBitmap(decoded, maxOf(1, (decoded.width * ratio).toInt()), maxOf(1, (decoded.height * ratio).toInt()), true)
        } else decoded
        return java.io.ByteArrayOutputStream().use { out ->
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray()
        }
    }
}
