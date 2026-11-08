package dev.coda.android.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/** Own with the UI lifecycle; closing before connection completes also releases the pending result. */
class PlaybackConnection(context: Context, onSessionExtrasChanged: () -> Unit = {}) : AutoCloseable {
    private var closed = false
    val controller: ListenableFuture<MediaController> = MediaController.Builder(
        context.applicationContext, SessionToken(context, ComponentName(context, PlaybackService::class.java)),
    ).setListener(object : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: android.os.Bundle) {
            if (!closed) onSessionExtrasChanged()
        }
    }).buildAsync()

    override fun close() {
        closed = true
        MediaController.releaseFuture(controller)
    }
}
