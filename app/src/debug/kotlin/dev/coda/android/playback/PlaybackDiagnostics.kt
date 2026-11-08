package dev.coda.android.playback

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor

/** Install before constructing the engine. Receives PCM after end trimming and per-item gain. */
@UnstableApi
object PlaybackDiagnostics {
    var audioDecoderInitialized: ((String) -> Unit)? = null
    fun onAudioDecoderInitialized(name: String) { audioDecoderInitialized?.invoke(name) }
    var pcmTap: TeeAudioProcessor.AudioBufferSink? = null
    var controllerCreated: ((dev.coda.core.playback.PlaybackController) -> Unit)? = null
    fun onControllerCreated(controller: dev.coda.core.playback.PlaybackController) { controllerCreated?.invoke(controller) }
    /** Every seek request the session player receives, from any controller, as (target index, position); tests only. */
    var seekHandled: ((index: Int, positionMs: Long) -> Unit)? = null
    fun onSeekHandled(index: Int, positionMs: Long) { seekHandled?.invoke(index, positionMs) }
    /** Every play (true) or pause (false) request the session player receives, from any controller; tests only. */
    var playWhenReadyHandled: ((playWhenReady: Boolean) -> Unit)? = null
    fun onPlayWhenReadyHandled(playWhenReady: Boolean) { playWhenReadyHandled?.invoke(playWhenReady) }
    /** Every progress value the mini player draws, with the item it belongs to; tests only. */
    var miniProgressDrawn: ((progress: Float, item: androidx.media3.common.MediaItem?) -> Unit)? = null
    fun onMiniProgressDrawn(progress: Float, player: androidx.media3.common.Player) { miniProgressDrawn?.invoke(progress, player.currentMediaItem) }
    fun processors(): List<AudioProcessor> = pcmTap?.let { listOf(TeeAudioProcessor(it)) } ?: emptyList()
}
