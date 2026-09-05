package io.github.iamtoolino.coda.player

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class AppendQueueTest {
    @Test fun emptyQueueAppendClearsRetainedPlayIntentAndRemainsPaused() = withPlayer { player, items ->
        player.playWhenReady = true
        player.appendQueueItems(items)
        assertFalse(player.playWhenReady)
        assertEquals(2, player.mediaItemCount)
        assertEquals(0, player.currentMediaItemIndex)
        assertEquals(0L, player.currentPosition)
    }

    @Test fun populatedQueueAppendPreservesPausedAndPlayingIntentIndexAndPosition() = withPlayer { player, items ->
        for (playing in listOf(false, true)) {
            player.setMediaItems(items, 1, 120L)
            player.playWhenReady = playing
            player.appendQueueItems(items)
            assertEquals(playing, player.playWhenReady)
            assertEquals(4, player.mediaItemCount)
            assertEquals(1, player.currentMediaItemIndex)
            assertEquals(120L, player.currentPosition)
            assertEquals(listOf("first", "last", "first", "last"),
                (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
        }
    }

    private fun withPlayer(check: (ExoPlayer, List<MediaItem>) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val audio = File.createTempFile("append-fixture", ".wav", context.cacheDir)
        // Local silence only: these tests neither authenticate nor contact a server.
        val samples = ByteArray(16000)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + samples.size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples.size).array()
        audio.writeBytes(header + samples)
        try {
            instrumentation.runOnMainSync {
                val player = ExoPlayer.Builder(context).build()
                player.volume = 0f
                try {
                    val items = listOf("first", "last").map {
                        MediaItem.Builder().setMediaId(it).setUri(audio.toURI().toString()).build()
                    }
                    check(player, items)
                } finally {
                    player.release()
                }
            }
        } finally {
            audio.delete()
        }
    }
}
