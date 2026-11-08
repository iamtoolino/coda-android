package dev.coda.core.playback

import org.junit.Assert.*
import org.junit.Test

/** Which sources are the server's Opus 128 transcode (cache files are named by TrackKey: .opus / .raw). */
class TrackSourceTranscodeTest {
    @Test fun cachedFileFormatFollowsTheCacheFileName() {
        assertEquals(StreamFormat.OPUS_128, TrackSource.CachedFile("/cache/0123abcd.opus").transcodedFormat)
        assertNull(TrackSource.CachedFile("/cache/0123abcd.raw").transcodedFormat)
        assertNull(TrackSource.CachedFile("/cache/x.opus.raw").transcodedFormat)
        assertNull(TrackSource.CachedFile("/cache.opus/x.raw").transcodedFormat)
        assertNull(TrackSource.CachedFile("/cache/x.flac").transcodedFormat)
        assertNull(TrackSource.CachedFile("/cache/noextension").transcodedFormat)
    }

    @Test fun transcodeFormatCoversStreamedAndCachedSources() {
        assertEquals(StreamFormat.OPUS_128, TrackSource.Remote("s", StreamFormat.OPUS_128).transcodeFormat)
        assertNull(TrackSource.Remote("s", StreamFormat.RAW).transcodeFormat)
        assertEquals(StreamFormat.OPUS_128, TrackSource.CachedFile("/c/a.opus").transcodeFormat)
        assertNull(TrackSource.CachedFile("/c/a.raw").transcodeFormat)
    }
}
