package dev.coda.android.playback

import androidx.media3.common.Player
import dev.coda.core.connectivity.ConnectivityRecovery
import dev.coda.core.playback.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodaPlayerStateTest {
    /**
     * Media3's `SimpleBasePlayer.State.Builder.build` throws for a loading state while idle or ended: Dana's OF-1 capture
     * crashed there (CodaPlayer.kt:109) after stopping while playback waited for the network.
     */
    @Test fun bufferingOnlyLoadsWhileBufferingOrReady() {
        assertTrue(loadingFor(Player.STATE_BUFFERING, buffering = true))
        assertTrue(loadingFor(Player.STATE_READY, buffering = true))
        assertFalse(loadingFor(Player.STATE_IDLE, buffering = true))
        assertFalse(loadingFor(Player.STATE_ENDED, buffering = true))
        assertFalse(loadingFor(Player.STATE_READY, buffering = false))
    }

    /** Whatever the flags say, an empty playlist is idle and a failure is idle (the builder's other checks). */
    @Test fun emptyPlaylistAndFailureStayIdleWhileWaiting() {
        val waiting = ConnectivityRecovery.Waiting(OccurrenceId(), 0, true, 0, 0)
        val waitingState = PlaybackState(waiting = waiting, isBuffering = true, isPrepared = true)
        assertEquals(Player.STATE_IDLE, playbackStateFor(waitingState, playlistEmpty = true))
        assertEquals(Player.STATE_BUFFERING, playbackStateFor(waitingState, playlistEmpty = false))
        assertEquals(Player.STATE_IDLE, playbackStateFor(PlaybackState(failure = PlaybackFailure.Network, isPrepared = true), playlistEmpty = false))
        assertEquals(Player.STATE_READY, playbackStateFor(PlaybackState(isPrepared = true), playlistEmpty = false))
    }

    /** The session offers seeking in the current item exactly when the controller honours it. */
    @Test fun sessionSeekCapabilityFollowsTheControllersSeekPolicy() {
        val opus = PlaybackState(currentOccurrence = Occurrence(QueueEntry("a"), null,
            EngineItem(OccurrenceId(), TrackSource.Remote("a", StreamFormat.OPUS_128), 0.0)))
        for (transcodeSeeks in listOf(false, true)) for (cached in listOf(false, true)) {
            val seekable = currentItemSeekable(opus, { it == "a" && cached }, transcodeSeeks)
            assertEquals("cached=$cached transcodeSeeks=$transcodeSeeks", cached || transcodeSeeks, seekable)
            assertEquals(seekable, sessionCommands(seekable).contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
            assertTrue(sessionCommands(seekable).contains(Player.COMMAND_SEEK_TO_NEXT))
        }
        // The controller ignores exactly the seek the session no longer offers.
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine, sourceResolver = SourceResolver { TrackSource.Remote(it.songId, StreamFormat.OPUS_128) })
        player.replaceQueue(listOf(QueueEntry("a", durationMs = 200_000)), startAt = 0)
        player.seek(60_000)
        assertTrue(engine.seeks.isEmpty())
        assertFalse(currentItemSeekable(player.state.value, { false }, false))
        // Original streams and local files stay seekable.
        val raw = player.state.value.let { s -> s.copy(currentOccurrence = s.currentOccurrence!!.let {
            it.copy(item = it.item.copy(source = TrackSource.Remote("a", StreamFormat.RAW))) }) }
        assertTrue(currentItemSeekable(raw, { false }, false))
        assertTrue(currentItemSeekable(PlaybackState(), { false }, false))
    }
}
