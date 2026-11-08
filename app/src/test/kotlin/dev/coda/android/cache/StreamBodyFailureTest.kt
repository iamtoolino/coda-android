package dev.coda.android.cache

import dev.coda.core.playback.PlaybackFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamBodyFailureTest {
    @Test fun subsonicErrorsAreClassifiedWithoutPersistingTheirBody() {
        assertEquals(PlaybackFailure.NotFound, streamBodyFailure("""{"error":{"code":70}}"""))
        assertEquals(PlaybackFailure.Unauthorized, streamBodyFailure("""<error code="40" message="private"/>"""))
        assertEquals(PlaybackFailure.Unauthorized, streamBodyFailure("""{"error":{"code":41}}"""))
        assertEquals(PlaybackFailure.Decode, streamBodyFailure("""{"error":{"code":0}}"""))
    }
}
