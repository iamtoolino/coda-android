package dev.coda.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** The sole channel consumer. Register scrobble and album-resume sinks before starting playback.
 * Each event reaches every sink in order; no competing receiveAsFlow collectors.
 */
class PlaybackEventDispatcher(
    controller: PlaybackController,
    scope: CoroutineScope,
    private val sinks: List<suspend (PlaybackEvent) -> Unit>,
) {
    private val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        controller.events.collect { event -> sinks.forEach { it(event) } }
    }

    fun release() = job.cancel()
}
