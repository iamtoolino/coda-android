package dev.coda.android.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import dev.coda.core.playback.BackgroundWorkPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The app's one source for "is the screen on" and "screen off and playing" ([quiet], decided by
 * [BackgroundWorkPolicy.quiet]). Queue sync, scrobbles, session covers and waveforms read it here; nobody asks the
 * PowerManager on their own. Screen state comes from the SCREEN_ON/OFF broadcasts (no polling), seeded from
 * `isInteractive`. [playing] is the app's playback-active flag (playing, or buffering towards it). Main thread.
 */
class QuietPlayback(context: Context, scope: CoroutineScope, playing: StateFlow<Boolean>) {
    private val screen = MutableStateFlow(true)
    val screenOn: StateFlow<Boolean> = screen.asStateFlow()

    init {
        // Register first, then read: a SCREEN_OFF/ON between the two cannot be lost.
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> screen.value = true
                    Intent.ACTION_SCREEN_OFF -> screen.value = false
                }
            }
        }, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }, Context.RECEIVER_NOT_EXPORTED)
        screen.value = context.getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: true
    }

    val quiet: StateFlow<Boolean> = combine(screen, playing) { on, active -> BackgroundWorkPolicy.quiet(on, active) }
        .stateIn(scope, SharingStarted.Eagerly, BackgroundWorkPolicy.quiet(screen.value, playing.value))
    val isPlaying: () -> Boolean = { playing.value }
}
