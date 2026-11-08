package dev.coda.android

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionError
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.session.SessionToken
import dev.coda.android.playback.PhoneVoice
import dev.coda.android.playback.PlaybackService
import dev.coda.android.session.SessionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Invisible trampoline for `MEDIA_PLAY_FROM_SEARCH` (phone Assistant). It hands the query to the playback
 * service as the car's voice request and closes once its request plays or failed (a toast says why). It stays
 * (transparent, touch-through) until then because a service may only start in the foreground while the app is visible.
 * Not signed in: the app opens on its login screen. It reads nothing but the query and string extras, logs nothing.
 */
class VoiceSearchActivity : ComponentActivity() {
    private var controller: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    /** Requests sent and not yet answered: the session answers each one with an error or with [PhoneVoice.STARTED]. */
    private var outstanding = 0
    /** A request of ours started playback: close once the player plays (the service is in the foreground then). */
    private var started = false
    private var guard: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        // A recreated activity must not repeat the request.
        if (savedInstanceState != null || !PhoneVoice.handles(intent?.action)) { finish(); return }
        handle(intent)
    }

    /** Single task: a second "play Y" while this one is up arrives here and is sent too; the newest request plays. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (PhoneVoice.handles(intent.action) && !isFinishing) handle(intent)
    }

    private fun handle(intent: Intent) {
        val request = PhoneVoice.request(intent.extras)
        val app = application as CodaApp
        guard?.cancel()
        guard = lifecycleScope.launch {
            // Same wait as the car (AutoLibraryCallback.connected): a cold start may still be restoring the login.
            val state = withTimeoutOrNull(RESTORE_WAIT_MS) {
                app.sessions.state.first { it is SessionState.Connected || it is SessionState.NeedsLogin || it is SessionState.SignedOut }
            }
            if (state !is SessionState.Connected) { openApp(); return@launch }
            val future = controller ?: connect().also { controller = it }
            future.addListener({
                val player = runCatching { future.get() }.getOrNull() ?: return@addListener finish()
                outstanding++
                // No Play from here, like the car: the session plays once the resolved queue is installed
                // ([PhoneVoice.EXTRA_PLAY]). A failed request starts nothing, and a Play on an empty player would be
                // Media3's playback resumption (the start chain).
                player.setMediaItem(request)
            }, mainExecutor)
            // The car chain gives up within its own bounds (8 s per server call); this only guards against a lost answer.
            delay(RESULT_WAIT_MS)
            finish()
        }
    }

    private fun connect() = MediaController.Builder(applicationContext, SessionToken(applicationContext, ComponentName(applicationContext, PlaybackService::class.java)))
        .setListener(object : MediaController.Listener {
            override fun onError(controller: MediaController, sessionError: SessionError) {
                outstanding = (outstanding - 1).coerceAtLeast(0)
                when (val outcome = PhoneVoice.outcome(sessionError.code, sessionError.message)) {
                    PhoneVoice.Outcome.OpenApp -> openApp()
                    is PhoneVoice.Outcome.Toast -> { PhoneVoice.toast(this@VoiceSearchActivity, outcome.message); closeWhenAnswered(controller) }
                }
            }

            override fun onCustomCommand(controller: MediaController, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (command.customAction != PhoneVoice.STARTED) return super.onCustomCommand(controller, command, args)
                outstanding = (outstanding - 1).coerceAtLeast(0)
                started = true
                closeWhenAnswered(controller)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }).buildAsync().also { future ->
            future.addListener({
                runCatching { future.get() }.getOrNull()?.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying && started && outstanding == 0) finish()
                    }
                })
            }, mainExecutor)
        }

    /** Every request answered: close, but after a start only once the player plays. */
    private fun closeWhenAnswered(player: Player) {
        if (outstanding == 0 && (!started || player.isPlaying)) finish()
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onDestroy() {
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }

    private companion object {
        const val RESTORE_WAIT_MS = 2_000L
        const val RESULT_WAIT_MS = 30_000L
    }
}
