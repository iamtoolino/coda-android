package dev.coda.android.cache

import dev.coda.core.playback.PlaybackFailure

/** Safe transport classification, without upstream text or authenticated request locations. */
class TrackReadException(val failure: PlaybackFailure) : java.io.IOException("Track request failed")

internal fun streamBodyFailure(body: String): PlaybackFailure = when (
    Regex("(?:\\\"code\\\"\\s*:\\s*|code=[\\\"'])(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
) {
    40, 41 -> PlaybackFailure.Unauthorized
    70 -> PlaybackFailure.NotFound
    else -> PlaybackFailure.Decode
}
