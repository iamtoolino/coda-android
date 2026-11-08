package dev.coda.android.playback

import android.content.*
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import dev.coda.core.playback.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal data class SessionLocks(val wake: Boolean, val wifi: Boolean)

/**
 * Lock ownership. Audible playback holds the locks while it has focus. Waiting to resume holds
 * them too, without focus (playback that starts offline never requested focus), but only until the wait's
 * [dev.coda.core.connectivity.ConnectivityRecovery.Waiting.lockUntilMs]: [capExpired] then releases them even
 * if focus is still held, and resume relies on the network callback and the relaxed retry timer. A paused
 * wait holds nothing.
 */
internal fun sessionLocks(state: PlaybackState, focused: Boolean, capExpired: Boolean = false): SessionLocks {
    state.waiting?.let { val held = it.autoResume && !capExpired; return SessionLocks(wake = held, wifi = held) }
    val playing = state.isPlaying && focused
    val remote = state.currentOccurrence?.item?.source is TrackSource.Remote
    return SessionLocks(wake = playing, wifi = playing && remote)
}

/**
 * Applies [sessionLocks] for every state and re-evaluates when a waiting state's lock cap passes, which no
 * state emission signals. Time and the scope's clock are injected so tests run in virtual time.
 */
internal class SessionLockDriver(
    scope: CoroutineScope,
    state: Flow<PlaybackState>,
    private val focused: () -> Boolean,
    private val nowMs: () -> Long,
    private val apply: (SessionLocks) -> Unit,
) {
    private val job = scope.launch {
        state.collectLatest { s ->
            val remaining = s.waiting?.let { it.lockUntilMs - nowMs() } ?: 0
            apply(sessionLocks(s, focused(), capExpired = remaining <= 0))
            if (s.waiting?.autoResume == true && remaining > 0) {
                delay(remaining)
                apply(sessionLocks(s, focused(), capExpired = true))
            }
        }
    }
    fun cancel() = job.cancel()
}

/** One owner of focus, noisy handling and locks, independent of the decoder implementation. */
internal class AudioSessionGuard(context: Context, private val controller: PlaybackController, scope: CoroutineScope) {
    private val context = context.applicationContext
    private val audio = context.getSystemService(AudioManager::class.java)
    private val wake = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Coda:playback").apply { setReferenceCounted(false) }
    @Suppress("DEPRECATION")
    private val wifi = context.getSystemService(WifiManager::class.java)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Coda:stream").apply { setReferenceCounted(false) }
    private val focusPolicy = AudioFocusPolicy(controller,
        request = { audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED },
        abandon = { audio.abandonAudioFocusRequest(focus) },
    )
    private val focus: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> focusPolicy.permanentLoss()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> focusPolicy.transientLoss()
                AudioManager.AUDIOFOCUS_GAIN -> focusPolicy.gain()
            }
        }.build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            controller.pause(immediate = true) // Safety first, no fade out of the speaker.
        }
    }
    private val locks = SessionLockDriver(scope, controller.state, { focusPolicy.focused }, { android.os.SystemClock.elapsedRealtime() }) { locks ->
        if (locks.wake && !wake.isHeld) wake.acquire()
        if (!locks.wake && wake.isHeld) wake.release()
        if (locks.wifi && !wifi.isHeld) wifi.acquire()
        if (!locks.wifi && wifi.isHeld) wifi.release()
        dev.coda.android.diag.BatteryLog.lock(dev.coda.android.diag.BatteryIds.LOCK_PLAYBACK, locks.wake); dev.coda.android.diag.BatteryLog.lock(dev.coda.android.diag.BatteryIds.LOCK_WIFI, locks.wifi)
    }
    private val job = scope.launch {
        controller.state.collect { state -> if (!state.isPlaying) focusPolicy.playbackInactive() }
    }
    init {
        controller.playbackGate = focusPolicy
        this.context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
    }
    fun release() {
        job.cancel(); locks.cancel(); focusPolicy.onPause()
        context.unregisterReceiver(noisy)
        if (wake.isHeld) wake.release()
        if (wifi.isHeld) wifi.release()
        dev.coda.android.diag.BatteryLog.lock(dev.coda.android.diag.BatteryIds.LOCK_PLAYBACK, false); dev.coda.android.diag.BatteryLog.lock(dev.coda.android.diag.BatteryIds.LOCK_WIFI, false)
    }
}
