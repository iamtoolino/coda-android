package dev.coda.android.playback

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class PlaybackActionsTest {
    @Test fun endedPlaySelectsQueueStartBeforePreparingAndPlaying() {
        val calls = mutableListOf<String>()
        player(Player.STATE_ENDED, false, calls).togglePlayback()
        assertEquals(listOf("seekTo(0, 0)", "prepare()", "play()"), calls)
    }
    @Test fun activePlaybackStillTogglesWithoutReselectingQueue() {
        val calls = mutableListOf<String>()
        player(Player.STATE_READY, true, calls).togglePlayback()
        player(Player.STATE_READY, false, calls).togglePlayback()
        assertEquals(listOf("pause()", "play()"), calls)
    }
    private fun player(state: Int, playWhenReady: Boolean, calls: MutableList<String>): Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getPlaybackState" -> state
                "getPlayWhenReady" -> playWhenReady
                else -> { calls += "${method.name}(${args?.joinToString().orEmpty()})"; null }
            }
        } as Player
}
