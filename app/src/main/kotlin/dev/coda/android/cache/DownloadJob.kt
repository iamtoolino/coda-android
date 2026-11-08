package dev.coda.android.cache

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dev.coda.android.CodaApp
import dev.coda.core.cache.DownloadLines
import dev.coda.core.cache.DownloadState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Kept-set downloads and "Load for offline" run as an Android 14+ user-initiated data transfer job, scheduled
 * only while work is pending, with one low-importance ongoing notification for the whole download queue. The downloads
 * themselves run in [OfflineLibrary]; the job keeps the process running (screen off, app in the background) and ends
 * when nothing is pending. Its network constraint is the Wi-Fi rule of the adds (CacheFormatPolicy.addsDownload): an
 * unmetered Wi-Fi or Ethernet network, without requiring a validated internet connection (a local server needs none).
 * The system's NETWORK_TYPE_UNMETERED would also require VALIDATED and so never start on such a LAN.
 * While a "Load for offline" is pending the job takes any network (it runs on mobile data too).
 */
object DownloadJobs {
    const val JOB_ID = 0x0C0DA001
    private const val TAG = "CodaDownloads"

    /**
     * Schedules the job unless it is already scheduled or running. A user-initiated job can only be scheduled while the
     * app is visible; elsewhere (a process started in the background) this fails quietly and the next visible moment
     * (an add, the app coming to the front) schedules it.
     */
    fun ensure(context: Context, anyNetwork: Boolean = false): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        val pending = scheduler.getPendingJob(JOB_ID) != null
        if (!needsSchedule(pending, pending && anyNetworkJob(context), DownloadJobService.running.value, anyNetwork)) return true
        return schedule(context, scheduler, anyNetwork)
    }

    /**
     * Whether [ensure] (re)schedules the job. A Wi-Fi-only job (waiting or running) is replaced when a "Load for
     * offline" needs any network. An any-network job left over from a load that is over (the in-job swap back
     * can be refused in the background) is replaced by a Wi-Fi-only one at the next visible moment, unless it runs.
     */
    internal fun needsSchedule(pending: Boolean, pendingAnyNetwork: Boolean, running: Boolean, anyNetwork: Boolean) = when {
        !pending -> true
        anyNetwork -> !pendingAnyNetwork
        else -> pendingAnyNetwork && !running
    }

    /** The scheduled (or running) job takes any network, not only unmetered Wi-Fi. */
    fun anyNetworkJob(context: Context) = context.getSystemService(JobScheduler::class.java)?.getPendingJob(JOB_ID)
        ?.requiredNetwork?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false

    /** Schedules (or replaces) the job with the network rule [anyNetwork]. */
    internal fun schedule(context: Context, scheduler: JobScheduler, anyNetwork: Boolean): Boolean {
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, DownloadJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetwork(network(anyNetwork))
            .build()
        return try {
            (scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS).also { if (!it) android.util.Log.i(TAG, "Download job not scheduled") }
        } catch (e: Exception) {
            android.util.Log.i(TAG, "Download job not scheduled: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Unmetered Wi-Fi or Ethernet, as [dev.coda.core.cache.CacheFormatPolicy.addsDownload]; not VALIDATED, not INTERNET.
     * [anyNetwork] (a "Load for offline" pending): any connected network, mobile data and hotspots too.
     */
    internal fun network(anyNetwork: Boolean = false): NetworkRequest = if (anyNetwork) NetworkRequest.Builder().build()
        else NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            .build()

    /** Cancels a job that is waiting to start when there is nothing to download any more (a running job ends itself). */
    fun cancelIfIdle(context: Context) {
        if (DownloadJobService.running.value) return
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(JOB_ID) != null) scheduler.cancel(JOB_ID)
    }

    fun scheduled(context: Context) = context.getSystemService(JobScheduler::class.java)?.getPendingJob(JOB_ID) != null
}

/** The one download notification: "Downloading 23 of 120", the current album or playlist, a bar and Cancel. */
object DownloadNotifications {
    const val CHANNEL = "downloads"
    const val ID = 0x0C0DA001
    private var shown: String? = null

    private fun channel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of offline downloads"
                setShowBadge(false)
            })
    }

    fun build(context: Context, state: DownloadState): Notification {
        channel(context)
        val cancel = PendingIntent.getBroadcast(context, 0, Intent(context, DownloadCancelReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(DownloadLines.notification(state))
            .setContentText(state.currentTitle)
            // Only kept covers left has no count: an indeterminate bar.
            .setProgress(state.total.coerceAtLeast(1), state.done, !state.active)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .build()
    }

    /**
     * Shows, updates or removes the notification for [state]. It shows while work is pending and runs (the job), waits
     * for Wi-Fi, or still makes progress; it goes when nothing is pending, or when the job gave up on a stalled run
     * (it retries later on its own).
     */
    @Synchronized fun update(context: Context, state: DownloadState, jobRunning: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        // The job's last kept covers (no audio pending) keep the notification it runs under.
        if (!state.active && state.coversPending && jobRunning && shown != null) return
        val show = state.active && (jobRunning || state.waitingForWifi || (!state.stalled && !state.paused))
        val key = if (show) "${DownloadLines.notification(state)}|${state.currentTitle}|${state.done}|${state.total}" else null
        if (key == shown) return
        shown = key
        if (key == null) manager.cancel(ID)
        else try { manager.notify(ID, build(context, state)) } catch (_: SecurityException) { /* notifications not allowed */ }
    }

    /**
     * Whether the notification stays (detached) when the job ends: only while audio is pending and the job is
     * rescheduled. A job rescheduled for kept covers alone removes it (nothing "downloads" in between; the
     * next run posts its own), so no ongoing notification lingers while the phone is off Wi-Fi.
     */
    fun keepsNotification(state: DownloadState, reschedule: Boolean) = reschedule && state.active

    /** The notification permission was just granted: post again what the system refused before (the key was remembered). */
    fun permissionGranted(context: Context) {
        val app = context.applicationContext as CodaApp
        synchronized(this) { shown = null }
        update(context, app.offlineLibrary.value?.downloads?.value ?: DownloadState(), DownloadJobService.running.value)
    }
}

/** The job: runs while downloads are pending, then ends. The notification stays attached while it runs. */
class DownloadJobService : JobService() {
    private var scope: CoroutineScope? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as CodaApp
        dev.coda.android.diag.BatteryLog.count(dev.coda.android.diag.BatteryIds.DL1_JOB_RUNS); dev.coda.android.diag.BatteryLog.event(dev.coda.android.diag.BatteryIds.EV_JOB_START); dev.coda.android.diag.BatteryLog.lock(dev.coda.android.diag.BatteryIds.LOCK_DL1_JOB, true)
        app.transferGate.jobStarted()
        running.value = true
        // A user-initiated job must post its notification right away.
        setNotification(params, DownloadNotifications.ID,
            DownloadNotifications.build(this, app.offlineLibrary.value?.downloads?.value ?: DownloadState()),
            JOB_END_NOTIFICATION_POLICY_DETACH)
        val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        jobScope.launch {
            // After process death the library appears once the saved session is restored; its persisted work is
            // published when it is created (OfflineLibrary), so the first state seen is the real one.
            var library = withTimeoutOrNull(LIBRARY_WAIT_MS) { app.offlineLibrary.filterNotNull().first() }
            if (library == null) { finish(params, false); return@launch }
            library.poke()
            var blockedSince: Long? = null
            while (isActive) {
                // Bound to the current session: sign-out or another account ends the job; a new library takes over its work.
                val current = app.offlineLibrary.value
                if (current == null) { finish(params, false); return@launch }
                if (current !== library) { library = current; library.poke(); blockedSince = null }
                val state = library.downloads.value
                // Kept covers left that cannot transfer now (Wi-Fi lost) wait like audio does, then reschedule.
                val coversWaiting = !state.active && !state.coversPending && state.coversDue
                if (!state.active && !state.coversPending && !coversWaiting) { finish(params, false); return@launch }
                val now = System.nanoTime() / 1_000_000
                val waiting = state.waitingForWifi || coversWaiting
                // Waiting for Wi-Fi or stalled (server errors, no room): give the slot back and let the system retry.
                if (waiting || state.stalled) {
                    val since = blockedSince ?: now.also { blockedSince = it }
                    if (now - since >= if (waiting) WIFI_GRACE_MS else STALL_GRACE_MS) {
                        // A job that took any network for a "Load for offline" that is over, with kept sets left
                        // waiting for Wi-Fi: replaced by a Wi-Fi-only job (this one stops); if the system refuses, it is
                        // rescheduled as it is, below.
                        if (waiting && state.queue == null && DownloadJobs.anyNetworkJob(this@DownloadJobService) &&
                            DownloadJobs.schedule(this@DownloadJobService, getSystemService(JobScheduler::class.java), anyNetwork = false)) return@launch
                        // A stalled run yields its slot: no more attempts until the system starts the job again.
                        if (!waiting) app.transferGate.jobStopped()
                        finish(params, true); return@launch
                    }
                } else blockedSince = null
                withTimeoutOrNull(CHECK_MS) {
                    @OptIn(ExperimentalCoroutinesApi::class)
                    app.offlineLibrary.flatMapLatest { lib -> lib?.downloads?.map { lib to it } ?: flowOf(null to DownloadState()) }
                        .first { (lib, value) -> lib !== library || value != state }
                }
            }
        }
        return true
    }

    /**
     * The notification stays attached ("Waiting for Wi-Fi") only while the job is rescheduled; when it ends for good the
     * system removes it with the job (a detached notification could not be cancelled reliably from the app).
     */
    private fun leaveNotification(params: JobParameters, reschedule: Boolean) {
        val state = (application as CodaApp).offlineLibrary.value?.downloads?.value ?: DownloadState()
        try {
            setNotification(params, DownloadNotifications.ID, DownloadNotifications.build(this, state),
                if (DownloadNotifications.keepsNotification(state, reschedule)) JOB_END_NOTIFICATION_POLICY_DETACH else JOB_END_NOTIFICATION_POLICY_REMOVE)
        } catch (_: RuntimeException) { /* the job already ended */ }
    }

    private fun finish(params: JobParameters, reschedule: Boolean) {
        leaveNotification(params, reschedule)
        running.value = false
        dev.coda.android.diag.BatteryLog.event(dev.coda.android.diag.BatteryIds.EV_JOB_FINISH, if (reschedule) 1 else 0); dev.coda.android.diag.BatteryLog.lock(dev.coda.android.diag.BatteryIds.LOCK_DL1_JOB, false)
        jobFinished(params, reschedule)
        scope?.cancel(); scope = null
    }

    /**
     * The system stops the job (constraints lost, timeout, thermal): execution must stop, visible or not. The transfer
     * gate (CodaApp.transferGate) closes until the job runs again, so the running transfer stops now; pending
     * work is kept and the job is rescheduled while some is left, kept covers included.
     */
    override fun onStopJob(params: JobParameters): Boolean {
        val app = application as CodaApp
        val more = app.offlineLibrary.value?.downloads?.value?.let { it.active || it.coversDue } == true
        leaveNotification(params, more)
        // Before running turns false, so the gate never opens for the app being visible in between.
        app.transferGate.jobStopped()
        running.value = false
        dev.coda.android.diag.BatteryLog.event(dev.coda.android.diag.BatteryIds.EV_JOB_STOP, 1); dev.coda.android.diag.BatteryLog.lock(dev.coda.android.diag.BatteryIds.LOCK_DL1_JOB, false)
        scope?.cancel(); scope = null
        return more
    }

    companion object {
        /** Whether the job runs now (the notification follows it). */
        val running = MutableStateFlow(false)
        private const val LIBRARY_WAIT_MS = 30_000L
        private const val CHECK_MS = 5_000L
        private const val WIFI_GRACE_MS = 30_000L
        private const val STALL_GRACE_MS = 120_000L
    }
}

/** The notification's Cancel: every pending download stops; what is on the phone and the kept marks stay. */
class DownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as CodaApp
        val result = goAsync()
        app.scope.launch {
            try {
                val library = withTimeoutOrNull(8_000) { app.offlineLibrary.filterNotNull().first() }
                if (library != null) library.cancelDownloads()
                else DownloadNotifications.update(app, DownloadState(), jobRunning = false)
                app.getSystemService(JobScheduler::class.java)?.cancel(DownloadJobs.JOB_ID)
            } finally { result.finish() }
        }
    }
}
