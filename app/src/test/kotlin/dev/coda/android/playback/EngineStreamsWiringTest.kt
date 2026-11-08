package dev.coda.android.playback

import android.net.Uri
import dev.coda.core.playback.StreamFormat
import dev.coda.core.playback.TrackSource
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * The service's engine resolver forwards the transcode-offset capability of whatever resolver the
 * session installed, also one installed after the engine was built. PlaybackService builds its engine with
 * [PlaybackDependencies.newEngine], which uses this resolver; Mp2bOffsetResumeDeviceTest plays through that engine.
 */
class EngineStreamsWiringTest {
    private val installed = PlaybackDependencies.remoteStreams
    @After fun restore() { PlaybackDependencies.remoteStreams = installed }

    private class Resolved(val source: TrackSource.Remote) : RuntimeException()
    private fun session(supported: Boolean) = object : RemoteStreamResolver {
        override fun resolve(source: TrackSource.Remote): Uri = throw Resolved(source)
        override fun supportsTranscodeOffset() = supported
        override fun knownTranscodeOffset() = supported
    }

    @Test fun theEngineResolverForwardsTheCapabilityOfTheCurrentSession() {
        val engineStreams = PlaybackDependencies.engineStreams
        assertFalse("no session: the default resolver has no capability", engineStreams.supportsTranscodeOffset())
        PlaybackDependencies.remoteStreams = session(supported = true)
        assertTrue(engineStreams.supportsTranscodeOffset())
        PlaybackDependencies.remoteStreams = session(supported = false)
        assertFalse(engineStreams.supportsTranscodeOffset())
        assertEquals("a known no is forwarded as known (MP-2c Rex P3-3)", false, engineStreams.knownTranscodeOffset())
    }

    @Test fun withoutASessionTheCapabilityIsUnknown() {
        assertNull(PlaybackDependencies.engineStreams.knownTranscodeOffset())
    }

    @Test fun theEngineResolverForwardsResolveWithTheOffset() {
        PlaybackDependencies.remoteStreams = session(supported = true)
        val source = TrackSource.Remote("song", StreamFormat.OPUS_128, timeOffsetSeconds = 8)
        val resolved = assertThrows(Resolved::class.java) { PlaybackDependencies.engineStreams.resolve(source) }
        assertEquals(source, resolved.source)
    }
}
