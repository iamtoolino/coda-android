package io.github.iamtoolino.coda.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class PlaybackStreamFormatTest {
    @Test fun controllerReceivesObservedFormatAcrossTrackChangesAndClearing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var player: ExoPlayer
        lateinit var session: MediaSession
        lateinit var future: ListenableFuture<MediaController>
        val files = listOf(8_000, 16_000).map { rate ->
            File.createTempFile("stream-format-", ".wav", context.cacheDir).apply {
                val dataSize = rate * 2
                writeBytes(ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray())
                    putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2)
                    putShort(2); putShort(16); put("data".toByteArray()); putInt(dataSize)
                }.array())
            }
        }
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build().apply { volume = 0f }
            session = MediaSession.Builder(context, player).setId("stream-format-test").build()
            future = MediaController.Builder(context, session.token).buildAsync()
        }
        try {
            val controller = future.get(10, TimeUnit.SECONDS)
            instrumentation.runOnMainSync { assertNull(controller.currentTracks.selectedAudioFormat()) }
            for ((index, rate) in listOf(8_000, 16_000).withIndex()) {
                val ready = CountDownLatch(1)
                val listener = object : Player.Listener {
                    override fun onTracksChanged(tracks: Tracks) {
                        if (tracks.selectedAudioFormat()?.sampleRate == rate) ready.countDown()
                    }
                }
                instrumentation.runOnMainSync {
                    controller.addListener(listener)
                    player.setMediaItem(MediaItem.fromUri(files[index].toURI().toString()))
                    player.prepare()
                }
                assertTrue("Selected format $rate reaches the controller", ready.await(10, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    val format = requireNotNull(controller.currentTracks.selectedAudioFormat())
                    assertEquals("PCM", format.sampleMimeType?.let(::audioCodecName))
                    assertEquals(rate, format.sampleRate)
                    assertEquals(1, format.channelCount)
                    controller.removeListener(listener)
                }
            }
            val cleared = CountDownLatch(1)
            instrumentation.runOnMainSync {
                controller.addListener(object : Player.Listener {
                    override fun onTracksChanged(tracks: Tracks) {
                        if (tracks.selectedAudioFormat() == null) cleared.countDown()
                    }
                })
                player.clearMediaItems()
            }
            assertTrue("Old stream format is cleared", cleared.await(10, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync {
                MediaController.releaseFuture(future)
                session.release()
                player.release()
            }
            files.forEach(File::delete)
        }
    }
}
