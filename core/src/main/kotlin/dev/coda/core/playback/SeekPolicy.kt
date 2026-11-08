package dev.coda.core.playback

/**
 * Which tracks the seek bar may move. A server transcode (Opus 128) has no length and no byte ranges,
 * so a seek in it would restart from 0. Such a track becomes seekable once its whole file is in the track cache.
 * Local files and the original (Range-capable) stream are always seekable. A transcode is also seekable when the
 * server starts transcodes at `timeOffset` ([transcodeSeeks], see [TranscodeSeek]).
 */
object SeekPolicy {
    fun isSeekable(source: TrackSource?, completeInCache: Boolean, transcodeSeeks: Boolean = false): Boolean = when (source) {
        null, is TrackSource.CachedFile -> true
        is TrackSource.Remote -> source.format == StreamFormat.RAW || completeInCache || transcodeSeeks
    }
}
