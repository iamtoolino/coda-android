package dev.coda.android.playback

import dev.coda.android.artwork.CoverArt
import dev.coda.android.artwork.CoverGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Size selection for the cover bytes in the session metadata. The request asks the server for a
 * downscaled cover and is cached under its own key (cover ID + size, see `CoverArtKeyer`), separate from the
 * 472 px UI covers. A server may ignore `size` and return the original: then it is shrunk locally, so a large
 * original never leaves the session without a cover.
 */
object SessionCover {
    const val SIZE = CoverGuard.SESSION_MAX_EDGE
    /** Longest edges tried, in order, when the fetched bytes exceed [CurrentArtworkData.MAX_BYTES]. */
    val SHRINK_EDGES = listOf(SIZE, 768, 512, 384, 256)

    fun art(coverArtId: String) = CoverArt(coverArtId, SIZE)

    /** [fetch] returns the (server-sized) bytes; [shrink] re-encodes them to at most `edge` px or returns null; [edgeOf] reads the longest edge. */
    suspend fun select(
        coverArtId: String,
        fetch: suspend (CoverArt) -> ByteArray,
        edgeOf: (ByteArray) -> Int? = CoverGuard::longestEdge,
        shrink: (ByteArray, Int) -> ByteArray? = CoverGuard::shrinkJpeg,
    ): ByteArray? {
        val bytes = fetch(art(coverArtId))
        // Decode and re-encode off the caller's thread: the player loads covers from Main (shrinking
        // 3000 px originals there skipped 50-100 frames per track change).
        return withContext(Dispatchers.Default) { CoverGuard.fit(bytes, SIZE, CurrentArtworkData.MAX_BYTES, shrink, edgeOf, SHRINK_EDGES) }
    }

    fun shrinkJpeg(bytes: ByteArray, edge: Int): ByteArray? = CoverGuard.shrinkJpeg(bytes, edge)
}
