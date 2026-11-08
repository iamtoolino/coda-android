package dev.coda.android.playback

import dev.coda.core.playback.*

/** What the Now Playing format chip needs, gathered on the main thread; no URLs, tokens or hostnames. */
data class AudioDetailsInput(
    val serverSuffix: String?,
    val source: TrackSource,
    val engine: EngineDetails?,
    /** The server's song bitRate in kbps (`getSong`), used only when the decoder reports none (FLAC, Ogg Opus originals). */
    val serverBitRateKbps: Int? = null,
)

/** Pure formatting of the chip text, so it is unit-testable. */
object AudioDetailsFormat {
    fun codecName(mime: String?): String? = when (mime) {
        null -> null
        "audio/flac" -> "FLAC"; "audio/opus" -> "Opus"; "audio/vorbis" -> "Vorbis"; "audio/mpeg" -> "MP3"
        "audio/mp4a-latm" -> "AAC"; "audio/alac" -> "ALAC"; "audio/raw" -> "PCM"
        else -> mime.removePrefix("audio/")
    }

    /** True when this is an Opus transcode; decoded codec evidence beats the source/filename (a server may ignore format=opus). */
    fun isOpusTranscode(input: AudioDetailsInput): Boolean {
        val codec = codecName(input.engine?.codecMime)
        return input.source.transcodeFormat != null && (codec == null || codec == "Opus")
    }

    /**
     * Bitrate in kbps: the decoder's value, else 128 for an Opus transcode (Ogg Opus reports none, streamed or cached), else the
     * server's own bitRate for the song (FLAC and Ogg Opus originals report none either). The server's value describes the
     * original, so a transcode never uses it. A missing or zero server value is no value.
     */
    fun bitrateKbps(input: AudioDetailsInput): Int? {
        input.engine?.bitrate?.let { return (it + 500) / 1000 }
        // A server that ignored format=opus may have stored the original under .opus: only trust 128 for Opus.
        if (isOpusTranscode(input)) return 128
        return input.serverBitRateKbps?.takeIf { it > 0 }
    }

    private fun codec(input: AudioDetailsInput) = codecName(input.engine?.codecMime)
        ?: if (input.source.transcodeFormat != null) "Opus" else input.serverSuffix?.uppercase() ?: "Unknown"

    /** Codec plus kbps for every format ("FLAC 912", "MP3 270", "Opus 128"); just the codec when no bitrate is known. */
    fun playingFormat(input: AudioDetailsInput): String = listOfNotNull(codec(input), bitrateKbps(input)?.toString()).joinToString(" ")

    /** What TalkBack reads for the chip: "FLAC, 912 kilobits per second". */
    fun playingFormatSpoken(input: AudioDetailsInput): String =
        listOfNotNull(codec(input), bitrateKbps(input)?.let { "$it kilobits per second" }).joinToString(", ")
}

/** Process-wide read access for the UI: the playback service lives in the same process as the screens. */
object AudioDetailsHub {
    @Volatile var provider: (() -> AudioDetailsInput?)? = null
    /** Main thread only (the engine's player is main-thread bound). */
    fun snapshot(): AudioDetailsInput? = provider?.invoke()
}
