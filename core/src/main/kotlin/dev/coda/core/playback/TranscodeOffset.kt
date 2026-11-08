package dev.coda.core.playback

/**
 * A server transcode cannot seek (chunked, no length, Range ignored), so a load at a position (a takeover, a
 * cold-start resume, a reconnect) used to play from 0. A server that advertises the OpenSubsonic [EXTENSION] starts the
 * transcode at `timeOffset` instead; measured on Navidrome, sample-exact at whole seconds (fractions are ignored).
 * The engine asks for the whole second at or before the position and decodes the rest up to it as decode-only.
 */
object TranscodeOffset {
    const val EXTENSION = "transcodeOffset"

    /** Whole seconds to ask the transcode of [source] to start at for a load at [startPositionMs]; 0 = from the start. */
    fun requestSeconds(source: TrackSource, startPositionMs: Long): Int =
        if (source is TrackSource.Remote && source.format == StreamFormat.OPUS_128 && source.timeOffsetSeconds == 0 &&
            startPositionMs >= 1_000) (startPositionMs / 1_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0

    /** The offset actually requested: only from a server that advertises [EXTENSION]; otherwise from 0, as before. */
    fun appliedSeconds(requestedSeconds: Int, serverSupportsOffset: Boolean): Int =
        if (serverSupportsOffset) requestedSeconds.coerceAtLeast(0) else 0

    fun supportedBy(extensions: Set<String>): Boolean = EXTENSION in extensions
}
