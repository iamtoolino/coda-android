package dev.coda.core.connectivity

import dev.coda.core.playback.*
import dev.coda.core.scrobble.*
import dev.coda.core.sync.LocalQueueCopy
import dev.coda.core.sync.QueueHandoffCoordinator
import dev.coda.core.sync.QueueHandoffSnapshot
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** No background request without a network; one shared backoff while the server does not answer. */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerGateTest {
    @get:Rule val folder = TemporaryFolder()

    private class Net(value: Boolean) : Connectivity { override val online = MutableStateFlow(value) }
    private data class Call(val songId: String, val submission: Boolean, val timeMs: Long, val atMs: Long)

    private class Api(private val now: () -> Long) : ScrobbleApi {
        val attempts = mutableListOf<Call>()
        val sent = mutableListOf<Call>()
        var down = false
        override suspend fun scrobble(songId: String, submission: Boolean, timeMs: Long) {
            val call = Call(songId, submission, timeMs, now())
            attempts += call
            if (down) throw IOException("down")
            sent += call
        }
    }

    private var clockMs = 1_000_000L
    private fun store() = ScrobbleOutbox(File(folder.root, "scrobbles.json"))
    private fun TestScope.gate(net: Net, skipped: MutableList<ServerGate.Kind> = mutableListOf()) =
        ServerGate(net, { testScheduler.currentTime }) { skipped += it }
    private fun TestScope.scrobbles(gate: ServerGate, scope: CoroutineScope = backgroundScope, store: ScrobbleStore = store()) =
        ScrobbleCoordinator(scope, store, { clockMs }, StandardTestDispatcher(testScheduler, "IO"), listOf(1_000, 2_000), gate = gate)
    private fun completed(id: String) = PlaybackEvent.Completed(CompletedPlayback(id, null))
    private fun started(id: String) = PlaybackEvent.OccurrenceStarted(QueueEntry(id))

    @Test fun backoffDoublesFromThirtySecondsToTenMinutesOneStepPerRoundAndResets() = runTest {
        val net = Net(true); val gate = gate(net)
        assertTrue(gate.isOpen()); assertTrue(gate.retriesAllowed)
        val steps = mutableListOf<Long>()
        repeat(8) {
            gate.failed(); steps += gate.backoffMs
            gate.failed()                                       // a second caller in the same round: no extra step
            assertEquals(steps.last(), gate.backoffMs)
            assertFalse(gate.isOpen()); assertFalse(gate.retriesAllowed)
            advanceTimeBy(gate.backoffMs); runCurrent()
            assertTrue(gate.isOpen())
        }
        assertEquals(listOf(30_000L, 60_000, 120_000, 240_000, 480_000, 600_000, 600_000, 600_000), steps)
        gate.reset()                                            // a network change or a server answer
        assertTrue(gate.isOpen()); assertTrue(gate.retriesAllowed)
        gate.failed(); assertEquals("starts again at 30 s", 30_000L, gate.backoffMs)
    }

    @Test fun awaitOpenWakesAtTheBackoffEndOnANetworkAndOnAReset() = runTest {
        val net = Net(false); val gate = gate(net)
        var opened: Long? = null
        fun TestScope.await() = backgroundScope.launch { gate.awaitOpen(); opened = testScheduler.currentTime }.also { opened = null }
        await(); advanceTimeBy(60_000); runCurrent()
        assertNull("offline: closed", opened)
        net.online.value = true; runCurrent()
        assertEquals("a network opens it", 60_000L, opened)
        gate.failed(); await(); advanceTimeBy(29_000); runCurrent()
        assertNull(opened)
        advanceTimeBy(1_100); runCurrent()
        assertEquals("the backoff ended", 90_000L, opened)
        gate.failed(); await(); advanceTimeBy(5_000); runCurrent()
        assertNull(opened)
        gate.reset(); runCurrent()
        assertEquals("a reset opens it at once", 95_100L, opened)
    }

    /**
     * Offline for an hour with track changes, a pause and the screen going on and off: no request of any kind. Back online:
     * one save (the latest snapshot) and every completed play in order with its original time, exactly once.
     */
    /** Rex r1 P2-2: a reset (or a lost network) between awaitOpen's read of the backoff end and its subscription wakes it. */
    @Test fun awaitOpenDoesNotMissAResetOrALostNetworkRightAfterItReadTheBackoff() = runTest {
        val net = Net(true)
        var inject: (() -> Unit)? = null
        lateinit var gate: ServerGate
        // awaitOpen reads `until`, then the clock: a hook on that clock read lands exactly in the window.
        gate = ServerGate(net, { inject?.also { inject = null }?.invoke(); testScheduler.currentTime })
        gate.failed()
        inject = { gate.reset() }
        val start = testScheduler.currentTime
        var woke = -1L
        backgroundScope.launch { gate.awaitOpen(); woke = testScheduler.currentTime - start }
        runCurrent()
        assertEquals("woke on the reset, not after the 30 s step", 0L, woke)

        gate.failed()
        inject = { net.online.value = false }
        woke = -1L
        backgroundScope.launch { gate.awaitOpen(); woke = testScheduler.currentTime - start }
        runCurrent()
        assertEquals("waits for the network", -1L, woke)
        gate.reset(); net.online.value = true; runCurrent()
        assertEquals("woke when the network came back, not at the old step's end", 0L, woke)
    }

    @Test fun offlineHourMakesNoRequestThenOneSaveAndAllScrobblesInOrder() = runTest {
        val net = Net(true); val skipped = mutableListOf<ServerGate.Kind>(); val gate = gate(net, skipped)
        val engine = FakeAudioEngine(); val player = PlaybackController(engine)
        val saves = mutableListOf<QueueHandoffSnapshot>(); val local = mutableListOf<LocalQueueCopy>()
        var screenOn = true
        val api = Api { testScheduler.currentTime }
        val scrobbles = scrobbles(gate); scrobbles.setSession(ScrobbleSession("k", api))
        val handoff = QueueHandoffCoordinator(player, backgroundScope, "Coda on Android", { null }, { saves += it },
            saveLocal = { local += it }, screenOn = { screenOn }, onSaved = { scrobbles.flushBatch() }, serverGate = gate)
        runCurrent()
        player.replaceQueue((1..20).map { QueueEntry("s$it") }, 0, 0, startsPlaying = true)
        runCurrent(); advanceTimeBy(251); runCurrent()
        assertEquals(1, saves.size)
        val savesBefore = saves.size; val localBefore = local.size
        net.online.value = false
        val played = mutableListOf<Pair<String, Long>>()
        for (i in 0 until 12) {                                 // 12 tracks of 5 minutes
            screenOn = i % 3 != 0
            scrobbles.onEvent(started("s${i + 1}"))
            advanceTimeBy(5 * 60_000L); runCurrent()
            clockMs += 300_000; scrobbles.onEvent(completed("s${i + 1}")); played += "s${i + 1}" to clockMs
            engine.position = 0; engine.advance(); runCurrent(); advanceTimeBy(501); runCurrent()
            if (i == 5) { engine.position = 42_000; player.pause(); runCurrent(); advanceTimeBy(60_000); player.play(); runCurrent(); advanceTimeBy(501); runCurrent() }
        }
        assertEquals("no queue save offline", savesBefore, saves.size)
        assertTrue("no scrobble or now-playing request offline", api.attempts.isEmpty())
        assertTrue("the local copy follows every boundary", local.size >= localBefore + 12)
        assertTrue(ServerGate.Kind.QUEUE_SAVE in skipped && ServerGate.Kind.SCROBBLE in skipped && ServerGate.Kind.NOW_PLAYING in skipped)
        screenOn = false
        net.online.value = true; gate.reset(); runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals("one save when the network returns", savesBefore + 1, saves.size)
        assertEquals("the latest snapshot", player.currentIndex, saves.last().currentIndex)
        assertEquals(played, api.sent.filter { it.submission }.map { it.songId to it.timeMs })
        assertTrue("now-playing hints were dropped, not queued", api.sent.none { !it.submission })
        advanceTimeBy(60 * 60_000L); runCurrent()
        assertEquals("exactly once", savesBefore + 1, saves.size)
        assertEquals(played.size, api.sent.count { it.submission })
        assertNull(store().peek("k"))
        handoff.close()
    }

    /** Network back but the server down: one shared backoff (30 s doubling to 10 min) for the save and the outbox together. */
    @Test fun serverDownAfterReconnectBacksOffAndResetsOnNetworkChangeAndServerContact() = runTest {
        val net = Net(true); val gate = gate(net)
        val api = Api { testScheduler.currentTime }.apply { down = true }
        val disk = store()
        disk.add(PendingScrobble(serverKey = "k", songId = "s1", playedAtMs = 500), 500)
        val saveTimes = mutableListOf<Long>()
        val player = PlaybackController(FakeAudioEngine())
        val handoff = QueueHandoffCoordinator(player, backgroundScope, "Coda on Android", { null },
            { saveTimes += testScheduler.currentTime; throw IOException("down") }, serverGate = gate)
        runCurrent()
        player.replaceQueue(listOf(QueueEntry("a"), QueueEntry("b")))   // paused edit: saves at once
        val scrobbles = scrobbles(gate, store = disk); scrobbles.setSession(ScrobbleSession("k", api))
        runCurrent()
        advanceTimeBy(40 * 60_000L); runCurrent()
        val times = api.attempts.map { it.atMs }
        // First round with the per-call retries (0, 1, 3 s), then one attempt per backoff step.
        assertEquals(listOf(0L, 1_000, 3_000), times.take(3))
        val gaps = times.zipWithNext { a, b -> b - a }.drop(2)
        assertEquals(listOf(30_000L, 60_000, 120_000, 240_000, 480_000, 600_000), gaps.take(6))
        assertTrue("capped at 10 min", gaps.drop(5).all { it == 600_000L })
        assertTrue("the save rides the same rounds", saveTimes.size in (times.size - 2)..(times.size + 2))
        assertTrue("one request per round per kind while backing off", times.size < 12)
        // A network change resets the backoff: the next attempt is immediate.
        val before = api.attempts.size
        gate.reset(); runCurrent()
        assertEquals(before + 1, api.attempts.size)
        // A server answer (any request) resets it too and the outbox goes out.
        api.down = false
        advanceTimeBy(1_000); runCurrent()                      // per-call retry after a reset
        assertEquals(listOf("s1"), api.sent.map { it.songId })
        assertEquals(0L, gate.backoffMs)
        handoff.close()
    }

    @Test fun scrobblesCompletedOfflineSurviveProcessDeathAndAreSentAfterRestart() = runTest {
        val net = Net(false); val gate = gate(net)
        val api = Api { testScheduler.currentTime }
        val process = CoroutineScope(backgroundScope.coroutineContext + Job())
        val first = scrobbles(gate, process)
        first.setSession(ScrobbleSession("k", api))
        for (id in listOf("s1", "s2", "s3")) { clockMs += 200_000; first.onEvent(completed(id)); runCurrent() }
        assertTrue(api.attempts.isEmpty())
        process.cancel()                                        // process death while offline
        assertEquals(3, store().all().size)
        val restarted = ServerGate(Net(true), { testScheduler.currentTime })
        val second = ScrobbleCoordinator(backgroundScope, store(), { clockMs }, StandardTestDispatcher(testScheduler, "IO"),
            listOf(1_000, 2_000), gate = restarted)
        second.setSession(ScrobbleSession("k", api)); runCurrent()
        assertEquals(listOf("s1" to 1_200_000L, "s2" to 1_400_000L, "s3" to 1_600_000L), api.sent.map { it.songId to it.timeMs })
        assertTrue(store().all().isEmpty())
    }

    @Test fun offlineExitWritesOnlyTheLocalCopy() = runTest {
        val net = Net(true); val gate = gate(net)
        val player = PlaybackController(FakeAudioEngine())
        val saves = mutableListOf<QueueHandoffSnapshot>(); val local = mutableListOf<LocalQueueCopy>()
        val handoff = QueueHandoffCoordinator(player, backgroundScope, "Coda on Android", { null }, { saves += it },
            saveLocal = { local += it }, serverGate = gate)
        runCurrent()
        player.replaceQueue(listOf(QueueEntry("a"), QueueEntry("b"))); runCurrent(); advanceTimeBy(251); runCurrent()
        assertEquals(1, saves.size)
        net.online.value = false
        val localBefore = local.size
        handoff.terminationSave()!!.invoke()
        assertEquals(1, saves.size); assertEquals(localBefore + 1, local.size)
        handoff.close()
    }
}
