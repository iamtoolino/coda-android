package dev.coda.core.cache

import dev.coda.core.connectivity.NetworkState
import dev.coda.core.playback.StreamFormat

/** numbers. None of them is a user setting; the lifetimes and the line are CA-3 settings ([CachePolicy]). */
object CacheRules {
    const val DAY_MS = 86_400_000L
    /**
     * Lossy originals at or below MP3's maximum are always fetched as they are: on Wi-Fi and on mobile or metered, in
     * for play, read-ahead and offline (no second lossy encode).
     */
    const val PASS_THROUGH_MAX_KBPS = 320
    /**
     * Suffixes that are clearly lossless: a low bitrate (FLAC at 256 kbit/s) does not make them lossy, so they never get
     * the always-original exemption. `m4a`/`mp4` stay lossy: they are AAC far more often, and the suffix can't tell ALAC apart.
     */
    val LOSSLESS_SUFFIXES = setOf("flac", "alac", "wav", "wave", "aiff", "aif", "aifc", "ape", "wv", "dsf", "dff", "tta")
    /** Navidrome answers an Opus 128 request for an Opus original of at most this bitrate with the original itself. */
    const val OPUS_SERVER_PASS_THROUGH_MAX_KBPS = 128
    /** Opus 128 transcodes have no Content-Length: 16 KB/s plus a small container allowance. */
    const val OPUS_BYTES_PER_SECOND = 16_000L
    const val UNKNOWN_DURATION_SECONDS = 240
    /** Unknown original bitrate: CD PCM, an upper bound for CD-quality FLAC. */
    const val UNKNOWN_ORIGINAL_KBPS = 1_411
    const val WAVEFORM_AGE_LIMIT_MS = 365 * DAY_MS
    const val WAVEFORM_CAP = 20_000
}

/** What the server says about the original: enough to pick a format and estimate a size before downloading. */
@kotlinx.serialization.Serializable
data class SongFormat(
    val suffix: String? = null,
    val bitRateKbps: Int? = null,
    val durationSeconds: Int? = null,
    val sizeBytes: Long? = null,
) {
    private val knownKbps get() = bitRateKbps?.takeIf { it > 0 }
    /** Unknown (or 0) bitrate counts as above the cut: an unknown file is treated like a lossless one. */
    val atMost320 get() = knownKbps?.let { it <= CacheRules.PASS_THROUGH_MAX_KBPS } == true
    /** Clearly lossless by suffix ([CacheRules.LOSSLESS_SUFFIXES]); an unknown suffix is not "clearly" lossless. */
    val isLossless get() = suffix?.trim()?.removePrefix(".")?.lowercase() in CacheRules.LOSSLESS_SUFFIXES
    /** A lossy original of at most 320 kbit/s: always fetched as it is. */
    val isLossyUpTo320 get() = atMost320 && !isLossless
    /** Only for [ArrivalDetector]: what Navidrome sends untouched for an Opus 128 request. Not a fetch rule. */
    internal val opusPassesThroughServer get() = knownKbps?.let { it <= CacheRules.OPUS_SERVER_PASS_THROUGH_MAX_KBPS } == true
    private val seconds get() = (durationSeconds ?: CacheRules.UNKNOWN_DURATION_SECONDS).coerceAtLeast(1).toLong()
    fun estimate(format: StreamFormat): Long = when (format) {
        StreamFormat.OPUS_128 -> (seconds * CacheRules.OPUS_BYTES_PER_SECOND * 1.02).toLong()
        StreamFormat.RAW -> sizeBytes?.takeIf { it > 0 } ?: (seconds * (knownKbps ?: CacheRules.UNKNOWN_ORIGINAL_KBPS) * 125L)
    }
}

/**
 * Which format Coda fetches, keeps and plays (CA-2 "What Coda fetches" and "What plays").
 * [StreamFormat.RAW] is the original as stored on the server; [StreamFormat.OPUS_128] is the server's transcode.
 */
object CacheFormatPolicy {
    /** Mobile data, or metered Wi-Fi such as a phone hotspot. */
    fun metered(network: NetworkState) = !network.unmetered || network.cellular

    /** Proper Wi-Fi: connected, unmetered and not cellular. A phone hotspot (metered Wi-Fi) and mobile data are not. */
    fun wifi(network: NetworkState) = network.available && !metered(network)

    /**
     * Explicit adds download only on proper Wi-Fi: their background downloads, re-sync downloads and
     * Opus → original replacements inside adds. Elsewhere they stay pending and start by themselves on Wi-Fi; nothing
     * asks. The read-ahead window is not an add: it keeps fetching on mobile or metered data.
     */
    fun addsDownload(network: NetworkState) = wifi(network)

    /** The network an add's downloads will run on, for planning its size ("Make room?", "Not enough space"). */
    val ADD_NETWORK = NetworkState(available = true, unmetered = true, cellular = false)

    /**
     * What to fetch (and keep) when nothing is cached (no double lossy encoding on mobile). Network only:
     * free space never changes the format (lossy mode removed).
     * - A lossy original of ≤ 320 kbit/s is always the original, on any network.
     * - Anything above 320 kbit/s, any clearly lossless file (also a low-bitrate FLAC) or an unknown bitrate: the original on Wi-Fi, else Opus 128.
     */
    fun fetchFormat(network: NetworkState, song: SongFormat): StreamFormat = when {
        song.isLossyUpTo320 -> StreamFormat.RAW
        !metered(network) -> StreamFormat.RAW
        else -> StreamFormat.OPUS_128
    }

    /**
     * What a download should fetch for a song with [cached] in the cache, or null for nothing. A cached copy is never
     * downloaded again or downgraded; the only replacement is Opus → original on Wi-Fi.
     */
    fun download(cached: StreamFormat?, network: NetworkState, song: SongFormat): StreamFormat? = when (cached) {
        null -> fetchFormat(network, song)
        StreamFormat.RAW -> null
        StreamFormat.OPUS_128 -> StreamFormat.RAW.takeIf { replacesOpus(network) }
    }

    fun replacesOpus(network: NetworkState) = !metered(network)

    sealed interface Play {
        data class Cached(val kind: StreamFormat) : Play
        data class Stream(val format: StreamFormat) : Play
    }

    /**
     * A complete original plays first on any network. An Opus copy plays, except on Wi-Fi with the server reachable:
     * then the original streams (and the read-ahead keeps it).
     */
    fun play(cached: StreamFormat?, network: NetworkState, serverReachable: Boolean, song: SongFormat): Play = when {
        cached == StreamFormat.RAW -> Play.Cached(StreamFormat.RAW)
        cached == StreamFormat.OPUS_128 && replacesOpus(network) && network.available && serverReachable -> Play.Stream(StreamFormat.RAW)
        cached == StreamFormat.OPUS_128 -> Play.Cached(StreamFormat.OPUS_128)
        else -> Play.Stream(fetchFormat(network, song))
    }
}

/**
 * What actually arrived for a request (Navidrome: an Opus request answers `audio/ogg` for a transcode, and the
 * original's own type for a pass-through or fallback). Recognisable magic bytes decide; Content-Type otherwise.
 */
object ArrivalDetector {
    private fun ByteArray.startsWith(text: String, at: Int = 0) =
        size >= at + text.length && text.indices.all { this[at + it] == text[it].code.toByte() }
    private fun ByteArray.containsAscii(text: String) = (0..size - text.length).any { startsWith(text, it) }

    fun isOggOpus(head: ByteArray) = head.startsWith("OggS") && head.containsAscii("OpusHead")

    /** Containers an original arrives in: FLAC, ID3/MPEG, RIFF/WAVE, MP4 (`ftyp`), Ogg Vorbis/FLAC, AIFF, WavPack, APE. */
    fun isOtherAudio(head: ByteArray): Boolean = head.startsWith("fLaC") || head.startsWith("ID3") ||
        (head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0) ||
        head.startsWith("RIFF") || head.startsWith("ftyp", 4) || head.startsWith("FORM") || head.startsWith("wvpk") ||
        head.startsWith("MAC ") || (head.startsWith("OggS") && !head.containsAscii("OpusHead") && head.size >= 64)

    private fun oggContentType(type: String?) = type?.substringBefore(';')?.trim()?.lowercase()
        ?.let { it == "audio/ogg" || it == "audio/opus" || it == "application/ogg" } == true

    fun kind(requested: StreamFormat, contentType: String?, head: ByteArray, song: SongFormat = SongFormat()): StreamFormat {
        if (requested == StreamFormat.RAW) return StreamFormat.RAW
        val opus = when {
            isOggOpus(head) -> true
            isOtherAudio(head) -> false
            // Nothing recognisable and no type (a file being adopted): what was requested.
            contentType == null -> true
            else -> oggContentType(contentType)
        }
        if (!opus) return StreamFormat.RAW
        // Navidrome sends an Opus original of ≤ 128 kbit/s untouched for an Opus 128 request: that is the original.
        return if (song.suffix.equals("opus", ignoreCase = true) && song.opusPassesThroughServer) StreamFormat.RAW else StreamFormat.OPUS_128
    }
}
