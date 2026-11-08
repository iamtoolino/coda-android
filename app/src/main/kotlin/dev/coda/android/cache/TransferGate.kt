package dev.coda.android.cache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * When downloads may transfer. They may while the app is [visible] or the user-initiated job runs, except after the
 * system stopped the job (or the job gave up on a stalled run): then they stop, visible or not, until the job is
 * rescheduled and starts again ([jobStarted]), the app comes to the front again ([cameToFront]) or the user asks for
 * downloads ([userAskedForWork]: Keep offline, Load for offline). Work that merely reappears (Wi-Fi back for
 * replacement-only work) never ends the hold. Cancel (the notification's) does not use the gate; it empties the work.
 */
class TransferGate(scope: CoroutineScope, jobRunning: Flow<Boolean>) {
    private val shown = MutableStateFlow(false)
    /** The app is on screen (also drives the cache maintenance ticker). */
    val visible: StateFlow<Boolean> get() = shown
    private val stopped = MutableStateFlow(false)
    val allowed: StateFlow<Boolean> = combine(shown, jobRunning, stopped) { on, job, held -> (on || job) && !held }
        .stateIn(scope, SharingStarted.Eagerly, false)

    fun setVisible(on: Boolean) { shown.value = on }
    /** The app came to the front (a new visible moment): a stop from the background no longer holds. */
    fun cameToFront() { shown.value = true; stopped.value = false }
    /** The system stopped the job, or it yielded after a stall: transfers wait for the job to run again. */
    fun jobStopped() { stopped.value = true }
    /** The (rescheduled) job started. */
    fun jobStarted() { stopped.value = false }
    /** An explicit user action added downloads. */
    fun userAskedForWork() { stopped.value = false }
}

/**
 * What a change of the download state does. The job is scheduled when work appears and cancelled when none is
 * left; this is separate from the gate: a state change never ends a system-stop hold ([TransferGate]).
 * [anyNetwork] (a "Load for offline" is pending) asks for the job on any network, also when work was already pending.
 */
class DownloadSchedule(private val ensureJob: (anyNetwork: Boolean) -> Unit, private val cancelJobIfIdle: () -> Unit) {
    private var wasActive = false
    private var wasAnyNetwork = false
    fun onState(active: Boolean, hasLibrary: Boolean, anyNetwork: Boolean = false) {
        if (active && (!wasActive || anyNetwork && !wasAnyNetwork)) ensureJob(anyNetwork)
        // Nothing pending any more (Cancel, the last item removed, everything done): no job stays scheduled.
        // Not before the library exists: a job that restarted the process waits for the saved session.
        else if (!active && hasLibrary) cancelJobIfIdle()
        wasActive = active
        wasAnyNetwork = active && anyNetwork
    }
}
