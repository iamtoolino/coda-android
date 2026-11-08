package dev.coda.android.scrobble

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dev.coda.android.playback.PlaybackDependencies
import dev.coda.android.session.Session
import dev.coda.android.session.SessionRepository
import dev.coda.android.session.SessionState
import dev.coda.core.scrobble.ScrobbleApi
import dev.coda.core.scrobble.ScrobbleCoordinator
import dev.coda.core.scrobble.ScrobbleOutbox
import dev.coda.core.scrobble.ScrobbleSession
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * App-scope wiring of the scrobble coordinator: it follows the signed-in session, listens to the
 * playback service's event fan-out, and retries when a network returns. It lives in the application
 * so scrobbles are recorded with the screen off. [scope] must be a main-thread scope.
 */
class ScrobbleHost(context: Context, private val scope: CoroutineScope, sessions: SessionRepository,
    quiet: dev.coda.android.playback.QuietPlayback? = null, gate: dev.coda.core.connectivity.ServerGate? = null) {
    /** The durable outbox (a sign-in as another account keeps only that account's entries). */
    val outbox = ScrobbleOutbox(File(context.applicationInfo.dataDir, "files/scrobbles.json"))
    // With the screen off no now-playing; while screen off and playing completed plays wait (persisted) for the
    // next queue save (PlaybackService calls flushBatch) or the screen turning on / playback stopping.
    val coordinator = ScrobbleCoordinator(scope, outbox,
        sendsNowPlaying = { quiet == null || dev.coda.core.playback.BackgroundWorkPolicy.sendsNowPlaying(quiet.screenOn.value) },
        defersSubmissions = { quiet?.quiet?.value == true }, gate = gate)
    private var activeToken: UUID? = null

    init {
        // Additive: other coordinators (handoff, album resume) register their own sinks the same way.
        PlaybackDependencies.eventSinks = PlaybackDependencies.eventSinks + { coordinator.onEvent(it) }
        scope.launch {
            sessions.state.collect { state ->
                val session = (state as? SessionState.Connected)?.session
                if (session?.token == activeToken) return@collect
                activeToken = session?.token
                coordinator.setSession(session?.let(::scrobbleSession))
            }
        }
        quiet?.let { q -> scope.launch { q.quiet.collect { if (!it) coordinator.flushBatch() } } }
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { scope.launch { coordinator.onNetworkAvailable() } }
        })
    }

    /** Sign-out: try to send the unsent plays while the login still works; takes at most [SIGN_OUT_FLUSH_MS], failures keep them. */
    suspend fun flushForSignOut() {
        try { kotlinx.coroutines.withTimeoutOrNull(SIGN_OUT_FLUSH_MS) { coordinator.flushNow() } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { /* the entries stay */ }
    }

    private fun scrobbleSession(session: Session) = ScrobbleSession(
        session.client.configuration.let { accountKey(it.serverUrl, it.username) }, { id, submission, time ->
        session.client.scrobble(id, submission, time)
    }, session.token)

    companion object {
        const val SIGN_OUT_FLUSH_MS = 10_000L

        /** A one-way key for "this account on this server"; the outbox never stores the address or the name. */
        fun accountKey(serverUrl: String, username: String): String =
            MessageDigest.getInstance("SHA-256").digest("$serverUrl\n$username".toByteArray())
                .take(8).joinToString("") { "%02x".format(it) }
    }
}
