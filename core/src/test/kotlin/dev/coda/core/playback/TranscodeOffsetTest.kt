package dev.coda.core.playback

import dev.coda.core.subsonic.SubsonicClient
import dev.coda.core.subsonic.SubsonicConfig
import dev.coda.core.testing.FakeSubsonicServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** When a load asks the server's transcode to start at `timeOffset`, and the URL it sends. */
class TranscodeOffsetTest {
    private val opus = TrackSource.Remote("s", StreamFormat.OPUS_128)

    @Test fun onlyAnUncachedTranscodeLoadedAtOneSecondOrLaterAsksForWholeSeconds() {
        assertEquals(0, TranscodeOffset.requestSeconds(opus, 0))
        assertEquals(0, TranscodeOffset.requestSeconds(opus, 999))
        assertEquals(1, TranscodeOffset.requestSeconds(opus, 1_000))
        assertEquals("the second at or before the position", 8, TranscodeOffset.requestSeconds(opus, 8_999))
        assertEquals(125, TranscodeOffset.requestSeconds(opus, 125_400))
        // The original seeks with Range and ignores timeOffset; a cached file seeks locally.
        assertEquals(0, TranscodeOffset.requestSeconds(TrackSource.Remote("s", StreamFormat.RAW), 8_000))
        assertEquals(0, TranscodeOffset.requestSeconds(TrackSource.CachedFile("/c/x.opus"), 8_000))
        assertEquals("never an offset on top of an offset", 0, TranscodeOffset.requestSeconds(opus.copy(timeOffsetSeconds = 3), 8_000))
    }

    @Test fun withoutTheExtensionTheTranscodeStartsAtZeroAsBefore() {
        assertEquals(8, TranscodeOffset.appliedSeconds(8, serverSupportsOffset = true))
        assertEquals(0, TranscodeOffset.appliedSeconds(8, serverSupportsOffset = false))
        assertTrue(TranscodeOffset.supportedBy(setOf("formPost", "transcodeOffset")))
        assertFalse(TranscodeOffset.supportedBy(setOf("formPost", "songLyrics")))
        assertFalse(TranscodeOffset.supportedBy(emptySet()))
    }

    @Test fun theOffsetIsSentAsWholeSecondsWithTheTranscodeOnly() {
        val config = SubsonicConfig("https://music.example/navidrome", "user", "secret", "Coda on Test")
        val offset = config.streamUrl("song-1", "opus", timeOffsetSeconds = 8)
        assertEquals("8", offset.queryParameter("timeOffset"))
        assertEquals("opus", offset.queryParameter("format"))
        assertEquals("128", offset.queryParameter("maxBitRate"))
        assertNull(config.streamUrl("song-1", "opus").queryParameter("timeOffset"))
        assertNull(config.streamUrl("song-1", "opus", timeOffsetSeconds = 0).queryParameter("timeOffset"))
        assertNull("the original ignores it", config.streamUrl("song-1", "raw", timeOffsetSeconds = 8).queryParameter("timeOffset"))
    }

    @Test fun extensionsAreReadFromTheServer() = runBlocking {
        FakeSubsonicServer().use { server ->
            val client = SubsonicClient(SubsonicConfig(server.baseUrl, "synthetic", "synthetic-password", "Coda test"))
            server.openSubsonicExtensions = listOf("formPost", "transcodeOffset")
            assertEquals(setOf("formPost", "transcodeOffset"), client.openSubsonicExtensions())
            server.openSubsonicExtensions = null
            assertTrue(runCatching { client.openSubsonicExtensions() }.isFailure)
        }
    }
}
