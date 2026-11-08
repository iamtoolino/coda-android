package dev.coda.android.playback

import androidx.media3.common.Player

/** Select the queue start before Play, so MediaSession cannot resume the ended last item. */
fun Player.togglePlayback() {
    if (playbackState == Player.STATE_ENDED) {
        seekTo(0, 0)
        prepare()
        play()
    } else if (playWhenReady) pause() else play()
}

/** True unless the current item is a server transcode that cannot seek yet (not fully cached, no `timeOffset`; see [SeekPolicy]). */
val androidx.media3.session.MediaController.currentSeekable: Boolean get() = sessionExtras.getBoolean(SEEKABLE, true)

/** Waiting controls retry without changing the intent owned by notification/headset Play and Pause. */
fun androidx.media3.session.MediaController.retryOrTogglePlayback() {
    if (sessionExtras.getBoolean("waiting")) {
        sendCustomCommand(androidx.media3.session.SessionCommand(RETRY_CONNECTION, android.os.Bundle.EMPTY), android.os.Bundle.EMPTY)
    } else togglePlayback()
}
