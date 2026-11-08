package dev.coda.core.scrobble

import dev.coda.core.playback.*
import dev.coda.core.subsonic.SubsonicError
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ScrobbleCoordinatorTest {
    @get:Rule val folder = TemporaryFolder()
    private data class Call(val songId: String, val submission: Boolean, val timeMs: Long)

    private class FakeApi : ScrobbleApi {
        val calls = mutableListOf<Call>()
        val attempts = mutableListOf<Call>()
        var failure: Throwable? = null
        var failuresLeft = Int.MAX_VALUE
        /** Each call suspends this long (virtual time), so two senders can overlap. */
        var latencyMs = 0L
        override suspend fun scrobble(songId: String, submission: Boolean, timeMs: Long) {
            val call = Call(songId, submission, timeMs)
            attempts += call
            if (latencyMs > 0) kotlinx.coroutines.delay(latencyMs)
            val error = failure
            if (error != null && failuresLeft-- > 0) throw error
            calls += call
        }
        fun sent(submission: Boolean) = calls.filter { it.submission == submission }
    }

    private class FaultStore(val disk: ScrobbleOutbox) : ScrobbleStore by disk {
        var failAdd = false
        var failRemove = false
        var failPrune = false
        override fun add(entry: PendingScrobble, nowMs: Long) {
            if (failAdd) throw IOException("injected write failure")
            disk.add(entry, nowMs)
        }
        override fun remove(id: String) {
            if (failRemove) throw IOException("injected removal failure")
            disk.remove(id)
        }
        override fun prune(nowMs: Long) {
            if (failPrune) throw IOException("injected pruning failure")
            disk.prune(nowMs)
        }
    }

    private var clockMs = 1_000_000L
    private fun outbox() = ScrobbleOutbox(File(folder.root, "scrobbles.json"))
    private fun TestScope.coordinator(store: ScrobbleStore = outbox(), delays: List<Long> = listOf(100, 200)) =
        ScrobbleCoordinator(backgroundScope, store, { clockMs }, StandardTestDispatcher(testScheduler, "IO"), delays)
    private fun started(id: String) = PlaybackEvent.OccurrenceStarted(QueueEntry(id))
    private fun completed(id: String) = PlaybackEvent.Completed(CompletedPlayback(id, null))
    private fun TestScope.finishRetries() { advanceTimeBy(10_000); runCurrent() }

    @Test fun nowPlayingOnEachOccurrenceThenSubmissionWithCompletionTime() = runTest {
        val api = FakeApi(); val c = coordinator(); c.setSession(ScrobbleSession("k", api))
        clockMs = 5_000; c.onEvent(started("s1")); runCurrent()
        assertEquals(listOf(Call("s1", false, 5_000)), api.calls)
        clockMs = 9_000; c.onEvent(completed("s1")); runCurrent()
        assertEquals(listOf(Call("s1", true, 9_000)), api.sent(true))
        assertTrue(outbox().all().isEmpty())
    }

    @Test fun flushNowSendsTheOutboxOnceAndKeepsEntriesOnFailure() = runTest {
        val store = outbox(); val api = FakeApi()
        store.add(PendingScrobble(serverKey = "k", songId = "s1", playedAtMs = clockMs), clockMs)
        store.add(PendingScrobble(serverKey = "k", songId = "s2", playedAtMs = clockMs), clockMs)
        val c = coordinator(store)
        c.setSession(ScrobbleSession("k", api))
        api.failure = IOException("down")
        c.flushNow()
        assertEquals("one try, no back-off", 1, api.attempts.size)
        assertNotNull(store.peek("k"))
        api.failure = null
        c.flushNow()
        assertEquals(listOf("s1", "s2"), api.sent(true).map { it.songId }.distinct().let { l -> api.sent(true).map { it.songId } })
        assertNull(store.peek("k"))
    }

    @Test fun flushNowAndTheFlushWorkerNeverSendTheSamePlayTwice() = runTest {
        val store = outbox(); val api = FakeApi().apply { latencyMs = 50 }
        store.add(PendingScrobble(serverKey = "k", songId = "s1", playedAtMs = clockMs), clockMs)
        store.add(PendingScrobble(serverKey = "k", songId = "s2", playedAtMs = clockMs), clockMs)
        val c = coordinator(store)
        c.setSession(ScrobbleSession("k", api))
        runCurrent()   // the worker's own flush (from setSession) runs and finishes nothing yet: s1 is in flight
        val signOut = backgroundScope.launch { c.flushNow() }
        advanceTimeBy(10); runCurrent()   // the sign-out flush cancelled it and is now sending s1
        c.onNetworkAvailable()            // the worker wakes while the sign-out flush is mid-send
        signOut.join(); finishRetries()
        assertEquals(listOf("s1", "s2"), api.sent(true).map { it.songId })
        assertNull(store.peek("k"))
    }

    @Test fun noSessionMeansNothingIsSentOrQueued() = runTest {
        val c = coordinator(); c.onEvent(started("s1")); c.onEvent(completed("s1")); runCurrent()
        assertTrue(outbox().all().isEmpty())
    }

    @Test fun skippedOccurrenceNeverSubmitsAndANewOccurrenceCancelsRetryingNowPlaying() = runTest {
        val api = FakeApi().apply { failure = IOException() }
        val c = coordinator(); c.setSession(ScrobbleSession("k", api))
        c.onEvent(started("s1")); runCurrent()
        api.failure = null
        c.onEvent(started("s2")); finishRetries()
        assertEquals(listOf("s2"), api.sent(false).map { it.songId })
        assertTrue(api.sent(true).isEmpty()); assertTrue(outbox().all().isEmpty())
    }

    @Test fun nowPlayingGivesUpAfterTheRetrySchedule() = runTest {
        val api = FakeApi().apply { failure = SubsonicError.Network(IOException()) }
        val c = coordinator(); c.setSession(ScrobbleSession("k", api))
        c.onEvent(started("s1")); finishRetries()
        assertEquals(3, api.attempts.size)
        assertTrue(api.calls.isEmpty())
    }

    @Test fun offlineSubmissionsStayQueuedThenFlushInOrderWithOriginalTimes() = runTest {
        val api = FakeApi().apply { failure = SubsonicError.Network(IOException()) }
        val c = coordinator(); c.setSession(ScrobbleSession("k", api))
        clockMs = 100; c.onEvent(completed("a"))
        clockMs = 200; c.onEvent(completed("b")); runCurrent()
        assertEquals("persist the next completion during network backoff", listOf("a", "b"), outbox().all().map { it.songId })
        finishRetries()
        assertEquals(listOf("a", "b"), outbox().all().map { it.songId })
        assertTrue(api.calls.isEmpty())
        clockMs = 90_000; api.failure = null
        c.onNetworkAvailable(); runCurrent()
        assertEquals(listOf(Call("a", true, 100), Call("b", true, 200)), api.sent(true))
        assertTrue(outbox().all().isEmpty())
    }

    @Test fun queuedPlaysSurviveRestartAndGoOutWhenTheSessionReturns() = runTest {
        val firstScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val offline = FakeApi().apply { failure = IOException() }
        val first = ScrobbleCoordinator(firstScope, outbox(), { clockMs }, StandardTestDispatcher(testScheduler), listOf(100))
        first.setSession(ScrobbleSession("k", offline))
        clockMs = 42; first.onEvent(completed("a")); runCurrent()
        firstScope.cancel(); runCurrent()
        val api = FakeApi(); val second = coordinator(); second.setSession(ScrobbleSession("k", api)); runCurrent()
        assertEquals(listOf(Call("a", true, 42)), api.sent(true))
    }

    @Test fun anotherAccountsPlaysAreNeverSent() = runTest {
        val first = FakeApi().apply { failure = IOException() }
        val c = coordinator(); c.setSession(ScrobbleSession("one", first))
        c.onEvent(completed("a")); finishRetries()
        val other = FakeApi(); c.setSession(ScrobbleSession("two", other)); runCurrent()
        assertTrue(other.calls.isEmpty())
        assertEquals(listOf("a"), outbox().all().map { it.songId })
        val back = FakeApi(); c.setSession(ScrobbleSession("one", back)); runCurrent()
        assertEquals(listOf("a"), back.sent(true).map { it.songId })
    }

    @Test fun serverRejectionDropsTheEntryWithoutBlockingTheNext() = runTest {
        val sent = mutableListOf<String>()
        val api = ScrobbleApi { id, _, _ -> if (id == "gone") throw SubsonicError.NotFound() else sent += id }
        val c = coordinator(); c.setSession(ScrobbleSession("k", api))
        c.onEvent(completed("gone")); c.onEvent(completed("ok")); runCurrent()
        assertEquals(listOf("ok"), sent)
        assertTrue(outbox().all().isEmpty())
    }

    @Test fun unauthorizedKeepsTheEntryUntilANewSession() = runTest {
        val denied = FakeApi().apply { failure = SubsonicError.Unauthorized() }
        val c = coordinator(); c.setSession(ScrobbleSession("k", denied))
        c.onEvent(completed("a")); finishRetries()
        assertEquals(1, outbox().all().size)
        val api = FakeApi(); c.setSession(ScrobbleSession("k", api)); runCurrent()
        assertEquals(listOf("a"), api.sent(true).map { it.songId })
    }

    @Test fun signingOutCancelsNowPlayingButKeepsQueuedPlays() = runTest {
        val api = FakeApi().apply { failure = IOException() }
        val c = coordinator(); c.setSession(ScrobbleSession("k", api))
        c.onEvent(started("s1")); c.onEvent(completed("a")); runCurrent()
        c.setSession(null); runCurrent()
        val seen = api.attempts.size; finishRetries()
        assertEquals(seen, api.attempts.size)
        assertEquals(1, outbox().all().size)
        c.onEvent(completed("b")); runCurrent()
        assertEquals(1, outbox().all().size)
    }

    @Test fun blankSongIdIsIgnored() = runTest {
        val api = FakeApi(); val c = coordinator(); c.setSession(ScrobbleSession("k", api))
        c.onEvent(completed(" ")); c.onEvent(started(" ")); runCurrent()
        assertTrue(api.calls.isEmpty()); assertTrue(outbox().all().isEmpty())
    }

    @Test fun ambiguousResponsesRetryAndEventuallySubmitWithOriginalTimestamp() = runTest {
        for (error in listOf(SubsonicError.Decoding(), SubsonicError.Server(0, null))) {
            val api = FakeApi().apply { failure = error }
            val c = coordinator(); c.setSession(ScrobbleSession("k", api)); runCurrent()
            clockMs = 42; c.onEvent(completed("a")); finishRetries()
            assertEquals(42L, outbox().all().single().playedAtMs)
            assertEquals(3, api.attempts.size)
            api.failure = null; clockMs = 90_000
            c.onNetworkAvailable(); runCurrent()
            assertEquals(listOf(Call("a", true, 42)), api.sent(true))
            assertTrue(api.attempts.all { it.timeMs == 42L })
            assertTrue(outbox().all().isEmpty())
            c.setSession(null); runCurrent()
        }
    }

    @Test fun failedWritesDoNotKillFanOutAndRetryWithOriginalTimesAfterSignOut() = runTest {
        val store = FaultStore(outbox()).apply { failAdd = true }
        val api = FakeApi(); val c = coordinator(store); c.setSession(ScrobbleSession("k", api)); runCurrent()
        val engine = FakeAudioEngine(); val player = PlaybackController(engine)
        val otherSink = mutableListOf<PlaybackEvent>()
        val dispatcher = PlaybackEventDispatcher(player, backgroundScope, listOf(c::onEvent, { otherSink += it }))
        clockMs = 42; player.replaceQueue(listOf(QueueEntry("a"))); engine.finish(); finishRetries()
        assertEquals(1, otherSink.filterIsInstance<PlaybackEvent.Completed>().size)
        assertTrue(store.disk.all().isEmpty())
        assertTrue(api.sent(true).isEmpty()) // Persist before sending.
        c.setSession(null); runCurrent()
        store.failAdd = false; clockMs = 90_000
        c.onNetworkAvailable(); runCurrent() // Can persist even while signed out.
        assertEquals(42L, store.disk.all().single().playedAtMs)
        c.setSession(ScrobbleSession("k", api)); runCurrent()
        assertEquals(listOf(Call("a", true, 42)), api.sent(true))
        assertTrue(store.disk.all().isEmpty())
        dispatcher.release(); player.release()
    }

    @Test fun failedRemovalRetriesWithoutResendingAcknowledgedPlayOrBlockingTheNext() = runTest {
        val store = FaultStore(outbox()).apply { failRemove = true }
        val api = FakeApi(); val c = coordinator(store); c.setSession(ScrobbleSession("k", api)); runCurrent()
        c.onEvent(completed("a")); c.onEvent(completed("b")); finishRetries()
        assertEquals(listOf("a"), api.sent(true).map { it.songId })
        assertEquals(listOf("a", "b"), store.disk.all().map { it.songId })
        store.failRemove = false
        c.onNetworkAvailable(); runCurrent()
        assertEquals(listOf("a", "b"), api.sent(true).map { it.songId })
        assertTrue(store.disk.all().isEmpty())
    }

    @Test fun flushNowRemovalFailureNeverResendsTheAcceptedPlay() = runTest {
        // Sol P2: the sign-out flush shares the worker's acknowledgement handling.
        val store = FaultStore(outbox())
        store.disk.add(PendingScrobble(serverKey = "k", songId = "a", playedAtMs = clockMs), clockMs)
        store.disk.add(PendingScrobble(serverKey = "k", songId = "b", playedAtMs = clockMs), clockMs)
        val api = FakeApi(); val c = coordinator(store)
        c.setSession(ScrobbleSession("k", api))
        store.failRemove = true
        runCatching { c.flushNow() }
        assertEquals(listOf("a"), api.sent(true).map { it.songId })
        store.failRemove = false
        c.flushNow(); finishRetries()
        assertEquals("a was accepted once and never sent again", listOf("a", "b"), api.sent(true).map { it.songId })
        assertTrue(store.disk.all().isEmpty())
    }

    @Test fun accountWipeDropsAFailedWriteOfTheOldAccount() = runTest {
        // Sol P2: a write that failed before the wipe must not restore the old account's play afterwards.
        val store = FaultStore(outbox()).apply { failAdd = true }
        val api = FakeApi(); val c = coordinator(store); c.setSession(ScrobbleSession("old", api)); runCurrent()
        c.onEvent(completed("a")); runCurrent()
        c.setSession(null); runCurrent()
        c.retainOnly("new")
        store.failAdd = false
        c.onNetworkAvailable(); finishRetries()
        assertTrue(store.disk.all().isEmpty())
        c.setSession(ScrobbleSession("old", api)); finishRetries()
        assertTrue("never sent", api.sent(true).isEmpty())
    }

    @Test fun accountWipeWaitsForAWriteInFlight() = runTest {
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val disk = outbox()
        val store = object : ScrobbleStore by disk {
            override fun add(entry: PendingScrobble, nowMs: Long) { entered.countDown(); release.await(); disk.add(entry, nowMs) }
        }
        val c = ScrobbleCoordinator(backgroundScope, store, { clockMs }, Dispatchers.IO, listOf(100, 200))
        c.setSession(ScrobbleSession("old", FakeApi())); runCurrent()
        c.onEvent(completed("a")); runCurrent()
        assertTrue(withContext(Dispatchers.IO) { entered.await(5, java.util.concurrent.TimeUnit.SECONDS) })
        c.setSession(null)
        val wipe = backgroundScope.launch { c.retainOnly("new") }
        runCurrent()
        release.countDown()
        wipe.join()
        assertTrue("the old account's play committed mid-wipe is gone", disk.all().isEmpty())
    }

    @Test fun anInFlightSubmissionCannotDelayPersistenceOfLaterCompletions() = runTest {
        val blocked = CompletableDeferred<Unit>()
        val api = ScrobbleApi { _, _, _ -> blocked.await() }
        val c = coordinator(); c.setSession(ScrobbleSession("k", api)); runCurrent()
        clockMs = 42; c.onEvent(completed("a")); runCurrent()
        clockMs = 43; c.onEvent(completed("b")); runCurrent()
        assertEquals(listOf("a" to 42L, "b" to 43L), outbox().all().map { it.songId to it.playedAtMs })
        c.setSession(null); runCurrent()
    }

    @Test fun pruningFailureRetainsEntriesAndDoesNotKillWorker() = runTest {
        val store = FaultStore(outbox()).apply { failPrune = true }
        store.disk.add(PendingScrobble("old", "k", "old", 0), 0)
        clockMs = ScrobblePolicy.OUTBOX_MAX_AGE_MS + 1
        val api = FakeApi(); val c = coordinator(store); c.setSession(ScrobbleSession("k", api)); finishRetries()
        assertEquals(1, store.disk.all().size)
        assertTrue(api.calls.isEmpty())
        store.failPrune = false; c.onNetworkAvailable(); runCurrent()
        assertTrue(store.disk.all().isEmpty())
        c.onEvent(completed("fresh")); runCurrent()
        assertEquals(listOf("fresh"), api.sent(true).map { it.songId })
    }

    @Test fun completionCancelsNowPlayingDuringBackoff() = runTest {
        val api = FakeApi().apply { failure = IOException() }
        val c = coordinator(); c.setSession(ScrobbleSession("k", api))
        c.onEvent(started("a")); runCurrent()
        c.onEvent(completed("a")); runCurrent()
        api.failure = null; finishRetries()
        assertTrue(api.sent(false).isEmpty())
        assertEquals(listOf("a"), api.sent(true).map { it.songId })
    }

    @Test fun clearStopFailureAndServiceReleaseCancelNowPlayingThroughFanOut() = runTest {
        for (end in listOf<(PlaybackController, FakeAudioEngine) -> Unit>(
            { p, _ -> p.clear() }, { p, _ -> p.stop() },
            { _, e -> e.emit(EngineEvent.Failed(e.current!!.occurrence, PlaybackFailure.Network)) },
            { p, _ -> p.release() },
            { p, _ -> p.pause(); p.seek(1, 0) },
        )) {
            val api = FakeApi().apply { failure = IOException() }
            val c = coordinator(); c.setSession(ScrobbleSession("k", api))
            val engine = FakeAudioEngine(); val player = PlaybackController(engine)
            val otherSink = mutableListOf<PlaybackEvent>()
            val dispatcher = PlaybackEventDispatcher(player, backgroundScope, listOf(c::onEvent, { otherSink += it }))
            player.replaceQueue(listOf(QueueEntry("a"), QueueEntry("b"))); runCurrent()
            assertEquals(1, api.attempts.size)
            end(player, engine); runCurrent()
            api.failure = null; finishRetries()
            assertTrue(api.calls.isEmpty())
            assertTrue(otherSink.contains(PlaybackEvent.OccurrenceEnded))
            dispatcher.release(); player.release(); c.setSession(null); runCurrent()
        }
    }

    @Test fun finalCompletionCancelsNowPlayingThroughFanOut() = runTest {
        val api = FakeApi().apply { failure = IOException() }
        val c = coordinator(); c.setSession(ScrobbleSession("k", api))
        val engine = FakeAudioEngine(); val player = PlaybackController(engine)
        val dispatcher = PlaybackEventDispatcher(player, backgroundScope, listOf(c::onEvent))
        player.replaceQueue(listOf(QueueEntry("a"))); runCurrent()
        engine.finish(); runCurrent()
        api.failure = null; finishRetries()
        assertTrue(api.sent(false).isEmpty())
        assertEquals(listOf("a"), api.sent(true).map { it.songId })
        dispatcher.release(); player.release()
    }

    /** Runs blocking IO only when instructed, independently of the confined main scheduler. */
    private class QueuedIo : CoroutineDispatcher() {
        val work = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { work.addLast(block) }
        fun runOne() { work.removeFirst().run() }
        fun runLast() { work.removeLast().run() }
    }

    @Test fun completionWakeDuringEmptyLookupReturnIsNotLost() = runTest {
        val main = QueuedIo(); val io = QueuedIo(); val api = FakeApi()
        val owner = CoroutineScope(SupervisorJob() + main)
        val c = ScrobbleCoordinator(owner, outbox(), { clockMs }, io)
        try {
            c.setSession(ScrobbleSession("k", api))
            repeat(10) { if (main.work.isNotEmpty()) main.runOne() }
            assertEquals(1, io.work.size)
            io.runOne() // Empty peek finished; hold its return to main throughout persistence.
            c.onEvent(completed("a"))
            main.runLast() // Run the persistence worker ahead of the held empty-lookup return.
            io.runOne() // Addition commits to disk.
            main.runLast() // Addition completes and wakes the still-active old flush job.
            assertEquals(1, outbox().all().size)
            assertEquals(1, main.work.size) // Only the held empty-lookup return remains.
            assertTrue(api.calls.isEmpty())
            repeat(30) {
                if (main.work.isNotEmpty()) main.runOne()
                if (io.work.isNotEmpty()) io.runOne()
            }
            assertEquals(listOf(Call("a", true, clockMs)), api.sent(true))
            assertTrue(outbox().all().isEmpty())
        } finally {
            owner.cancel()
            repeat(10) { if (main.work.isNotEmpty()) main.runOne() }
        }
    }

    @Test fun sessionChangeAfterDiskCommitBeforeIoReturnDoesNotDuplicateOrCrossAccounts() = runTest {
        val io = QueuedIo(); val other = FakeApi()
        val c = ScrobbleCoordinator(backgroundScope, outbox(), { clockMs }, io)
        c.setSession(ScrobbleSession("one", FakeApi())); runCurrent()
        io.runOne(); runCurrent() // Initial empty lookup.
        clockMs = 42; c.onEvent(completed("a")); runCurrent()
        io.runOne() // add committed, but the main-thread continuation has not removed pendingWrites.
        c.setSession(ScrobbleSession("two", other)); runCurrent()
        repeat(10) { if (io.work.isNotEmpty()) io.runOne(); runCurrent() }
        assertTrue(other.calls.isEmpty())
        assertEquals(1, outbox().all().size)
        val original = FakeApi(); c.setSession(ScrobbleSession("one", original)); runCurrent()
        repeat(10) { if (io.work.isNotEmpty()) io.runOne(); runCurrent() }
        assertEquals(listOf(Call("a", true, 42)), original.sent(true))
        assertTrue(outbox().all().isEmpty())
    }

    // Late delivery across an account switch (same mechanism as AlbumResumeWriteTest): the controller stamps each
    // event with the session signed in when it happened, and the coordinator drops a stamp that is not its session.
    private fun TestScope.parkedFanOut(sessionOf: () -> Any?, park: (PlaybackEvent) -> Boolean, c: ScrobbleCoordinator,
                                       delivered: MutableList<PlaybackEvent>, gate: CompletableDeferred<Unit>): Pair<PlaybackController, FakeAudioEngine> {
        val engine = FakeAudioEngine()
        val controller = PlaybackController(engine, sessionGeneration = sessionOf)
        PlaybackEventDispatcher(controller, backgroundScope, listOf(
            { e -> if (park(e)) { delivered += e; gate.await() } },
            { e -> c.onEvent(e) }))
        return controller to engine
    }

    @Test fun completionDeliveredAfterAnAccountSwitchIsNotScrobbledForTheNewAccount() = runTest {
        var current: Any? = "t1"
        val old = FakeApi(); val next = FakeApi()
        val c = coordinator(); c.setSession(ScrobbleSession("k1", old, "t1"))
        val delivered = mutableListOf<PlaybackEvent>(); val gate = CompletableDeferred<Unit>()
        val (controller, engine) = parkedFanOut({ current }, { it is PlaybackEvent.Completed }, c, delivered, gate)
        controller.replaceQueue(listOf(QueueEntry("track-1"), QueueEntry("track-2")))
        engine.emit(EngineEvent.Advanced(engine.next!!.occurrence))
        runCurrent()
        assertEquals("t1", (delivered.single() as PlaybackEvent.Completed).playback.sessionGeneration)
        current = "t2"
        c.setSession(ScrobbleSession("k2", next, "t2"))
        gate.complete(Unit); runCurrent(); finishRetries()
        assertTrue(old.sent(true).isEmpty()); assertTrue(next.calls.isEmpty())
        assertTrue(outbox().all().isEmpty())
    }

    @Test fun nowPlayingDeliveredAfterAnAccountSwitchIsNotSentForTheNewAccount() = runTest {
        var current: Any? = "t1"
        val old = FakeApi(); val next = FakeApi()
        val c = coordinator(); c.setSession(ScrobbleSession("k1", old, "t1"))
        val delivered = mutableListOf<PlaybackEvent>(); val gate = CompletableDeferred<Unit>()
        val (controller, _) = parkedFanOut({ current }, { it is PlaybackEvent.OccurrenceStarted }, c, delivered, gate)
        controller.replaceQueue(listOf(QueueEntry("track-1")))
        runCurrent()
        assertEquals("t1", (delivered.single() as PlaybackEvent.OccurrenceStarted).sessionGeneration)
        current = "t2"
        c.setSession(ScrobbleSession("k2", next, "t2"))
        gate.complete(Unit); runCurrent(); finishRetries()
        assertTrue(old.calls.isEmpty()); assertTrue(next.calls.isEmpty())
    }

    @Test fun matchingUnstampedAndUnjudgedEventsStillScrobbleButSignedOutAndOldStampsDoNot() = runTest {
        val api = FakeApi()
        val c = coordinator(); c.setSession(ScrobbleSession("k", api, "t1"))
        fun done(id: String, stamp: Any?) = PlaybackEvent.Completed(CompletedPlayback(id, null, sessionGeneration = stamp))
        c.onEvent(done("match", "t1"))
        c.onEvent(completed("unstamped"))                       // UnknownSession
        c.onEvent(done("signedout", null))
        c.onEvent(done("old", "t0"))
        c.onEvent(PlaybackEvent.OccurrenceStarted(QueueEntry("np-old"), "t0"))
        c.onEvent(PlaybackEvent.OccurrenceStarted(QueueEntry("np-ok"), "t1"))
        runCurrent()
        assertEquals(listOf("match", "unstamped"), api.sent(true).map { it.songId })
        assertEquals(listOf("np-ok"), api.sent(false).map { it.songId })
        // A coordinator session that was not given a generation cannot judge: it keeps the old behaviour.
        val plain = FakeApi(); val d = coordinator(); d.setSession(ScrobbleSession("k", plain))
        d.onEvent(done("any", "t9")); runCurrent()
        assertEquals(listOf("any"), plain.sent(true).map { it.songId })
    }

    // ---- BAT-2: screen off and playing ----

    private var quiet = true
    private fun TestScope.quietCoordinator(store: ScrobbleStore = outbox(), scope: CoroutineScope = backgroundScope) =
        ScrobbleCoordinator(scope, store, { clockMs }, StandardTestDispatcher(testScheduler, "IO"), listOf(100, 200),
            sendsNowPlaying = { !quiet }, defersSubmissions = { quiet }, elapsedMs = { testScheduler.currentTime })

    @Test fun bat2ScreenOffDropsNowPlayingAndBatchesPlaysUntilTheBoundary() = runTest {
        val api = FakeApi(); val c = quietCoordinator(); c.setSession(ScrobbleSession("k", api)); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        clockMs = 100; c.onEvent(started("a")); c.onEvent(completed("a"))
        clockMs = 200; c.onEvent(started("b")); c.onEvent(completed("b")); runCurrent()
        assertTrue("no now-playing and nothing sent yet", api.calls.isEmpty())
        assertEquals("persisted at once", listOf("a", "b"), outbox().all().map { it.songId })
        c.flushBatch(); runCurrent()
        assertEquals(listOf(Call("a", true, 100), Call("b", true, 200)), api.calls)
        c.flushBatch(); c.onNetworkAvailable(); runCurrent()
        assertEquals("exactly once", 2, api.calls.size)
        assertTrue(outbox().all().isEmpty())
        quiet = false
        clockMs = 300; c.onEvent(started("c")); runCurrent()
        assertEquals("screen on: now playing again", Call("c", false, 300), api.calls.last())
    }

    @Test fun bat2PlayPersistedRightAfterTheBoundaryRidesAlongLaterOnesWait() = runTest {
        val api = FakeApi(); val c = quietCoordinator(); c.setSession(ScrobbleSession("k", api)); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        c.flushBatch(); runCurrent()
        clockMs = 10; c.onEvent(completed("a")); runCurrent()
        assertEquals("within the piggyback window: sent with the boundary", listOf("a"), api.sent(true).map { it.songId })
        advanceTimeBy(6_000); runCurrent()
        clockMs = 20; c.onEvent(completed("b")); runCurrent()
        assertEquals(1, api.calls.size)
        advanceTimeBy(dev.coda.core.playback.BackgroundWorkPolicy.SCROBBLE_BATCH_MS - 1_000); runCurrent()
        assertEquals("waits for a boundary", 1, api.calls.size)
        advanceTimeBy(2_000); runCurrent()
        assertEquals("at most ~10 min later", listOf(Call("a", true, 10), Call("b", true, 20)), api.sent(true))
        assertTrue(outbox().all().isEmpty())
    }

    @Test fun bat2BatchedPlaysSurviveProcessDeathAndGoOutOnceInOrder() = runTest {
        val firstScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val api = FakeApi()
        val first = quietCoordinator(scope = firstScope); first.setSession(ScrobbleSession("k", api)); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        clockMs = 41; first.onEvent(completed("a"))
        clockMs = 42; first.onEvent(completed("b")); runCurrent()
        assertTrue(api.calls.isEmpty())
        firstScope.cancel(); runCurrent()   // process death before the batch went out
        val second = quietCoordinator(); second.setSession(ScrobbleSession("k", api)); runCurrent()
        assertEquals("a restart (session set) sends the outbox at once", listOf(Call("a", true, 41), Call("b", true, 42)), api.calls)
        second.flushBatch(); runCurrent()
        assertEquals(2, api.calls.size)
    }

    @Test fun bat2BatchOfflineStaysQueuedThenFlushesInOrder() = runTest {
        val api = FakeApi().apply { failure = SubsonicError.Network(IOException()) }
        val c = quietCoordinator(); c.setSession(ScrobbleSession("k", api)); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        clockMs = 1; c.onEvent(completed("a"))
        clockMs = 2; c.onEvent(completed("b")); runCurrent()
        c.flushBatch(); finishRetries()
        assertEquals(listOf("a", "b"), outbox().all().map { it.songId })
        assertTrue(api.calls.isEmpty())
        clockMs = 3; c.onEvent(completed("c")); runCurrent()
        api.failure = null
        c.onNetworkAvailable(); runCurrent()
        assertEquals(listOf(Call("a", true, 1), Call("b", true, 2), Call("c", true, 3)), api.calls)
        assertTrue(outbox().all().isEmpty())
    }
}
