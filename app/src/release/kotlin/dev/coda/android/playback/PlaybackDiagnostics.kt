package dev.coda.android.playback

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi

@UnstableApi
internal object PlaybackDiagnostics {
    fun onAudioDecoderInitialized(name: String) = Unit
    fun onControllerCreated(controller: dev.coda.core.playback.PlaybackController) = Unit
    fun processors(): List<AudioProcessor> = emptyList()
    fun onSeekHandled(index: Int, positionMs: Long) = Unit
    fun onPlayWhenReadyHandled(playWhenReady: Boolean) = Unit
    fun onMiniProgressDrawn(progress: Float, player: androidx.media3.common.Player) = Unit
}
