package dev.coda.core.playback

/** Synchronous authorization before any engine command that can start audio. */
interface PlaybackGate {
    fun requestPlay(): Boolean
    /** Called even when already paused, so an explicit command cancels pending focus resume. */
    fun onPause()

    data object Unrestricted : PlaybackGate {
        override fun requestPlay() = true
        override fun onPause() = Unit
    }
}
