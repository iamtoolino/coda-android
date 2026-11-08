package dev.coda.core.connectivity

import dev.coda.core.subsonic.SubsonicError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one connectivity state, shared by the library, cache expiry, playback and the gate.
 * It is fed by the network ([network], [networkChanged]), by the outcome of every server request ([succeeded],
 * [failed]; [answered] classifies a Subsonic error) and by its own probe (`ping`, [PROBE_TIMEOUT_MS]). It is the only
 * owner of that probe.
 *
 * - [Link.OFFLINE] (automatic): no network for [OFFLINE_AFTER_MS] (a handover blip is shorter and changes nothing).
 * - [Link.UNREACHABLE] (the offer): a network, but two failures at least [CONFIRM_MS] apart with no success between (the
 *   first failure schedules the probe that may be the second), or a network the system marks unusable (captive portal)
 *   for [OFFLINE_AFTER_MS]. Held at least [OFFER_MIN_MS] (no blinking).
 * - [Link.OFFLINE_CHOSEN]: the user took the offer ([chooseOffline]).
 * - Back to [Link.ONLINE] from any of them: two successes at least [BACK_MS] apart (a network returning without a server
 *   answer changes nothing). A success schedules the probe that may be the second.
 *
 * Probe schedule (battery rules win): never without a usable network; never when a server answer already
 * settled the question (a success clears the online probes, and offline it is itself the first of the two); online only
 * after a failure, on a network change ([SETTLE_MS] settle, unless an answer came after the change) and on [refresh].
 * Offline or unreachable: [SETTLE_MS] after a network change, then [BACK_STEPS_MS] (5, 10, 20, 40, 60 s, the cap), but
 * only while Coda is [attended] (visible or playing). With the screen off and playing ([quiet]) a probe also waits for the
 * shared [ServerGate] backoff, and a failed one moves it (30 s doubling to 10 min instead of the 60 s cap), so it goes out
 * in the same round as the pending save and scrobbles; the screen coming on ends that wait. Idle (neither visible nor
 * playing) the back timer stops: no probe at all until Coda is attended again (then one at once, the schedule restarting),
 * a network change or a server answer. The way back never stops dead: offline with a usable network, while attended,
 * there is always a probe scheduled or running.
 *
 * A failure counts toward the offer only while Coda is [attended], the network is usable and not [blocked] for
 * Coda (Doze, app standby, Data Saver, the background block), and [GRACE_MS] after coming back (to the front, also
 * while playing ([visible]), or out of a block). Going idle, a block or a network change ends an open run. Only a
 * failing probe (a fresh ping started at least [CONFIRM_MS] after the first failure, on the same network) confirms; a
 * failing request that late asks for that ping. [log] (debug and "-pre" builds) gets one line per failure, counted or
 * why not.
 *
 * Time comes from [nowMs] and coroutine delays, so tests run in virtual time.
 */
class Reachability(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    /** The probe (`ping`): true, the server answered; false, it did not; null, nothing to ask (no session). */
    private val probe: suspend () -> Boolean?,
    /** the one "screen off and playing" policy. */
    private val quiet: StateFlow<Boolean> = MutableStateFlow(false),
    initial: Network = Network.USABLE,
    /** Coda is visible (any activity started) or playing; idle, the back schedule's timer does not run. */
    private val attended: StateFlow<Boolean> = MutableStateFlow(true),
    /** One diagnostics line per failure (no host); null in release builds. */
    private val log: ((String) -> Unit)? = null,
    /** Coda is in the front (an activity started); null, [attended] stands in. */
    private val visible: StateFlow<Boolean>? = null,
) : Connectivity {
    enum class Link { ONLINE, OFFLINE, UNREACHABLE, OFFLINE_CHOSEN }
    /** The default network: none, present but unusable (no INTERNET, or a captive portal), usable. */
    enum class Network { NONE, BLOCKED, USABLE }

    /** [known]: some evidence has arrived (a verdict, not the optimistic start). */
    data class State(val link: Link = Link.ONLINE, val known: Boolean = false) {
        val reachable: Boolean get() = link == Link.ONLINE
        /** The "Show what's on the phone" offer may be shown. */
        val offerShown: Boolean get() = link == Link.UNREACHABLE
        val chosen: Boolean get() = link == Link.OFFLINE_CHOSEN
    }

    /** Set once after construction: the gate whose backoff the probe follows while [quiet]. */
    @Volatile var gate: ServerGate? = null

    private val lock = Any()
    private var net = initial
    private var netSince = nowMs()
    private var link = Link.ONLINE
    private var known = false
    private var lastSuccessAt: Long? = null
    private var lastProbeAt: Long? = null
    /** Answers before the last network change do not stand in for its probe. */
    private var changeAt = Long.MIN_VALUE
    /** Online: the first failure of a run with no success since. */
    private var failureAt: Long? = null
    /** Offline: the first success of a run with no failure since. */
    private var streakAt: Long? = null
    private var unreachableSince = 0L
    private var backStep = 0
    private var probeAt: Long? = null
    /** [probeAt] is the back schedule's timer (deferred while idle), not a probe a network change, answer or caller asked for. */
    private var timerProbe = false
    /** Idle dropped a back-schedule probe: it goes out when Coda is attended again. */
    private var deferred = false
    /** A probe started before [reset] reports into the old session: ignored. */
    private var epoch = 0
    private var startedEpoch = 0
    private var probing = false
    private var refreshing = false
    /** The probe's own request also reports its failure ([failed]); the entry it caused already set the schedule. */
    private var enteredDuringProbe = false
    /** Android blocks Coda's network ([blocked]). */
    private var isBlocked = false
    /** The last [attended] value seen (null before the first); only a change to attended starts a grace. */
    private var wasAttended: Boolean? = null
    /** Failures before this time (just back to the front or out of a block) do not count. */
    private var graceUntil = Long.MIN_VALUE
    /** The last [visible] value seen (null before the first); a return to the front starts a grace even while playing. */
    private var wasVisible: Boolean? = null
    /** Bumped by every network change; a probe from an earlier network says nothing about this one. */
    private var netGeneration = 0
    /**
     * The running probe: when it started (or was due, if later: the wait runs on another clock and can end 1 ms
     * early on this one), and on which network ([netGeneration]).
     */
    private var probeStartedAt = Long.MIN_VALUE
    private var probeGeneration = 0
    private var netTimer: Job? = null
    private var holdTimer: Job? = null

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()
    private val mutableOnline = MutableStateFlow(initial == Network.USABLE)
    /** Any default network, usable or not (search and page fallbacks: "no network" as before, instantly). */
    val hasNetwork: Boolean get() = synchronized(lock) { net != Network.NONE }
    /** A usable network (the gate's [Connectivity]). */
    override val online: StateFlow<Boolean> = mutableOnline.asStateFlow()
    private val next = MutableStateFlow<Long?>(null)
    /** A verdict [refresh] waits for: a probe running, or online with a failure not yet confirmed. */
    private val unsettled = MutableStateFlow(false)

    init {
        if (initial != Network.USABLE) startNetTimer()
        scope.launch { probeLoop() }
        scope.launch { attended.collect { attendedChanged(it) } }
        if (visible != null) scope.launch { visible.collect { visibleChanged(it) } }
    }

    /** Production's [attended] stays true while playing, so a return to the front is seen here (grace only). */
    private fun visibleChanged(now: Boolean) = synchronized(lock) {
        val before = wasVisible
        wasVisible = now
        if (before == false && now) graceUntil = nowMs() + GRACE_MS
    }

    private fun attendedChanged(now: Boolean) = synchronized(lock) {
        val before = wasAttended
        wasAttended = now
        if (before == false && now) graceUntil = nowMs() + GRACE_MS
        if (link == Link.ONLINE) {
            // Going idle ends an open run (a failure from before a sleep must not confirm after it).
            if (!now) { endRun(); publish() }
            return
        }
        if (!now) {
            // Going idle: a pending back-schedule probe is dropped now (no wake-up for it), not when it is due.
            if (probeAt != null && timerProbe) { probeAt = null; timerProbe = false; deferred = true; publish() }
        } else if (deferred) {
            deferred = false
            if (net == Network.USABLE && probeAt == null && !probing) { backStep = 0; probeAt = nowMs(); publish() }
        }
    }

    /**
     * Another account or server (or signed out): the old session's answers say nothing about the new one. The state
     * starts again as at app start, keeping only the network.
     */
    fun reset() = synchronized(lock) {
        epoch++
        holdTimer?.cancel()
        link = Link.ONLINE; known = false
        lastSuccessAt = null; lastProbeAt = null; changeAt = Long.MIN_VALUE
        failureAt = null; streakAt = null; backStep = 0
        probeAt = null; timerProbe = false; deferred = false
        probing = false; refreshing = false; enteredDuringProbe = false
        if (net != Network.USABLE) evaluateNetwork(nowMs())
        publish()
    }

    fun network(value: Network) = synchronized(lock) {
        if (value == net) return
        val now = nowMs()
        val was = net
        net = value
        if (value == Network.USABLE) {
            netTimer?.cancel()
            if (was != Network.USABLE) changed(now)
        } else {
            // No probe and no failure counts without a network (a request failing for lack of one says nothing).
            failureAt = null; probeAt = null; refreshing = false
            if (was == Network.USABLE) { netSince = now; startNetTimer() }
            else evaluateNetwork(now)
        }
        publish()
    }

    /**
     * Android blocks (true) or allows Coda's network (`onBlockedStatusChanged`). A block ends an open run; the
     * unblock starts a [GRACE_MS] grace (the first requests after it still fail).
     */
    fun blocked(value: Boolean) = synchronized(lock) {
        if (value == isBlocked) return
        isBlocked = value
        if (value) { if (link == Link.ONLINE) endRun() } else graceUntil = nowMs() + GRACE_MS
        publish()
    }

    /**
     * Online: forget an open failure run and any pending probe (its confirmation, or the one a failure in the grace
     * asked for); a refresh keeps its probe.
     */
    private fun endRun() {
        failureAt = null
        if (!refreshing) { probeAt = null; timerProbe = false }
    }

    /** Another default network, or the same one usable again. */
    fun networkChanged() = synchronized(lock) { if (net == Network.USABLE) { changed(nowMs()); publish() } }

    private fun changed(now: Long) {
        changeAt = now
        netGeneration++
        // A VPN rebuild or a Wi-Fi/VPN flip starts a fresh run.
        failureAt = null
        backStep = 0
        probeAt = now + SETTLE_MS; timerProbe = false; deferred = false
    }

    /** Any server answer: a Subsonic answer (also 401/404), a cover or a stream. */
    fun succeeded() = synchronized(lock) {
        val now = nowMs()
        lastSuccessAt = now
        succeededLocked(now)
        publish()
    }

    /** A server request failed: network error, timeout, 5xx or a non-Subsonic answer (a hotel login page). */
    fun failed() = synchronized(lock) { failure(nowMs(), probe = false); publish() }

    /** The [reset] epoch now; a request takes it when it starts and reports with it ([succeeded], [failed]). */
    val epochNow: Int get() = synchronized(lock) { epoch }

    /** [succeeded] for a request started in epoch [issued]; false (nothing done): it belongs to a session before [reset]. */
    fun succeeded(issued: Int): Boolean = synchronized(lock) { (issued == epoch).also { if (it) succeeded() } }

    /** [failed] for a request started in epoch [issued]; dropped when it belongs to a session before [reset]. */
    fun failed(issued: Int) = synchronized(lock) { if (issued == epoch) failed() }

    /** The user took the offer. */
    fun chooseOffline() = synchronized(lock) {
        if (link != Link.UNREACHABLE) return
        link = Link.OFFLINE_CHOSEN
        holdTimer?.cancel()
        // A hold in progress had no probe pending: the way back goes on from the chosen state.
        scheduleBack(nowMs())
        publish()
    }

    /**
     * A fresh verdict for a caller that must not act on a stale one (cache expiry before expiry: [maxAgeMs] 5 min;
     * the sign-out dialog: 0, a probe now). Online with an answer or a probe younger than [maxAgeMs]: nothing is sent.
     * Offline the back schedule is already running; only [maxAgeMs] 0 adds a probe. Waits for the verdict, at most
     * [REFRESH_WAIT_MS]; on timeout it returns and [state] is whatever it is then.
     */
    suspend fun refresh(maxAgeMs: Long) {
        synchronized(lock) {
            if (net != Network.USABLE) return
            val now = nowMs()
            if (maxAgeMs > 0) {
                if (link != Link.ONLINE) return
                val answer = lastSuccessAt?.takeIf { it >= changeAt }
                val evidence = listOfNotNull(answer, lastProbeAt).maxOrNull()
                if (evidence != null && now - evidence < maxAgeMs) return
            }
            refreshing = true
            probeAt = minOf(probeAt ?: now, now); timerProbe = false
            publish()
        }
        withTimeoutOrNull(REFRESH_WAIT_MS) { unsettled.first { !it } }
    }

    private fun failure(now: Long, probe: Boolean) {
        val ignored = when {
            net != Network.USABLE -> "no-net"
            link != Link.ONLINE -> null
            isBlocked -> "blocked"
            !attended.value -> "idle"
            // A ping sent on the previous network; the change already scheduled its own settle probe.
            probe && probeGeneration != netGeneration -> "stale"
            now < graceUntil -> "grace"
            else -> null
        }
        log?.invoke("reach failure " + (if (ignored == null) "counted" else "ignored $ignored") +
            (if (probe) " probe" else " request") +
            " sinceChange=" + (if (changeAt == Long.MIN_VALUE) "-" else "${now - changeAt}ms") +
            " attended=${attended.value} blocked=$isBlocked link=$link")
        if (ignored == "grace") {
            // The question stays open: the server is asked once the grace is over.
            probeAt = minOf(probeAt ?: Long.MAX_VALUE, graceUntil); timerProbe = false
        }
        if (ignored != null) return
        when (link) {
            Link.ONLINE -> {
                val first = failureAt
                if (first == null) { failureAt = now; probeAt = minOf(probeAt ?: Long.MAX_VALUE, now + CONFIRM_MS); timerProbe = false }
                // Only a fresh ping (started at least CONFIRM_MS after the first failure) confirms; an earlier one
                // (a refresh) asks for that ping instead, at once if its time has come.
                else if (probe) {
                    if (probeStartedAt - first >= CONFIRM_MS) enter(Link.UNREACHABLE, now)
                    else { probeAt = maxOf(first + CONFIRM_MS, now); timerProbe = false }
                }
                // A request failing that late asks for the ping now.
                else if (now - first >= CONFIRM_MS) { probeAt = minOf(probeAt ?: now, now); timerProbe = false }
            }
            else -> {
                // The failure also invalidates a confirmed streak waiting for the hold's end.
                streakAt = null; holdTimer?.cancel()
                if (probe && enteredDuringProbe) enteredDuringProbe = false
                else if (probe) scheduleBack(now)
                // A request failing during the offer's hold (no probe pending then): the back schedule goes on.
                else scheduleBack(now)
            }
        }
    }

    /** Offline: the next back-schedule probe, unless one is pending or running; idle it waits for [attended]. */
    private fun scheduleBack(now: Long, duringProbe: Boolean = false) {
        if (link == Link.ONLINE || net != Network.USABLE || probeAt != null || (probing && !duringProbe)) return
        if (!attended.value) { deferred = true; return }
        probeAt = now + BACK_STEPS_MS[backStep]; timerProbe = true
        backStep = (backStep + 1).coerceAtMost(BACK_STEPS_MS.lastIndex)
    }

    private fun enter(to: Link, now: Long) {
        known = true
        if (to == link) return
        link = to
        failureAt = null; streakAt = null
        holdTimer?.cancel()
        if (to == Link.UNREACHABLE) {
            unreachableSince = now
            enteredDuringProbe = probing
            // Entered by the probe's own failure: that probe already counts, the schedule starts here.
            probeAt = null; backStep = 0
            scheduleBack(now, duringProbe = true)
        } else if (to == Link.OFFLINE) { probeAt = null; timerProbe = false }
    }

    private fun backOnline(now: Long) {
        val holdEnd = unreachableSince + OFFER_MIN_MS
        if (link == Link.UNREACHABLE && now < holdEnd) {
            // No blinking: the offer stays its minimum time; the run of successes decides at its end.
            if (holdTimer?.isActive != true) holdTimer = scope.launch {
                delay(holdEnd - now)
                synchronized(lock) {
                    if (!isActive || link != Link.UNREACHABLE) return@synchronized
                    // A failure during the hold cancels this timer; a replacement streak confirms on its own.
                    goOnline()
                    publish()
                }
            }
            probeAt = null; timerProbe = false
            return
        }
        goOnline()
    }

    private fun goOnline() {
        link = Link.ONLINE
        failureAt = null; streakAt = null; probeAt = null; timerProbe = false; deferred = false
        known = true
    }

    private fun startNetTimer() {
        netTimer?.cancel()
        val since = netSince
        netTimer = scope.launch {
            delay(OFFLINE_AFTER_MS)
            synchronized(lock) { if (netSince == since && net != Network.USABLE) { evaluateNetwork(nowMs()); publish() } }
        }
    }

    /**
     * The network has been missing or unusable for [OFFLINE_AFTER_MS] (or changed between the two after that). The result
     * depends only on the network, not on the state before: none is Offline (automatic), a captive portal or a network
     * without INTERNET is Server unreachable (the offer; PO question in docs/reports/OF-1b.md). A chosen offline stays chosen.
     */
    private fun evaluateNetwork(now: Long) {
        if (now - netSince < OFFLINE_AFTER_MS) return
        when {
            link == Link.OFFLINE_CHOSEN -> known = true
            net == Network.NONE -> enter(Link.OFFLINE, now)
            net == Network.BLOCKED -> enter(Link.UNREACHABLE, now)
            else -> known = true
        }
    }

    private fun publish() {
        mutableState.value = State(link, known)
        mutableOnline.value = net == Network.USABLE
        next.value = probeAt
        unsettled.value = probing || refreshing || (link == Link.ONLINE && failureAt != null)
    }

    private suspend fun probeLoop() {
        while (true) {
            val at = next.filterNotNull().first()
            val wait = at - nowMs()
            if (wait > 0 && withTimeoutOrNull(wait) { next.first { it != at } } != null) continue
            val gate = gate
            if (quiet.value && gate != null && !gate.isOpen() && net == Network.USABLE) {
                // With the screen off and playing the probe takes its turn in the shared backoff round. The
                // screen coming on (or playback stopping) ends the wait: the loop decides again, by the attended rules.
                val opened = merge(flow { gate.awaitOpen(); emit(true) }, quiet.filter { !it }.map { false }).first()
                // Someone looks again: the §4 schedule starts over (as after coming to the front), this probe first.
                if (!opened) { synchronized(lock) { backStep = 0 }; continue }
                if (!start(maxOf(at, nowMs()))) continue
            } else if (!start(at)) continue
            val started = startedEpoch
            // A timeout is a failure; Box(null) (no session, nothing asked) stays null.
            val outcome = try { withTimeoutOrNull(PROBE_TIMEOUT_MS) { Box(probe()) }.let { if (it == null) false else it.value } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { answered(e) ?: false }
            val now = nowMs()
            synchronized(lock) {
                if (epoch != started) return@synchronized
                probing = false
                when (outcome) {
                    true -> { lastSuccessAt = now; succeededLocked(now) }
                    false -> failure(now, probe = true)
                    null -> { failureAt = null }
                }
                publish()
            }
            if (outcome == false && quiet.value && synchronized(lock) { epoch == started }) gate?.failed()
        }
    }

    /** Online a success ends the failure run and the probes it wanted; offline it is the first of two, or the second. */
    private fun succeededLocked(now: Long) {
        known = true
        when (link) {
            Link.ONLINE -> { failureAt = null; probeAt = null; refreshing = false }
            else -> {
                val first = streakAt
                if (first == null) { streakAt = now; probeAt = now + BACK_MS; timerProbe = false }
                else if (now - first >= BACK_MS) backOnline(now)
                // Too early to be the second (a network change moved the probe forward): the confirmation stays due.
                else if (probeAt == null) { probeAt = first + BACK_MS; timerProbe = false }
            }
        }
    }

    /** Claims the probe due at [dueBy] or earlier; false when it went away (an answer arrived, the network left). */
    private fun start(dueBy: Long): Boolean = synchronized(lock) {
        val at = probeAt
        if (at == null || at > dueBy) return false
        if (net != Network.USABLE) { probeAt = null; refreshing = false; publish(); return false }
        if (timerProbe && link != Link.ONLINE && !attended.value) {
            // Idle: the back schedule waits for Coda to be visible or playing again.
            probeAt = null; timerProbe = false; deferred = true; publish(); return false
        }
        probeAt = null; timerProbe = false; probing = true; refreshing = false; enteredDuringProbe = false
        startedEpoch = epoch
        lastProbeAt = nowMs()
        probeStartedAt = maxOf(lastProbeAt!!, at); probeGeneration = netGeneration
        publish()
        true
    }

    private class Box(val value: Boolean?)

    companion object {
        const val OFFLINE_AFTER_MS = 2_000L
        /** Was 3 s; longer than a TCP SYN retry cycle and a typical tunnel wake-up. */
        const val CONFIRM_MS = 10_000L
        const val BACK_MS = 2_000L
        const val SETTLE_MS = 1_000L
        const val OFFER_MIN_MS = 5_000L
        /** Was 6 s; matches the HTTP client's 10 s connect/read timeouts. */
        const val PROBE_TIMEOUT_MS = 10_000L
        /** After coming back to the front or out of a block, failures don't count this long. */
        const val GRACE_MS = 5_000L
        val BACK_STEPS_MS = longArrayOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L)
        /**
         * [refresh] waits at most 15 s (as before OF-2), not for the offer's confirming ping: the sign-out dialog
         * and cache expiry then go on with the state as it is (online with an unconfirmed failure stays online).
         */
        const val REFRESH_WAIT_MS = 15_000L
        /** HTTP codes that only the server itself answers with (§4: 401/404; 410 Gone is a not-found). */
        private val SERVER_ANSWERS = setOf(401, 404, 410)

        /**
         * What a failed Subsonic request says about the server: true, it answered (HTTP 401, 404 or 410, a Subsonic error
         * envelope incl. wrong credentials and not found); false, it did not (network error or timeout, 5xx, a non-Subsonic
         * answer such as a captive portal page, and other HTTP codes such as 403/407 that a proxy or firewall in front of
         * the server sends); null, nothing was asked.
         */
        fun answered(error: Throwable): Boolean? = when (error) {
            is SubsonicError.InvalidConfiguration -> null
            is SubsonicError.Network, is SubsonicError.Decoding -> false
            is SubsonicError.Http -> error.status in SERVER_ANSWERS
            is SubsonicError.Unauthorized -> error.httpStatus != 403
            is SubsonicError.NotFound, is SubsonicError.Server -> true
            is java.io.IOException -> false
            else -> null
        }
    }
}
