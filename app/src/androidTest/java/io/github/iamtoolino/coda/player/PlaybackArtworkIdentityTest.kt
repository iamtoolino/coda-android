package io.github.iamtoolino.coda.player

import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamtoolino.coda.NavidromeSession
import io.github.iamtoolino.coda.data.NavidromeClient
import io.github.iamtoolino.coda.data.Song
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class PlaybackArtworkIdentityTest {
    @Test fun canonicalArtworkSurvivesMediaMetadataAndSnapshotRestoration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val account = NavidromeSession(NavidromeClient("https://example.test", "fixture", "fixture"), "fixture", 1)
        instrumentation.runOnMainSync {
            val player = ExoPlayer.Builder(context).build()
            player.volume = 0f
            try {
                for (canonical in listOf(null, "album-cover")) {
                    val song = Song("track", "Track", albumId = "album", coverArt = "embedded",
                        canonicalAlbumCoverArt = canonical)
                    val expected = canonical ?: "album"
                    val item = song.toPlayableMediaItem(context, false, account)
                    assertEquals(expected, item.mediaMetadata.extras?.getString("coverArtId"))
                    assertEquals(expected, item.mediaMetadata.artworkUri?.pathSegments?.get(2))
                    // Metadata only; do not prepare or load the fixture's network URL.
                    player.setMediaItems(listOf(item), 0, 1234L)
                    val snapshot = requireNotNull(player.playbackSnapshot(account))
                    val restored = requireNotNull(PlaybackSnapshotCodec.decode(PlaybackSnapshotCodec.encode(snapshot)))
                    assertEquals(1234L, restored.positionMs)
                    assertEquals(canonical, restored.songs.single().canonicalAlbumCoverArt)
                    val replacement = restored.songs.single().toPlayableMediaItem(context, false, account)
                    assertEquals(item.mediaMetadata.artworkUri, replacement.mediaMetadata.artworkUri)
                    assertEquals(expected, replacement.mediaMetadata.extras?.getString("coverArtId"))
                }
            } finally {
                player.release()
            }
        }
    }
}
