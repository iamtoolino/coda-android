package dev.coda.android.playback

import dev.coda.core.playback.PlaybackController
import dev.coda.core.playback.PlaybackGate

/** Main-thread focus ownership; platform callbacks and commands use this same gate. */
internal class AudioFocusPolicy(
    private val controller: PlaybackController,
    private val request: () -> Boolean,
    private val abandon: () -> Unit,
) : PlaybackGate {
    var focused = false
        private set
    private var requested = false
    private var resumeOnGain = false

    override fun requestPlay(): Boolean {
        resumeOnGain = false
        if (focused) return true
        requested = true
        focused = request()
        if (!focused) onPause()
        return focused
    }

    override fun onPause() {
        resumeOnGain = false
        focused = false
        if (requested) {
            requested = false
            abandon()
        }
    }

    fun transientLoss() {
        if (!requested) return
        focused = false
        resumeOnGain = resumeOnGain || controller.playWhenReady
        controller.suspendPlayback()
    }

    fun permanentLoss() {
        controller.pause()
    }

    fun gain() {
        // Abandoned requests may still have a queued GAIN callback.
        if (!requested) return
        focused = true
        val resume = resumeOnGain
        resumeOnGain = false
        if (resume) controller.play()
    }

    fun playbackInactive() {
        if (!resumeOnGain) onPause()
    }
}
