package dev.coda.android.cache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** A system stop of the job holds the transfers even while the app is visible. */
class TransferGateTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val job = MutableStateFlow(false)
    private val gate = TransferGate(scope, job)
    @After fun tearDown() = scope.cancel()

    @Test fun visibleOrTheJobRunningAllowsTransfers() {
        assertFalse(gate.allowed.value)
        gate.setVisible(true); assertTrue(gate.allowed.value)
        gate.setVisible(false); assertFalse(gate.allowed.value)
        job.value = true; assertTrue(gate.allowed.value)
    }

    @Test fun aSystemStopWhileVisibleHoldsTransfersUntilTheJobRunsAgain() {
        gate.cameToFront(); job.value = true
        assertTrue(gate.allowed.value)
        gate.jobStopped(); job.value = false          // the old rule (visible || running) stayed open here
        assertFalse("held although the app is visible", gate.allowed.value)
        gate.jobStarted(); job.value = true
        assertTrue("the rescheduled job runs", gate.allowed.value)
    }

    @Test fun theHoldEndsWhenTheAppComesToTheFrontOrTheUserAsksForDownloads() {
        gate.cameToFront(); gate.jobStopped(); assertFalse(gate.allowed.value)
        gate.setVisible(false); gate.cameToFront(); assertTrue(gate.allowed.value)
        gate.jobStopped(); assertFalse(gate.allowed.value)
        gate.userAskedForWork(); assertTrue(gate.allowed.value)
    }

    @Test fun aStopWhileTheJobRunsInTheBackgroundHoldsToo() {
        job.value = true; assertTrue(gate.allowed.value)
        gate.jobStopped(); assertFalse(gate.allowed.value)
        job.value = false; gate.setVisible(false); assertFalse(gate.allowed.value)
    }

    /** Review r3 P1: replacement-only work is pending again on Wi-Fi; that is no user request and no job restart. */
    @Test fun aSystemStopHoldSurvivesPendingWorkGoingToZeroOnMobileDataAndBackOnWifi() {
        var ensured = 0; var cancelled = 0
        val schedule = DownloadSchedule({ ensured++ }, { cancelled++ })
        gate.cameToFront(); job.value = true
        schedule.onState(active = true, hasLibrary = true); assertEquals(1, ensured)
        gate.jobStopped(); job.value = false
        assertFalse(gate.allowed.value)
        schedule.onState(active = false, hasLibrary = true)    // mobile data: nothing pending
        schedule.onState(active = true, hasLibrary = true)     // Wi-Fi again: pending again
        assertEquals("the job is scheduled again", 2, ensured); assertEquals(1, cancelled)
        assertFalse("still held: no job start, no user request", gate.allowed.value)
        gate.userAskedForWork(); assertTrue(gate.allowed.value)
    }

    /** A "Load for offline" asks for the job on any network, also while kept sets already have a Wi-Fi-only job. */
    @Test fun aQueueLoadAsksForTheJobOnAnyNetworkEvenWhenWorkWasAlreadyPending() {
        val ensured = mutableListOf<Boolean>()
        val schedule = DownloadSchedule({ ensured += it }, {})
        schedule.onState(active = true, hasLibrary = true)                     // a kept album waits for Wi-Fi
        schedule.onState(active = true, hasLibrary = true, anyNetwork = true)  // Load for offline on mobile data
        schedule.onState(active = true, hasLibrary = true, anyNetwork = true)  // progress: no new request
        schedule.onState(active = true, hasLibrary = true)                     // the load ended: the job yields by itself later
        assertEquals(listOf(false, true), ensured)
    }

    /** A leftover any-network job turns Wi-Fi-only when the app is visible, unless it runs or a load needs it. */
    @Test fun aLeftoverAnyNetworkJobIsRescheduledWifiOnlyUnlessItRunsOrALoadIsPending() {
        // Pending any-network job, no queue load: replaced by a Wi-Fi-only one.
        assertTrue(DownloadJobs.needsSchedule(pending = true, pendingAnyNetwork = true, running = false, anyNetwork = false))
        // Running: left alone (it swaps itself back once the load is over).
        assertFalse(DownloadJobs.needsSchedule(pending = true, pendingAnyNetwork = true, running = true, anyNetwork = false))
        // A queue load pending: stays any-network.
        assertFalse(DownloadJobs.needsSchedule(pending = true, pendingAnyNetwork = true, running = false, anyNetwork = true))
        // Unchanged: a Wi-Fi-only job stays for kept sets, is replaced for a load; no job: scheduled.
        assertFalse(DownloadJobs.needsSchedule(pending = true, pendingAnyNetwork = false, running = false, anyNetwork = false))
        assertTrue(DownloadJobs.needsSchedule(pending = true, pendingAnyNetwork = false, running = true, anyNetwork = true))
        assertTrue(DownloadJobs.needsSchedule(pending = false, pendingAnyNetwork = false, running = false, anyNetwork = false))
    }
}
