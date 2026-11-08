package dev.coda.core.connectivity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether a network that can reach anything exists. The app reads the default network
 * (`NET_CAPABILITY_INTERNET` and not `NET_CAPABILITY_CAPTIVE_PORTAL`; not `VALIDATED`, so a LAN-only Wi-Fi or a blocked
 * validation probe never closes the gate) from callbacks only. Unknown (startup) counts as online.
 */
interface Connectivity {
    val online: StateFlow<Boolean>
}

/**
 * The one gate for background server requests (queue save and load, scrobbles, now-playing, the reachability ping,
 * album resume). Offline nothing is sent and a skipped request is not a failure ([onSkipped] counts it for the battery log).
 * Online but the server not answering (captive portal, server down, VPN not up yet): after a failed round the gate stays
 * closed for a shared backoff of 30 s, doubling, at most 10 min. A network change or any server answer resets it. While
 * the backoff runs, the callers' per-call retries are off ([retriesAllowed]): one attempt per round, so the pending save
 * and the scrobble outbox go out together when it ends.
 */
class ServerGate(
    private val connectivity: Connectivity,
    private val nowMs: () -> Long,
    private val onSkipped: (Kind) -> Unit = {},
) {
    enum class Kind { QUEUE_SAVE, QUEUE_LOAD, SCROBBLE, NOW_PLAYING, PING, ALBUM_RESUME }

    private val lock = Any()
    private var stepMs = 0L
    private val until = MutableStateFlow<Long?>(null)

    val online: Boolean get() = connectivity.online.value
    /** A network is up and no backoff runs. */
    fun isOpen(): Boolean = online && until.value.let { it == null || nowMs() >= it }
    /** Off once a round failed, until a reset: then a failure goes straight to the next backoff step. */
    val retriesAllowed: Boolean get() = synchronized(lock) { stepMs == 0L }
    /** Current backoff step (0: none); tests and the report. */
    val backoffMs: Long get() = synchronized(lock) { stepMs }

    /** [isOpen]; a request refused because there is no network is counted as skipped (a backoff refusal is not). */
    fun allows(kind: Kind): Boolean {
        if (!online) { onSkipped(kind); return false }
        return isOpen()
    }
    fun skipped(kind: Kind) = onSkipped(kind)

    /** The server did not answer. One step per round: callers failing in the same round do not double it twice. */
    fun failed() = synchronized(lock) {
        val now = nowMs()
        until.value?.let { if (now < it) return }
        stepMs = if (stepMs == 0L) INITIAL_BACKOFF_MS else minOf(stepMs * 2, MAX_BACKOFF_MS)
        until.value = now + stepMs
    }
    /** Any server answer ([dev.coda.android.net.ServerContact] in the app) or a network change. */
    fun reset() = synchronized(lock) { stepMs = 0L; until.value = null }

    /** Suspends until a network is up and the backoff that was running has ended (callbacks and one timer, no polling). */
    suspend fun awaitOpen() {
        while (true) {
            connectivity.online.first { it }
            val end = until.value ?: return
            val wait = end - nowMs()
            if (wait <= 0) return
            // Wait for a state that differs from the one read (not drop(1)): a reset or a lost network between the read and
            // the subscription is already in combine's first value and must wake us, not be thrown away.
            val changed = withTimeoutOrNull(wait) {
                combine(connectivity.online, until) { a, b -> a to b }.first { (up, u) -> !up || u != end }
            }
            // The backoff waited for ended: this round is ours, even if a caller of the same round just started the next step.
            if (changed == null && online) return
        }
    }

    companion object {
        const val INITIAL_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 10 * 60_000L
    }
}
