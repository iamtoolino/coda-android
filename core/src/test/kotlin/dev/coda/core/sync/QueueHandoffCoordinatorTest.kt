package dev.coda.core.sync

import dev.coda.core.playback.*
import dev.coda.core.subsonic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueHandoffCoordinatorTest {
    private fun remote(owner: String = "Coda on Mac") = PlayQueue(current = "b", currentIndex = 1,
        position = 12345, changedBy = owner, changed = "v1", entry = listOf(Song("a", "A"), Song("b", "B")))

    @Test fun cacheRestorationGateWaitsForSuccessfulLookupOrLocalOwnership() = runTest {
        val player = PlaybackController(FakeAudioEngine())
        val response = CompletableDeferred<PlayQueue?>()
        val coordinator = QueueHandoffCoordinator(player, backgroundScope, "Coda on Android", { response.await() }, {})
        advanceTimeBy(2000); runCurrent()
        assertFalse(coordinator.initialQueueResolved)
        response.complete(remote("Coda on Android")); runCurrent()
        assertTrue(coordinator.initialQueueResolved)
        assertEquals(listOf("a", "b"), player.queue.map { it.songId })
        coordinator.close()
        val failed = QueueHandoffCoordinator(PlaybackController(FakeAudioEngine()), backgroundScope,
            "Coda on Android", { throw java.io.IOException() }, {})
        advanceTimeBy(2000); runCurrent()
        assertFalse(failed.initialQueueResolved)
        failed.close()
    }

    @Test fun immediateCollectorDoesNotTreatRemoteRestoreAsLocalEdit() = runTest {
        val player = PlaybackController(FakeAudioEngine())
        val saves = mutableListOf<QueueHandoffSnapshot>()
        val immediateScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val coordinator = QueueHandoffCoordinator(player, immediateScope, "Coda on Android",
            { remote("Coda on Android") }, { saves += it })
        advanceTimeBy(251); runCurrent()
        assertEquals(1, player.currentIndex)
        assertEquals(12345L, player.positionMs)
        advanceTimeBy(60_000); runCurrent()
        assertTrue(saves.isEmpty())
        coordinator.close()
    }

    @Test fun ownRestoresPausedAndForeignRequiresAcceptance() = runTest {
        for (owner in listOf("Coda on Android", "Coda on Mac")) {
            val player = PlaybackController(FakeAudioEngine())
            val saves = mutableListOf<QueueHandoffSnapshot>()
            val coordinator = QueueHandoffCoordinator(player, backgroundScope, "Coda on Android", { remote(owner) }, { saves += it })
            advanceTimeBy(251); runCurrent()
            if (owner.endsWith("Mac")) {
                assertTrue(player.queue.isEmpty()); assertNotNull(coordinator.offer.value)
                coordinator.continuePlaying(coordinator.offer.value!!.handoffIdentity)
                assertTrue(player.isPlaying)
            } else { assertFalse(player.isPlaying); assertNull(coordinator.offer.value) }
            assertEquals(listOf("a", "b"), player.queue.map { it.songId })
            assertEquals(1, player.currentIndex); assertEquals(12345, player.positionMs)
            if (owner.endsWith("Android")) { advanceTimeBy(60_000); runCurrent(); assertTrue(saves.isEmpty()) }
            coordinator.close()
        }
    }

    /** A queue restored from the server keeps each song's ReplayGain (the gain the engine applies comes from it). */
    @Test fun aRestoredQueueKeepsReplayGain() = runTest {
        val rg = ReplayGainMetadata(trackGain = -4.5, albumGain = -6.0, trackPeak = 0.9, albumPeak = 0.95)
        val queue = remote("Coda on Android").copy(entry = listOf(Song("a", "A", replayGain = rg), Song("b", "B")))
        val player = PlaybackController(FakeAudioEngine())
        val coordinator = QueueHandoffCoordinator(player, backgroundScope, "Coda on Android", { queue }, { })
        advanceTimeBy(251); runCurrent()
        assertEquals(listOf(rg, null), player.queue.map { it.replayGain })
        coordinator.close()
    }

    @Test fun pauseWritesOnceAndPausedSeeksNeverOverwriteMac() = runTest {
        val player = PlaybackController(FakeAudioEngine())
        val saves = mutableListOf<QueueHandoffSnapshot>()
        val coordinator = QueueHandoffCoordinator(player, backgroundScope, "Coda on Android", { null }, { saves += it })
        runCurrent()
        player.replaceQueue(listOf(QueueEntry("a"), QueueEntry("b")))
        runCurrent(); advanceTimeBy(251); runCurrent()
        assertEquals(1, saves.size)
        player.seek(12345); player.pause(); runCurrent()
        assertEquals(2, saves.size); assertEquals(12345, saves.last().positionMilliseconds)
        player.seek(99999); runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals(2, saves.size)
        player.play(); runCurrent(); advanceTimeBy(501); runCurrent()
        assertEquals(3, saves.size)
        advanceTimeBy(15_000); runCurrent(); assertEquals(4, saves.size)
        coordinator.terminationSave()!!.invoke(); assertEquals(5, saves.size)
        coordinator.close()
    }

    @Test fun lateRefreshCannotReplaceLocalWorkAndDismissedVersionStaysHandled() = runTest {
        val player = PlaybackController(FakeAudioEngine())
        var queue = remote()
        val coordinator = QueueHandoffCoordinator(player, backgroundScope, "Coda on Android", { queue }, {})
        advanceTimeBy(251); runCurrent()
        coordinator.dismiss(queue.handoffIdentity)
        coordinator.refreshRemoteQueue(); runCurrent(); assertNull(coordinator.offer.value)
        queue = queue.copy(changed = "v2")
        coordinator.refreshRemoteQueue(); runCurrent(); assertNotNull(coordinator.offer.value)
        player.replaceQueue(listOf(QueueEntry("local")), startsPlaying = false); runCurrent()
        assertNull(coordinator.offer.value)
        coordinator.refreshRemoteQueue(); runCurrent(); assertNotNull(coordinator.offer.value)
        player.play(); runCurrent(); coordinator.refreshRemoteQueue()
        assertNull(coordinator.offer.value); assertEquals("local", player.currentEntry!!.songId)
        coordinator.close()
    }

    @Test fun undoDismissRevalidatesVersionAndDoesNotOverrideLocalPlayback() = runTest {
        val player = PlaybackController(FakeAudioEngine())
        var queue = remote()
        val coordinator = QueueHandoffCoordinator(player, backgroundScope, "Coda on Android", { queue }, {})
        advanceTimeBy(251); runCurrent()
        val identity = queue.handoffIdentity
        coordinator.dismiss(identity)
        coordinator.undoDismiss("unrelated"); runCurrent()
        assertNull(coordinator.offer.value)
        coordinator.undoDismiss(identity); runCurrent()
        assertEquals(queue, coordinator.offer.value)
        coordinator.dismiss(identity)
        queue = queue.copy(changed = "v2")
        coordinator.undoDismiss(identity); runCurrent()
        assertEquals(queue, coordinator.offer.value)
        coordinator.dismiss(queue.handoffIdentity)
        player.replaceQueue(listOf(QueueEntry("local")), startsPlaying = true); runCurrent()
        coordinator.undoDismiss(queue.handoffIdentity); runCurrent()
        assertNull(coordinator.offer.value)
        assertEquals("local", player.currentEntry!!.songId)
        coordinator.close()
    }

    @Test fun newestSaveSupersedesFailedRequestAndSessionCloseCancelsRetry() = runTest {
        val player = PlaybackController(FakeAudioEngine())
        val attempts = mutableListOf<QueueHandoffSnapshot>()
        val coordinator = QueueHandoffCoordinator(player, backgroundScope, "Coda on Android", { null }, {
            attempts += it
            if (attempts.size == 1) throw java.io.IOException()
        })
        runCurrent(); player.replaceQueue(listOf(QueueEntry("a"))); runCurrent()
        advanceTimeBy(251); runCurrent()
        player.seek(1000); player.pause(); runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf(0L, 1000L), attempts.map { it.positionMilliseconds })
        coordinator.close(); advanceTimeBy(60_000); runCurrent(); assertEquals(2, attempts.size)
    }

    private fun TestScope.immediate() = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    @Test fun immediatePausedReplacementNeverSavesAnEmptyOrIntermediateQueue() = runTest {
        val player = PlaybackController(FakeAudioEngine())
        val saves = mutableListOf<QueueHandoffSnapshot>()
        val coordinator = QueueHandoffCoordinator(player, immediate(), "Coda on Android", { null }, { saves += it })
        runCurrent()
        player.replaceQueue(listOf(QueueEntry("a"), QueueEntry("b")), 1, 12345, startsPlaying = false)
        runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf(QueueHandoffSnapshot(listOf("a", "b"), 1, 12345)), saves)
        saves.clear()
        player.replaceQueue(listOf(QueueEntry("c")), 0, 0, startsPlaying = false)
        runCurrent()
        assertEquals(listOf(QueueHandoffSnapshot(listOf("c"), 0, 0)), saves)
        coordinator.close()
    }

    @Test fun immediatePausedEnqueueIntoEmptyQueueSavesTheSelection() = runTest {
        val player = PlaybackController(FakeAudioEngine())
        val saves = mutableListOf<QueueHandoffSnapshot>()
        val coordinator = QueueHandoffCoordinator(player, immediate(), "Coda on Android", { null }, { saves += it })
        runCurrent()
        player.enqueue(listOf(QueueEntry("a"), QueueEntry("b")))
        runCurrent()
        assertEquals(listOf(QueueHandoffSnapshot(listOf("a", "b"), 0, 0)), saves)
        coordinator.close()
    }

    @Test fun reschedulingARefreshCancelsTheRequestInFlight() = runTest {
        val player = PlaybackController(FakeAudioEngine())
        val gates = mutableListOf<CompletableDeferred<PlayQueue?>>()
        var cancelled = 0
        val coordinator = QueueHandoffCoordinator(player, backgroundScope, "Coda on Android", {
            val gate = CompletableDeferred<PlayQueue?>().also { gates += it }
            try { gate.await() } catch (e: CancellationException) { cancelled++; throw e }
        }, {})
        advanceTimeBy(251); runCurrent()
        assertEquals(1, gates.size)
        coordinator.scheduleRefresh(100)
        runCurrent()
        assertEquals(1, cancelled)
        advanceTimeBy(101); runCurrent()
        assertEquals(2, gates.size)
        gates[0].complete(remote("Coda on Mac")); runCurrent()
        assertNull(coordinator.offer.value)
        gates[1].complete(remote("Coda on Mac")); runCurrent()
        assertNotNull(coordinator.offer.value)
        coordinator.close()
    }

    @Test fun localTakeoverCancelsTheRealRequestSoItCannotPublishAStaleOffer() = runTest {
        val player = PlaybackController(FakeAudioEngine())
        val gates = mutableListOf<CompletableDeferred<PlayQueue?>>()
        var cancelled = 0
        val coordinator = QueueHandoffCoordinator(player, backgroundScope, "Coda on Android", {
            val gate = CompletableDeferred<PlayQueue?>().also { gates += it }
            try { gate.await() } catch (e: CancellationException) { cancelled++; throw e }
        }, {})
        advanceTimeBy(251); runCurrent()
        coordinator.scheduleRefresh(100); advanceTimeBy(101); runCurrent()   // retriggered: a second request is in flight
        assertEquals(2, gates.size); assertEquals(1, cancelled)
        player.replaceQueue(listOf(QueueEntry("local")), startsPlaying = false); runCurrent()
        assertEquals(2, cancelled)
        gates.forEach { it.complete(remote("Coda on Mac")) }; runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertNull(coordinator.offer.value)
        assertNotNull(coordinator.terminationSave())
        coordinator.close()
    }

    @Test fun failedRefreshKeepsAnExistingOffer() = runTest {
        val player = PlaybackController(FakeAudioEngine())
        var fail = false
        val coordinator = QueueHandoffCoordinator(player, backgroundScope, "Coda on Android",
            { if (fail) throw java.io.IOException() else remote("Coda on Mac") }, {})
        advanceTimeBy(251); runCurrent()
        val offered = coordinator.offer.value
        assertNotNull(offered)
        fail = true
        coordinator.refreshRemoteQueue(); runCurrent()
        assertEquals(offered, coordinator.offer.value)
        fail = false
        coordinator.refreshRemoteQueue(); runCurrent()
        assertEquals(offered, coordinator.offer.value)
        coordinator.close()
    }

    @Test fun exitSaveCancelsTheInFlightSaveAndLandsAfterIt() = runTest {
        val player = PlaybackController(FakeAudioEngine())
        val gate = CompletableDeferred<Unit>()
        val written = mutableListOf<QueueHandoffSnapshot>()
        val coordinator = QueueHandoffCoordinator(player, backgroundScope, "Coda on Android", { null }, {
            if (written.isEmpty() && !gate.isCompleted) gate.await()
            written += it
        })
        runCurrent()
        player.replaceQueue(listOf(QueueEntry("a"), QueueEntry("b")), 0, 1000, startsPlaying = false)
        runCurrent()   // the pause-boundary save is suspended inside the server call
        player.seek(5000)
        val exiting = launch { coordinator.terminationSave()!!.invoke() }
        runCurrent(); gate.complete(Unit); runCurrent(); exiting.join()
        assertEquals(5000L, written.last().positionMilliseconds)
        assertEquals(written.map { it.positionMilliseconds }, written.map { it.positionMilliseconds }.sorted())
        coordinator.close()
    }

    @Test fun staleExitSaveNeverOverwritesANewerPeriodicSave() = runTest {
        val player = PlaybackController(FakeAudioEngine())
        val written = mutableListOf<QueueHandoffSnapshot>()
        val coordinator = QueueHandoffCoordinator(player, backgroundScope, "Coda on Android", { null }, { written += it })
        runCurrent()
        player.replaceQueue(listOf(QueueEntry("a"), QueueEntry("b")), 0, 1000, startsPlaying = true)
        runCurrent(); advanceTimeBy(251); runCurrent()
        val exit = coordinator.terminationSave()!!   // onTaskRemoved while playback continues
        player.seek(9000)
        advanceTimeBy(15_000); runCurrent()
        val newest = written.last()
        assertEquals(9000L, newest.positionMilliseconds)
        exit(); runCurrent()
        assertEquals(newest, written.last())
        coordinator.close()
    }

    /**
     * With the screen off there is no periodic save at all; the boundaries save at once: queue edit, skip and natural
     * track change (new track at position 0), pause and stop (exact position), exit (task removed / service destroyed).
     * Screen on keeps the 15 s tick. Every server save reports through onSaved (the scrobble batch rides along).
     */
    @Test fun screenOffSavesOnlyAtBoundaries() = runTest {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        val saves = mutableListOf<QueueHandoffSnapshot>()
        var screenOn = false
        var saved = 0
        val coordinator = QueueHandoffCoordinator(player, backgroundScope, "Coda on Android", { null }, { saves += it },
            screenOn = { screenOn }, onSaved = { saved++ })
        runCurrent()
        player.replaceQueue(listOf(QueueEntry("a"), QueueEntry("b"), QueueEntry("c"), QueueEntry("d")), 0, 0, startsPlaying = true)
        runCurrent(); advanceTimeBy(251); runCurrent()
        assertEquals("queue edit saves at once", 1, saves.size)
        engine.position = 30_000
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertEquals("no timer save in 10 minutes with the screen off", 1, saves.size)
        engine.position = 1_234
        player.next(); runCurrent(); advanceTimeBy(501); runCurrent()
        assertEquals("skip saves at once", 2, saves.size)
        assertEquals(1, saves.last().currentIndex); assertEquals("new track at position 0", 0, saves.last().positionMilliseconds)
        engine.position = 600
        engine.advance(); runCurrent(); advanceTimeBy(501); runCurrent()
        assertEquals("natural track change saves", 3, saves.size)
        assertEquals(2, saves.last().currentIndex); assertEquals(0, saves.last().positionMilliseconds)
        engine.position = 77_777
        player.pause(); runCurrent()
        assertEquals("pause saves at once", 4, saves.size); assertEquals("exact pause position", 77_777, saves.last().positionMilliseconds)
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertEquals(4, saves.size)
        player.play(); runCurrent(); advanceTimeBy(501); runCurrent()
        assertEquals("resume saves", 5, saves.size)
        engine.position = 88_888
        coordinator.terminationSave()!!.invoke()
        assertEquals("task removed / destroy saves the exact position", 6, saves.size)
        assertEquals(2, saves.last().currentIndex); assertEquals(88_888, saves.last().positionMilliseconds)
        assertEquals(6, saved)
        coordinator.close()
    }

    @Test fun screenOffStopSavesAndScreenOnKeepsTheTick() = runTest {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        val saves = mutableListOf<QueueHandoffSnapshot>()
        var screenOn = false
        val coordinator = QueueHandoffCoordinator(player, backgroundScope, "Coda on Android", { null }, { saves += it }, screenOn = { screenOn })
        runCurrent()
        player.replaceQueue(listOf(QueueEntry("a"), QueueEntry("b")), 0, 0, startsPlaying = true)
        runCurrent(); advanceTimeBy(251); runCurrent()
        assertEquals(1, saves.size)
        screenOn = true
        advanceTimeBy(15_000); runCurrent()
        assertEquals("screen on: the 15 s tick is back", 2, saves.size)
        advanceTimeBy(15_000); runCurrent()
        assertEquals(3, saves.size)
        screenOn = false
        advanceTimeBy(60_000); runCurrent()
        assertEquals("screen off again: no tick", 3, saves.size)
        player.stop(); runCurrent()
        assertEquals("stop saves at once", 4, saves.size)
        coordinator.close()
    }

    /** Screen off and playing, the local copy follows the position every 60 s, with no server request. */
    @Test fun screenOffWritesTheLocalCopyEverySixtySecondsWithoutServerSaves() = runTest {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        val saves = mutableListOf<QueueHandoffSnapshot>()
        val local = mutableListOf<LocalQueueCopy>()
        val coordinator = QueueHandoffCoordinator(player, backgroundScope, "Coda on Android", { null }, { saves += it },
            saveLocal = { local += it }, screenOn = { false })
        runCurrent()
        player.replaceQueue(listOf(QueueEntry("a"), QueueEntry("b")), 0, 0, startsPlaying = true)
        runCurrent(); advanceTimeBy(251); runCurrent()
        assertEquals(1, saves.size); assertEquals(1, local.size)
        advanceTimeBy(59_000); runCurrent()
        assertEquals("not yet a minute", 1, local.size)
        advanceTimeBy(2_000); runCurrent()
        assertEquals("one local copy after a minute", 2, local.size)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(4, local.size)
        assertEquals("no server request from the timer", 1, saves.size)
        player.stop(); runCurrent()
        coordinator.close()
    }

    // The first lookup after a restart with an empty player compares the phone's copy with the server queue.
    private val phone = "Coda on Android"
    private fun copy(vararg ids: String, index: Int, position: Long = 777, serverChanged: String? = null) =
        LocalQueueCopy(ids.map { LocalQueueCopy.Entry(it) }, index, position, serverChanged = serverChanged)
    private fun TestScope.restart(server: PlayQueue, local: LocalQueueCopy?, saves: MutableList<QueueHandoffSnapshot>): Pair<PlaybackController, QueueHandoffCoordinator> {
        val player = PlaybackController(FakeAudioEngine())
        val coordinator = QueueHandoffCoordinator(player, backgroundScope, phone, { server }, { saves += it }, loadLocal = { local })
        advanceTimeBy(251); runCurrent()
        return player to coordinator
    }

    @Test fun localNewerAfterRestartKeepsTheLocalQueueAndSavesIt() = runTest {
        val saves = mutableListOf<QueueHandoffSnapshot>()
        // The server holds our own pre-outage save (a, b at b); offline the phone moved on and edited: c, a at a.
        val (player, coordinator) = restart(remote(phone), copy("c", "a", index = 1, serverChanged = "v1"), saves)
        assertEquals(listOf("c", "a"), player.queue.map { it.songId })
        assertEquals(1, player.currentIndex); assertEquals(777L, player.positionMs); assertFalse(player.isPlaying)
        assertNull(coordinator.offer.value)
        runCurrent()
        assertEquals(listOf(QueueHandoffSnapshot(listOf("c", "a"), 1, 777)), saves)
        coordinator.close()
    }

    @Test fun anotherDeviceWithTheSameClientNameIsNotOverwrittenAfterRestart() = runTest {
        // A twin (same model, or debug and release) saved "v2" under our name after our copy knew "v1": the server's queue, as today.
        val saves = mutableListOf<QueueHandoffSnapshot>()
        val (player, coordinator) = restart(remote(phone).copy(changed = "v2"), copy("c", index = 0, serverChanged = "v1"), saves)
        assertEquals(listOf("a", "b"), player.queue.map { it.songId })
        advanceTimeBy(60_000); runCurrent(); assertTrue(saves.isEmpty())
        coordinator.close()
    }

    @Test fun aSeenForeignQueueIsStillOfferedAfterRestart() = runTest {
        // changedBy must be ours (extra guard): the Mac's queue, even unchanged since we saw it, is offered as today.
        val saves = mutableListOf<QueueHandoffSnapshot>()
        val (player, coordinator) = restart(remote(), copy("c", index = 0, serverChanged = "v1"), saves)
        assertTrue(player.queue.isEmpty()); assertNotNull(coordinator.offer.value)
        coordinator.close()
    }

    @Test fun serverNewerAfterRestartKeepsTheHandoffPath() = runTest {
        // The Mac saved after the phone's last known server state ("v0"): today's offer, the player stays empty, nothing is saved.
        val saves = mutableListOf<QueueHandoffSnapshot>()
        val (player, coordinator) = restart(remote(), copy("c", index = 0, serverChanged = "v0"), saves)
        assertTrue(player.queue.isEmpty()); assertNotNull(coordinator.offer.value)
        advanceTimeBy(60_000); runCurrent(); assertTrue(saves.isEmpty())
        coordinator.close()
    }

    @Test fun equalOrUnknownAfterRestartRestoresTheServerQueueAsToday() = runTest {
        // The same queue as the server's own save, no copy, or another client's queue without a known `changed`: as today.
        for (local in listOf(copy("a", "b", index = 1, position = 12345), null)) {
            val saves = mutableListOf<QueueHandoffSnapshot>()
            val (player, coordinator) = restart(remote(phone), local, saves)
            assertEquals(listOf("a", "b"), player.queue.map { it.songId }); assertEquals(12345L, player.positionMs)
            advanceTimeBy(60_000); runCurrent(); assertTrue(saves.isEmpty())
            coordinator.close()
        }
    }

    @Test fun anotherClientsQueueWithoutAKnownChangedIsOfferedAsToday() = runTest {
        val saves = mutableListOf<QueueHandoffSnapshot>()
        val (player, coordinator) = restart(remote(), copy("c", index = 0), saves)
        assertTrue(player.queue.isEmpty()); assertNotNull(coordinator.offer.value)
        coordinator.close()
    }

    /** Every local copy carries the last server `changed` seen (server clock), also offline. */
    @Test fun theLocalCopyCarriesTheLastServerChangedSeen() = runTest {
        val net = object : dev.coda.core.connectivity.Connectivity { override val online = kotlinx.coroutines.flow.MutableStateFlow(true) }
        val gate = dev.coda.core.connectivity.ServerGate(net, { testScheduler.currentTime })
        val player = PlaybackController(FakeAudioEngine())
        val local = mutableListOf<LocalQueueCopy>()
        val saves = mutableListOf<QueueHandoffSnapshot>()
        val coordinator = QueueHandoffCoordinator(player, backgroundScope, phone, { remote().copy(changed = "v7") }, { saves += it },
            saveLocal = { local += it }, serverGate = gate)
        advanceTimeBy(251); runCurrent()                       // the Mac's queue is offered: "v7" seen
        net.online.value = false
        player.replaceQueue(listOf(QueueEntry("a")), 0, 0, startsPlaying = false); runCurrent()
        assertEquals(listOf("v7"), local.map { it.serverChanged })
        assertTrue("offline: no server save", saves.isEmpty())
        coordinator.close()
    }

    /** The save held back offline goes out on reconnect only if nobody else saved the server's queue meanwhile. */
    @Test fun reconnectSaveIsNewerWins() = runTest {
        // The baseline is the `changed` the first load saw, or (first load failed) our own successful save.
        for ((macSavedMeanwhile, firstLoadFails) in listOf(true to false, false to false, true to true)) {
            val net = object : dev.coda.core.connectivity.Connectivity { override val online = kotlinx.coroutines.flow.MutableStateFlow(true) }
            val gate = dev.coda.core.connectivity.ServerGate(net, { testScheduler.currentTime })
            var server: PlayQueue? = remote(phone).copy(changed = "v1")
            val player = PlaybackController(FakeAudioEngine())
            val saves = mutableListOf<QueueHandoffSnapshot>()
            var loads = 0
            val coordinator = QueueHandoffCoordinator(player, backgroundScope, phone,
                { if (firstLoadFails && loads++ == 0) throw java.io.IOException() else server }, { saves += it }, serverGate = gate)
            advanceTimeBy(251); runCurrent()                   // our own queue restored, "v1" seen (or the load failed)
            if (firstLoadFails) player.replaceQueue(listOf(QueueEntry("a"), QueueEntry("b")), 1, 0, startsPlaying = false)
            player.play(); runCurrent(); advanceTimeBy(600); runCurrent()
            assertEquals(1, saves.size)
            net.online.value = false
            player.play(0); runCurrent(); advanceTimeBy(600); runCurrent()   // a track change offline: held back
            assertEquals(1, saves.size)
            if (macSavedMeanwhile) server = remote().copy(changed = "v2")
            net.online.value = true; runCurrent()
            if (macSavedMeanwhile) {
                assertEquals("no save over the Mac's newer queue", 1, saves.size)
                assertEquals("the normal handoff offer", "Coda on Mac", coordinator.offer.value?.changedBy)
                assertTrue("keeps playing locally", player.isPlaying)
                advanceTimeBy(60_000); runCurrent(); assertEquals("no later periodic save either", 1, saves.size)
                // A Bluetooth-style pause/resume does not take the server back; the exit save writes only the local copy.
                player.pause(); runCurrent(); advanceTimeBy(1_000); runCurrent()
                player.play(); runCurrent(); advanceTimeBy(1_000); runCurrent()
                assertEquals("no save after pause/resume", 1, saves.size)
                coordinator.terminationSave()?.invoke(); runCurrent()
                assertEquals("no exit save over the Mac's queue", 1, saves.size)
                // Taking the offer while playing installs the Mac's queue (and saves it as ours).
                coordinator.continuePlaying(coordinator.offer.value!!.handoffIdentity); runCurrent(); advanceTimeBy(600); runCurrent()
                assertEquals(listOf("a", "b"), player.queue.map { it.songId }); assertTrue(player.isPlaying)
                assertNull(coordinator.offer.value)
                assertEquals(2, saves.size)
            } else {
                assertEquals("local is newer: saved as today", 2, saves.size)
                assertNull(coordinator.offer.value)
            }
            player.stop(); runCurrent(); coordinator.close()
        }
    }

    /** In the reconnect conflict an explicit dismiss is "keep mine"; the offer going away on its own is not. */
    @Test fun dismissInAConflictKeepsMine() = runTest {
        for (case in listOf("dismiss", "untouched", "macNewer", "pausedRefresh", "failingSave")) {
            val net = object : dev.coda.core.connectivity.Connectivity { override val online = kotlinx.coroutines.flow.MutableStateFlow(true) }
            val gate = dev.coda.core.connectivity.ServerGate(net, { testScheduler.currentTime })
            var server: PlayQueue? = remote(phone).copy(changed = "v1")
            var serverClock = 2
            val player = PlaybackController(FakeAudioEngine())
            val saves = mutableListOf<QueueHandoffSnapshot>()
            val local = mutableListOf<LocalQueueCopy>()
            var loads = 0
            var failSaves = 0
            val coordinator = QueueHandoffCoordinator(player, backgroundScope, phone, { loads++; server }, {
                if (failSaves > 0) { failSaves--; throw java.io.IOException() }
                saves += it
                server = PlayQueue(current = it.songIDs[it.currentIndex], currentIndex = it.currentIndex, position = it.positionMilliseconds,
                    changedBy = phone, changed = "v${++serverClock}", entry = it.songIDs.map { id -> Song(id, id) })
            }, saveLocal = { local += it }, serverGate = gate)
            advanceTimeBy(251); runCurrent()
            player.play(); runCurrent(); advanceTimeBy(600); runCurrent()
            assertEquals(1, saves.size)
            net.online.value = false
            player.play(0); runCurrent(); advanceTimeBy(600); runCurrent()          // held back offline
            server = remote().copy(changed = "v9", entry = listOf(Song("m", "M")), currentIndex = 0, current = "m")
            net.online.value = true; runCurrent()
            val offer = coordinator.offer.value!!
            assertEquals("conflict: the Mac's offer", "Coda on Mac", offer.changedBy)
            assertEquals(1, saves.size)
            when (case) {
                "untouched" -> {
                    // No explicit dismiss (the card scrolled away, the app left, a stale identity): the conflict stays.
                    coordinator.dismiss("not the offer"); runCurrent()
                    advanceTimeBy(60_000); runCurrent()
                    player.pause(); runCurrent(); coordinator.scheduleRefresh(); advanceTimeBy(1_000); runCurrent()
                    assertEquals("no save without an explicit dismiss", 1, saves.size)
                    assertEquals("Coda on Mac", server?.changedBy)
                }
                "macNewer" -> {
                    // The Mac saved again after the offer was shown: keep-mine re-reads first and offers that queue instead.
                    server = remote().copy(changed = "v10", entry = listOf(Song("n", "N")), currentIndex = 0, current = "n")
                    coordinator.dismiss(offer.handoffIdentity); runCurrent(); advanceTimeBy(1_000); runCurrent()
                    assertEquals("no overwrite of the newer Mac queue", 1, saves.size)
                    assertEquals("v10", server?.changed)
                    val again = coordinator.offer.value
                    assertEquals("a new offer", listOf("n"), again?.entry?.map { it.id })
                    assertTrue("dismissing it is keep-mine again", coordinator.offerKeepsMine(again!!.handoffIdentity))
                    advanceTimeBy(60_000); runCurrent(); assertEquals(1, saves.size)
                }
                else -> {
                    if (case == "failingSave") failSaves = 3
                    assertTrue(coordinator.offerKeepsMine(offer.handoffIdentity))
                    val loadsBefore = loads
                    coordinator.dismiss(offer.handoffIdentity); runCurrent(); advanceTimeBy(100); runCurrent()
                    if (case == "failingSave") {
                        assertEquals("the failed save is kept for the next round", 1, saves.size)
                        // The backoff ends, the re-check sees the same Mac queue, the save goes out (at most 10 min).
                        repeat(120) { if (saves.size < 2) { advanceTimeBy(5_000); runCurrent() } }
                    }
                    assertEquals("dismiss saves the phone's queue", 2, saves.size)
                    assertEquals(listOf("a", "b"), saves[1].songIDs); assertEquals(0, saves[1].currentIndex)
                    assertEquals(phone, server?.changedBy)
                    assertTrue("read-back for the new baseline", loads > loadsBefore)
                    assertNull("conflict cleared", coordinator.offer.value)
                    assertTrue(player.isPlaying)
                    when (case) {
                        "dismiss" -> {
                            // Ownership is ours again: the periodic save goes out, and the copy carries the read-back baseline.
                            val readBack = server?.changed
                            advanceTimeBy(15_000); runCurrent()
                            assertEquals(3, saves.size)
                            assertEquals("the next copy is stamped with the read-back", readBack, local.last().serverChanged)
                        }
                        "pausedRefresh" -> {
                            // Paused, a refresh sees our own queue: no offer, no conflict; the next play saves as usual.
                            player.pause(); runCurrent(); advanceTimeBy(100); runCurrent()
                            val afterPause = saves.size
                            coordinator.scheduleRefresh(); advanceTimeBy(1_000); runCurrent()
                            assertNull("no offer for our own queue", coordinator.offer.value)
                            player.play(); runCurrent(); advanceTimeBy(600); runCurrent()
                            assertEquals("saving as the owner again", afterPause + 1, saves.size)
                        }
                    }
                }
            }
            player.stop(); runCurrent(); coordinator.close()
        }
    }

    @Test fun aLoadThatSawNoQueueIsABaselineForReconnect() {
        val mac = PlayQueue(changed = "v2", changedBy = "Coda on Mac", entry = listOf(Song("a", "A")))
        assertTrue(serverQueueChangedSince(mac, phone, ""))
        assertFalse("never loaded: unknown", serverQueueChangedSince(mac, phone, null))
        assertFalse("nobody saved since", serverQueueChangedSince(mac, phone, "v2"))
        assertFalse("our own save", serverQueueChangedSince(mac.copy(changedBy = phone), phone, "v1"))
    }

    /** Exit with a held, unverified save writes only the local copy. */
    @Test fun exitWithAnUnverifiedHeldSaveWritesOnlyTheLocalCopy() = runTest {
        val net = object : dev.coda.core.connectivity.Connectivity { override val online = kotlinx.coroutines.flow.MutableStateFlow(true) }
        val gate = dev.coda.core.connectivity.ServerGate(net, { testScheduler.currentTime })
        val player = PlaybackController(FakeAudioEngine())
        val saves = mutableListOf<QueueHandoffSnapshot>()
        val local = mutableListOf<LocalQueueCopy>()
        val coordinator = QueueHandoffCoordinator(player, backgroundScope, phone, { remote(phone) }, { saves += it },
            saveLocal = { local += it }, serverGate = gate)
        advanceTimeBy(251); runCurrent()
        net.online.value = false
        player.play(0); runCurrent(); advanceTimeBy(600); runCurrent()   // held back offline
        net.online.value = true                                         // back, but the worker has not verified yet
        val exit = coordinator.terminationSave()!!
        exit()
        assertTrue("no server save at exit", saves.isEmpty())
        assertEquals(listOf("a", "b"), local.last().entries.map { it.songId })
        coordinator.close()
    }

    /** A failed first lookup keeps restoration armed; the next successful one restores the own queue. */
    @Test fun failedFirstLookupThenSuccessfulRetryRestoresTheOwnQueue() = runTest {
        val player = PlaybackController(FakeAudioEngine())
        var fail = true
        val coordinator = QueueHandoffCoordinator(player, backgroundScope, phone,
            { if (fail) throw java.io.IOException() else remote(phone) }, {})
        advanceTimeBy(251); runCurrent()
        assertTrue(player.queue.isEmpty()); assertFalse(coordinator.initialQueueResolved)
        fail = false
        coordinator.refreshRemoteQueue(); runCurrent()
        assertEquals(listOf("a", "b"), player.queue.map { it.songId })
        assertEquals(1, player.currentIndex); assertEquals(12345L, player.positionMs)
        assertTrue(coordinator.initialQueueResolved)
        coordinator.close()
    }

    /** The same after a failed first lookup when the phone's local copy is newer than the server's own queue. */
    @Test fun failedFirstLookupThenSuccessfulRetryRestoresANewerLocalCopy() = runTest {
        val player = PlaybackController(FakeAudioEngine())
        val saves = mutableListOf<QueueHandoffSnapshot>()
        var fail = true
        val coordinator = QueueHandoffCoordinator(player, backgroundScope, phone,
            { if (fail) throw java.io.IOException() else remote(phone) }, { saves += it },
            loadLocal = { copy("c", "a", index = 1, serverChanged = "v1") })
        advanceTimeBy(251); runCurrent()
        assertTrue(player.queue.isEmpty())
        fail = false
        coordinator.refreshRemoteQueue(); runCurrent()
        assertEquals(listOf("c", "a"), player.queue.map { it.songId })
        assertEquals(1, player.currentIndex); assertEquals(777L, player.positionMs)
        runCurrent()
        assertEquals(listOf(QueueHandoffSnapshot(listOf("c", "a"), 1, 777)), saves)
        coordinator.close()
    }
}
