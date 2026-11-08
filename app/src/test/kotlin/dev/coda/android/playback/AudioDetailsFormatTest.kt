package dev.coda.android.playback

import dev.coda.core.playback.*
import org.junit.Assert.*
import org.junit.Test

/** The Now Playing format chip is codec plus kbps for every format, read out in words. */
class AudioDetailsFormatTest {
    private fun input(source: TrackSource = TrackSource.Remote("s", StreamFormat.RAW), engine: EngineDetails? = null,
        suffix: String? = "flac", serverKbps: Int? = null) =
        AudioDetailsInput(serverSuffix = suffix, source = source, engine = engine, serverBitRateKbps = serverKbps)
    private fun chip(i: AudioDetailsInput) = AudioDetailsFormat.playingFormat(i)
    private fun spoken(i: AudioDetailsInput) = AudioDetailsFormat.playingFormatSpoken(i)
    private fun engine(codec: String, bitrate: Int?) = EngineDetails(null, codec, bitrate, null, null, null, null, 0, 0, null, null, false, 0)
    private val flac = engine("audio/flac", null)
    private val opus = engine("audio/opus", 128_000)

    /** The FLAC decoder reports no bitrate, so the chip takes the server's song bitRate, streamed or cached. */
    @Test fun flacShowsTheServersBitRate() {
        for (source in listOf(TrackSource.Remote("s", StreamFormat.RAW), TrackSource.CachedFile("/cache/x.raw"))) {
            val i = input(source = source, engine = flac, serverKbps = 912)
            assertEquals("FLAC 912", chip(i))
            assertEquals("FLAC, 912 kilobits per second", spoken(i))
        }
        // A decoder that does report one (e.g. from the container) wins.
        assertEquals("FLAC 920", chip(input(engine = flac.copy(bitrate = 920_000), serverKbps = 912)))
    }

    @Test fun mp3TakesTheDecodersBitrateElseTheServers() {
        assertEquals("MP3 320", chip(input(engine = engine("audio/mpeg", 320_000), suffix = "mp3", serverKbps = 270)))
        val server = input(engine = engine("audio/mpeg", null), suffix = "mp3", serverKbps = 270)
        assertEquals("MP3 270", chip(server))
        assertEquals("MP3, 270 kilobits per second", spoken(server))
    }

    /** Ogg Opus reports no bitrate to the player, so a transcode (streamed or cached) shows 128. */
    @Test fun opusCopyShowsItsOwnBitrateElse128() {
        val noBitrate = opus.copy(bitrate = null)
        for (source in listOf(TrackSource.Remote("s", StreamFormat.OPUS_128), TrackSource.CachedFile("/cache/0123abcd.opus"))) {
            assertEquals("Opus 128", chip(input(source = source, engine = noBitrate)))
            // The server's bitRate describes the original, never the copy.
            assertEquals("Opus 128", chip(input(source = source, engine = noBitrate, serverKbps = 900)))
            // What the decoder reports wins over the 128 fallback.
            assertEquals("Opus 96", chip(input(source = source, engine = opus.copy(bitrate = 96_000), serverKbps = 900)))
        }
        // Before the decoder starts, the codec comes from the source.
        assertEquals("Opus 128", chip(input(source = TrackSource.CachedFile("/cache/0123abcd.opus"))))
        assertEquals("Opus, 128 kilobits per second", spoken(input(source = TrackSource.Remote("s", StreamFormat.OPUS_128))))
    }

    /** An Ogg Opus original reports no bitrate; the chip takes the server's song bitRate. */
    @Test fun opusOriginalTakesTheServersBitRate() {
        val noBitrate = opus.copy(bitrate = null)
        for (path in listOf("/cache/x.raw", "/cache/x.opus.raw", "/cache.opus/x.raw")) {
            assertEquals(path, "Opus 192", chip(input(source = TrackSource.CachedFile(path), engine = noBitrate, suffix = "opus", serverKbps = 192)))
        }
        // A server that ignored format=opus: stored as .opus but decodes as FLAC, so no 128 and the server's value counts.
        assertEquals("FLAC 912", chip(input(source = TrackSource.CachedFile("/cache/0123abcd.opus"), engine = flac, serverKbps = 912)))
        assertEquals("FLAC 912", chip(input(source = TrackSource.Remote("s", StreamFormat.OPUS_128), engine = flac, serverKbps = 912)))
    }

    @Test fun unknownBitrateShowsJustTheCodec() {
        for (kbps in listOf(null, 0)) {
            assertEquals("FLAC", chip(input(engine = flac, serverKbps = kbps)))
            assertEquals("FLAC", spoken(input(engine = flac, serverKbps = kbps)))
            assertEquals("Opus", chip(input(engine = opus.copy(bitrate = null), serverKbps = kbps)))
        }
        // No decoder yet: the server's file type names the codec.
        assertEquals("ALAC", chip(input(suffix = "alac")))
        assertEquals("Unknown", chip(input(suffix = null)))
    }
}
