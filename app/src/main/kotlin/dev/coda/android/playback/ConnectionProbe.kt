package dev.coda.android.playback

import dev.coda.core.playback.PlaybackController
import dev.coda.core.subsonic.SubsonicError
import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * One recovery probe per due retry, on the controller's thread. The session and recovery target
 * are captured with the request; a result that arrives after either changed is discarded, so a stale
 * Unauthorized can neither stop newer playback nor invalidate a newer login.
 */
internal class ConnectionProbe(
    private val controller: PlaybackController,
    private val session: () -> Session?,
    private val unauthorized: suspend (UUID) -> Unit,
    private val nowMs: () -> Long,
) {
    class Session(val token: UUID, val ping: suspend () -> Unit)

    suspend fun runIfDue() {
        val waiting = controller.recovery.waiting ?: return
        if (!controller.recovery.due(nowMs())) return
        val requested = session()
        if (requested == null) { controller.probeFailed(); return }
        fun sameSession() = session()?.token == requested.token
        fun sameTarget() = controller.recovery.waiting?.target == waiting.target
        try {
            requested.ping()
            if (sameSession()) controller.probeSucceeded(waiting.target)
        } catch (e: CancellationException) { throw e }
        catch (_: SubsonicError.Unauthorized) {
            if (!sameSession() || !sameTarget()) return
            controller.stop()
            unauthorized(requested.token)
        } catch (_: Exception) {
            if (sameSession() && sameTarget() && controller.recovery.waiting?.retryAtMs == waiting.retryAtMs) controller.probeFailed()
        }
    }
}
