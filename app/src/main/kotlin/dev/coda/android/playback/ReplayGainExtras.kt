package dev.coda.android.playback

import android.os.Bundle
import dev.coda.core.playback.ReplayGainMetadata

/** ReplayGain tags travel with the MediaItem so the service can resolve gain per occurrence. */
internal fun Bundle.putReplayGain(metadata: ReplayGainMetadata?) {
    metadata?.trackGain?.let { putDouble("rgTrackGain", it) }
    metadata?.albumGain?.let { putDouble("rgAlbumGain", it) }
    metadata?.trackPeak?.let { putDouble("rgTrackPeak", it) }
    metadata?.albumPeak?.let { putDouble("rgAlbumPeak", it) }
}

internal fun Bundle.readReplayGain(): ReplayGainMetadata? {
    fun value(key: String) = if (containsKey(key)) getDouble(key) else null
    val result = ReplayGainMetadata(value("rgTrackGain"), value("rgAlbumGain"), value("rgTrackPeak"), value("rgAlbumPeak"))
    return result.takeIf { it != ReplayGainMetadata() }
}
