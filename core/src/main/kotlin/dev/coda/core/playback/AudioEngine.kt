package dev.coda.core.playback

import java.util.UUID

/** Control calls and listener delivery are serialized on the application's main thread. */
interface AudioEngine {
    val name: String
    fun load(current: EngineItem, next: EngineItem?, startPositionMs: Long, playWhenReady: Boolean)
    /**
     * Replaces the successor of the engine's actual current item. The engine may already have
     * advanced while its Advanced callback is queued; this call must not reload that current item.
     */
    fun updateNext(next: EngineItem?)
    fun setGain(gainDb: Double)
    fun play()
    /** May fade out first; the position stops where the fade ends. */
    fun pause()
    /** Pause without a fade (headphones unplugged). */
    fun pauseNow() = pause()
    fun seekTo(positionMs: Long)
    /**
     * Whether a seek inside an uncached Opus transcode can land on its target (the server is known to start a
     * transcode at `timeOffset`). Cheap and non-blocking; false while unknown.
     */
    fun seeksInsideTranscodes(): Boolean = false
    fun stop()
    /** Cheap and safe to sample from any thread. */
    fun positionMs(): Long
    fun release()
    /**
     * Continues the current item from [source] (its whole file, now in the cache) at [positionMs] instead of from
     * the stream it was opened with. The next item and the occurrence identity are unchanged.
     */
    fun replaceCurrentSource(source: TrackSource, positionMs: Long) {}
    /** Display snapshot for the Now Playing format chip and device tests; main thread only. Null when nothing is loaded. */
    fun details(): EngineDetails? = null
    var listener: Listener?

    fun interface Listener {
        fun onEvent(event: EngineEvent)
    }
}

@JvmInline
value class OccurrenceId(val value: UUID = UUID.randomUUID())

enum class StreamFormat { RAW, OPUS_128 }

sealed interface TrackSource {
    data class CachedFile(val path: String) : TrackSource {
        /**
         * Which cache entry the file is. Cache files are named by their TrackKey (`.opus` = server transcode at
         * 128 kbps, `.raw` = original), and a transcoded Ogg Opus file reports no bitrate when played back.
         */
        val transcodedFormat: StreamFormat? get() = if (path.endsWith(".opus")) StreamFormat.OPUS_128 else null
    }
    /**
     * The adapter constructs the authenticated URL only when opening the source. [timeOffsetSeconds] is set
     * only by the engine for the stream it opens ([TranscodeOffset]); queue items, cache requests and events carry 0.
     */
    data class Remote(val songId: String, val format: StreamFormat, val timeOffsetSeconds: Int = 0) : TrackSource
}

/** The server's Opus 128 transcode: streamed, or a cached `.opus` copy of it. Single rule for the chip, the engine's stream-failure handling and the session extra. */
val TrackSource.transcodeFormat: StreamFormat?
    get() = when (this) {
        is TrackSource.Remote -> format.takeIf { it == StreamFormat.OPUS_128 }
        is TrackSource.CachedFile -> transcodedFormat
    }

/** Resolve cache availability and network format when an occurrence is prepared. No blocking I/O. */
fun interface SourceResolver {
    fun resolve(entry: QueueEntry): TrackSource
}

data class EngineItem(val occurrence: OccurrenceId, val source: TrackSource, val gainDb: Double)

sealed interface PlaybackFailure {
    data object Network : PlaybackFailure
    data class Http(val status: Int) : PlaybackFailure
    data object Unauthorized : PlaybackFailure
    data object NotFound : PlaybackFailure
    data object Decode : PlaybackFailure
    // Do not carry arbitrary engine messages, which may contain URLs or credentials.
    data object Unknown : PlaybackFailure
}

sealed interface EngineEvent {
    /** [durationMs]: the decoder-side length of [occurrence] when the engine knows one (an estimate for some MP3s; see [resolveDisplayDurationMs]). */
    data class State(val occurrence: OccurrenceId, val isPlaying: Boolean, val isBuffering: Boolean, val durationMs: Long? = null) : EngineEvent
    data class Advanced(val to: OccurrenceId) : EngineEvent
    data class Ended(val last: OccurrenceId) : EngineEvent
    data class Failed(val occurrence: OccurrenceId, val failure: PlaybackFailure) : EngineEvent
    /** The adapter accepted a different source for this occurrence (Opus rejected → original). */
    data class SourceFallback(val occurrence: OccurrenceId, val source: TrackSource) : EngineEvent
    /**
     * Loading this occurrence's remote stream failed and the engine is about to re-open it. A transcode
     * cannot resume at a byte offset, so the controller continues from a complete cached copy when there is one.
     */
    data class StreamInterrupted(val occurrence: OccurrenceId) : EngineEvent
}

/** What the engine knows about the item it is playing now, for display only. */
data class EngineDetails(
    val containerMime: String?, val codecMime: String?, val bitrate: Int?, val sampleRate: Int?, val channels: Int?,
    val pcmEncodingBits: Int?, val decoderName: String?,
    val encoderDelayFrames: Int, val encoderPaddingFrames: Int, val opusPreSkipFrames: Int?,
    /** Exact decoded length the trimmer enforces, when the container reports one. */
    val trimEndFrames: Long?,
    val gaplessNextPrepared: Boolean, val bufferedAheadMs: Long,
)
