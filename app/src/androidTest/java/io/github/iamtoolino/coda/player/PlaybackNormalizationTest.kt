package io.github.iamtoolino.coda.player

import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamtoolino.coda.NavidromeSession
import io.github.iamtoolino.coda.data.NavidromeClient
import io.github.iamtoolino.coda.data.NORMALIZED_STREAM_FORMAT
import io.github.iamtoolino.coda.data.Song
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class PlaybackNormalizationTest {
    @Test fun streamRoutingAndMixedQueueRestoration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val account = NavidromeSession(NavidromeClient("https://example.test", "fixture", "fixture"), "fixture", 1)
        instrumentation.runOnMainSync {
            val player = ExoPlayer.Builder(context).build()
            try {
                val songs = listOf(Song("same", "Track"), Song("same", "Track", normalizedStream = true))
                for (mobile in listOf(false, true)) {
                    val items = songs.map { it.toPlayableMediaItem(context, mobile, account) }
                    val expected = listOf(if (mobile) "opus" else "raw", NORMALIZED_STREAM_FORMAT)
                    items.forEachIndexed { index, item ->
                        assertEquals(expected[index], Uri.parse(item.localConfiguration!!.uri.toString()).getQueryParameter("format"))
                        assertEquals(streamCacheKey("fixture", "same", expected[index]), item.localConfiguration!!.customCacheKey)
                    }
                    assertEquals(MimeTypes.AUDIO_OGG, items[1].localConfiguration!!.mimeType)
                    player.setMediaItems(items, 1, 1234)
                    val saved = requireNotNull(player.playbackSnapshot(account))
                    val restored = requireNotNull(PlaybackSnapshotCodec.decode(PlaybackSnapshotCodec.encode(saved)))
                    assertEquals(listOf(false, true), restored.songs.map { it.normalizedStream })
                    assertEquals(listOf("same", "same"), restored.songs.map { it.id })
                    assertEquals(true, restored.songs[1].toPlayableMediaItem(context, !mobile, account)
                        .mediaMetadata.extras?.getBoolean("normalizedStream"))
                }
                val id = CodaMediaIds.playlistSong("playlist: /", "song: /")
                assertEquals("playlist: /" to "song: /", CodaMediaIds.playlistSongIds(id))
                assertEquals(null, CodaMediaIds.playlistSongIds(CodaMediaIds.song("same")))
            } finally {
                player.release()
            }
        }
    }
}
