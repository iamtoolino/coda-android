package dev.coda.android.playback

import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.decoder.DecoderException
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import dev.coda.android.cache.TrackReadException
import dev.coda.core.playback.PlaybackFailure
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

@UnstableApi
class PlaybackFailuresTest {
    // Media3 declares the Uri non-null; reflection supplies null without the Android runtime.
    private fun unrecognized() = UnrecognizedInputFormatException::class.java
        .getConstructor(String::class.java, android.net.Uri::class.java).newInstance("unknown", null)
    @Test fun parserAndDecoderFailuresAreDecodeBeforeTransportClassification() {
        assertEquals(PlaybackFailure.Decode, playbackFailure(ParserException.createForMalformedContainer("corrupt", null)))
        assertEquals(PlaybackFailure.Decode, playbackFailure(unrecognized()))
        assertEquals(PlaybackFailure.Decode, playbackFailure(IOException("load", ParserException.createForUnsupportedContainerFeature("x"))))
        assertEquals(PlaybackFailure.Decode, playbackFailure(RuntimeException(DecoderException("codec"))))
        assertEquals(PlaybackFailure.Network, playbackFailure(RuntimeException(java.net.SocketTimeoutException("slow"))))
        assertEquals(PlaybackFailure.Network, playbackFailure(IOException("reset")))
        assertEquals(PlaybackFailure.NotFound, playbackFailure(RuntimeException(TrackReadException(PlaybackFailure.NotFound))))
        assertEquals(PlaybackFailure.Unknown, playbackFailure(FileDataSource.FileDataSourceException(IOException("gone"), 2005)))
        assertEquals(PlaybackFailure.Unknown, playbackFailure(IllegalStateException("other")))
    }
}
