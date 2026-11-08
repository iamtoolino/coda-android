package dev.coda.core.sync

import dev.coda.core.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The local copy of our last queue (queue, index, position). Synthetic data only. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalQueueCopyTest {
    @get:Rule val folder = TemporaryFolder()

    private val full = QueueEntry("song-1", title = "Synthetic One", artist = "Synthetic Artist", album = "Synthetic Album",
        albumId = "album-1", coverArtId = "cover-1", trackNumber = 3, discNumber = 2, durationMs = 91_000, albumRating = 4,
        formatName = "FLAC", bitRate = 900, replayGain = ReplayGainMetadata(-6.5, -7.0, 0.9, 0.95),
        albumContinuationId = "album-1", year = 2024, albumArtist = "Synthetic Band")

    @Test fun roundTripKeepsQueueIndexPositionAndMetadata() {
        val store = LocalQueueStore(java.io.File(folder.root, "queue/account.json"))
        val queue = OwnQueue(listOf(full, QueueEntry("song-2", title = "Two")), 1, 33_000)
        store.write(LocalQueueCopy.of(queue))
        val read = LocalQueueStore(store.file).read()!!.toOwnQueue()!!
        assertEquals(listOf(full.copy(id = read.entries[0].id), QueueEntry("song-2", id = read.entries[1].id, title = "Two")), read.entries)
        assertEquals(1, read.currentIndex)
        assertEquals(33_000L, read.positionMs)
        assertNull("no partial file left", java.io.File(folder.root, "queue/account.json.tmp").takeIf { it.exists() })
    }

    @Test fun emptyCopyDeletesAndDamagedCopyIsNoCopy() {
        val store = LocalQueueStore(java.io.File(folder.root, "account.json"))
        assertNull(store.read())
        store.write(LocalQueueCopy.of(OwnQueue(listOf(full), 0, 1)))
        store.write(LocalQueueCopy.EMPTY)
        assertFalse(store.file.exists())
        store.file.writeText("{not json")
        assertNull(store.read())
        // A stale index from an older copy is clamped, never out of range.
        assertEquals(0, LocalQueueCopy(listOf(LocalQueueCopy.Entry.of(full)), 7, -5).toOwnQueue()!!.let { it.currentIndex + it.positionMs.toInt() })
    }

    private fun TestScope.immediate() = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    /** Written where the server queue is saved: on the pause save and on exit, also when the server cannot be reached. */
    @Test fun coordinatorWritesTheCopyWithEverySaveEvenOffline() = runTest {
        val player = PlaybackController(FakeAudioEngine())
        val copies = mutableListOf<LocalQueueCopy>()
        var serverSaves = 0
        val coordinator = QueueHandoffCoordinator(player, immediate(), "Coda on Android", { null },
            { serverSaves++; throw java.io.IOException("offline") }, { copies += it })
        runCurrent()
        player.replaceQueue(listOf(full, QueueEntry("song-2", title = "Two")), 1, 12_345, startsPlaying = false)
        runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals("server retried", 3, serverSaves)
        assertEquals("one local write per capture", 1, copies.size)
        val own = copies.single().toOwnQueue()!!
        assertEquals(listOf("song-1", "song-2"), own.entries.map { it.songId })
        assertEquals(1, own.currentIndex); assertEquals(12_345L, own.positionMs)
        assertEquals(full.replayGain, own.entries[0].replayGain)
        val exit = coordinator.terminationSave()!!
        exit()
        assertEquals(2, copies.size)
        coordinator.close()
    }

    /** The entries a start request installs: tagged with the token `expectStart()` returned (what `AutoLibraryCallback` puts on its items). */
    private fun installed(token: Long, vararg songs: String) = songs.map { QueueEntry(it, startToken = token) }

    // "last played wins", but the server (and the copy) are written only once playback has
    // actually started; a request whose queue never plays leaves the Mac's saved queue alone.
    @Test fun autoStartSavesOnlyOncePlaybackHasStarted() = runTest {
        // Never started (failed prepare, aborted voice command): nothing is saved, nothing is taken over.
        run {
            val player = PlaybackController(FakeAudioEngine())
            val saves = mutableListOf<QueueHandoffSnapshot>(); val copies = mutableListOf<LocalQueueCopy>()
            val coordinator = QueueHandoffCoordinator(player, immediate(), "Coda on Android", { null }, { saves += it }, { copies += it })
            runCurrent()
            val token = coordinator.expectStart()
            player.replaceQueue(installed(token, "a", "b"), 1, 5_000, startsPlaying = false)
            runCurrent(); advanceTimeBy(QueueHandoffCoordinator.START_WINDOW_MS + 1_000); runCurrent()
            assertTrue("server untouched", saves.isEmpty()); assertTrue("copy untouched", copies.isEmpty())
            assertNull("no exit save either", coordinator.terminationSave())
            coordinator.close()
        }
        // Started: saved like any phone playback (the pause save then keeps the position).
        run {
            val engine = FakeAudioEngine()
            val player = PlaybackController(engine)
            val saves = mutableListOf<QueueHandoffSnapshot>(); val copies = mutableListOf<LocalQueueCopy>()
            val coordinator = QueueHandoffCoordinator(player, immediate(), "Coda on Android", { null }, { saves += it }, { copies += it })
            runCurrent()
            val token = coordinator.expectStart()
            player.replaceQueue(installed(token, "a", "b"), 1, 5_000, startsPlaying = false)
            runCurrent(); advanceTimeBy(5_000); runCurrent()
            assertTrue("waiting for playback", saves.isEmpty())
            player.play(); runCurrent(); advanceTimeBy(1_000); runCurrent()
            assertTrue("the play intent alone is not playback", saves.isEmpty())
            engine.emit(EngineEvent.State(engine.current!!.occurrence, true, false)); runCurrent(); advanceTimeBy(1_000); runCurrent()
            assertEquals(1, saves.size); assertEquals(listOf("a", "b"), saves.single().songIDs); assertEquals(1, saves.single().currentIndex)
            assertEquals(1, copies.size)
            coordinator.close()
        }
        // A started request whose queue is already playing in the same step is saved at once, as before.
        run {
            val engine = FakeAudioEngine()
            val player = PlaybackController(engine)
            val saves = mutableListOf<QueueHandoffSnapshot>()
            val coordinator = QueueHandoffCoordinator(player, immediate(), "Coda on Android", { null }, { saves += it })
            runCurrent()
            val token = coordinator.expectStart()
            player.replaceQueue(installed(token, "a", "b"), 0, 0, startsPlaying = true)
            engine.emit(EngineEvent.State(engine.current!!.occurrence, true, false))
            runCurrent(); advanceTimeBy(1_000); runCurrent()
            assertEquals(1, saves.size)
            coordinator.close()
        }
    }

    // The play intent (`isPlaying` = playWhenReady) is true while buffering, offline or about to fail. Nothing is saved
    // (server or copy, by any path: queue change, periodic, pause, exit) until the engine is really playing the new queue.
    @Test fun playIntentWithoutEnginePlaybackSavesNothing() = runTest {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        val saves = mutableListOf<QueueHandoffSnapshot>(); val copies = mutableListOf<LocalQueueCopy>()
        // The player held an earlier queue of ours (ownership is LOCAL): the periodic and pause saves must not carry the new one yet.
        val coordinator = QueueHandoffCoordinator(player, immediate(), "Coda on Android", { null }, { saves += it }, { copies += it })
        runCurrent()
        player.replaceQueue(listOf(QueueEntry("old")), startsPlaying = false)
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        saves.clear(); copies.clear()
        val token = coordinator.expectStart()
        player.replaceQueue(installed(token, "a", "b"), 1, 5_000, startsPlaying = true)          // intent: playing
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertTrue(player.isPlaying)
        engine.emit(EngineEvent.State(engine.current!!.occurrence, false, true)) // buffering, no audio
        runCurrent(); advanceTimeBy(31_000); runCurrent()                       // two periodic saves would have fired
        assertTrue("buffering is not playback: ${saves.map { it.songIDs }}", saves.isEmpty()); assertTrue(copies.isEmpty())
        assertNull("no exit save while waiting", coordinator.terminationSave())
        // Past the start window the player still wants to play (waiting for the network): still not saved.
        advanceTimeBy(QueueHandoffCoordinator.START_WINDOW_MS * 2); runCurrent()
        assertTrue(saves.isEmpty())
        // The network comes back and audio starts: now it is saved.
        engine.emit(EngineEvent.State(engine.current!!.occurrence, true, false)); runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("a", "b"), saves.last().songIDs); assertEquals(1, saves.last().currentIndex)
        assertEquals(listOf("a", "b"), copies.last().entries.map { it.songId })
        coordinator.close()
    }

    @Test fun aFailedStartNeverSavesEvenAfterTheWindow() = runTest {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        val saves = mutableListOf<QueueHandoffSnapshot>(); val copies = mutableListOf<LocalQueueCopy>()
        val coordinator = QueueHandoffCoordinator(player, immediate(), "Coda on Android", { null }, { saves += it }, { copies += it })
        runCurrent()
        val token = coordinator.expectStart()
        player.replaceQueue(installed(token, "a"), 0, 0, startsPlaying = true)
        engine.emit(EngineEvent.Failed(engine.current!!.occurrence, PlaybackFailure.Decode))
        runCurrent(); advanceTimeBy(QueueHandoffCoordinator.START_WINDOW_MS * 2); runCurrent()
        assertFalse(player.isPlaying)
        assertTrue(saves.isEmpty()); assertTrue(copies.isEmpty()); assertNull(coordinator.terminationSave())
        coordinator.close()
    }

    // The wait ending (failure, pause before audio, expiry) must not make the unplayed queue saveable when an earlier
    // queue of ours already left ownership LOCAL. Only real playback of that very queue makes it eligible, even much later.
    private fun TestScope.localThenStart(engine: FakeAudioEngine, saves: MutableList<QueueHandoffSnapshot>, copies: MutableList<LocalQueueCopy>,
        startsPlaying: Boolean): Pair<PlaybackController, QueueHandoffCoordinator> {
        val player = PlaybackController(engine)
        val coordinator = QueueHandoffCoordinator(player, immediate(), "Coda on Android", { null }, { saves += it }, { copies += it })
        runCurrent()
        player.replaceQueue(listOf(QueueEntry("old")), startsPlaying = false)   // ownership is LOCAL (e.g. after clearing a queue)
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        saves.clear(); copies.clear()
        val token = coordinator.expectStart()
        player.replaceQueue(installed(token, "a", "b"), 1, 5_000, startsPlaying = startsPlaying)
        return player to coordinator
    }

    @Test fun failedStartKeepsLocalOwnershipFromSavingTheCandidate() = runTest {
        val engine = FakeAudioEngine(); val saves = mutableListOf<QueueHandoffSnapshot>(); val copies = mutableListOf<LocalQueueCopy>()
        val (player, coordinator) = localThenStart(engine, saves, copies, startsPlaying = true)
        engine.emit(EngineEvent.Failed(engine.current!!.occurrence, PlaybackFailure.Decode))
        runCurrent(); advanceTimeBy(QueueHandoffCoordinator.START_WINDOW_MS * 2); runCurrent()
        advanceTimeBy(31_000); runCurrent()                                      // periodic saves
        assertFalse(player.isPlaying)
        assertTrue("${saves.map { it.songIDs }}", saves.isEmpty()); assertTrue(copies.isEmpty())
        assertNull("exit save", coordinator.terminationSave())
        coordinator.close()
    }

    @Test fun expiredStartPausedBeforeAudioDoesNotSaveTheCandidate() = runTest {
        val engine = FakeAudioEngine(); val saves = mutableListOf<QueueHandoffSnapshot>(); val copies = mutableListOf<LocalQueueCopy>()
        val (player, coordinator) = localThenStart(engine, saves, copies, startsPlaying = false)
        runCurrent(); advanceTimeBy(QueueHandoffCoordinator.START_WINDOW_MS + 1_000); runCurrent()
        player.play(); runCurrent(); player.pause(); runCurrent(); advanceTimeBy(2_000); runCurrent()   // paused before any audio
        assertTrue("${saves.map { it.songIDs }}", saves.isEmpty()); assertTrue(copies.isEmpty())
        assertNull("exit save", coordinator.terminationSave())
        coordinator.close()
    }

    @Test fun anAbandonedCandidateThatLaterReallyPlaysIsSaved() = runTest {
        val engine = FakeAudioEngine(); val saves = mutableListOf<QueueHandoffSnapshot>(); val copies = mutableListOf<LocalQueueCopy>()
        val (player, coordinator) = localThenStart(engine, saves, copies, startsPlaying = false)
        runCurrent(); advanceTimeBy(QueueHandoffCoordinator.START_WINDOW_MS + 1_000); runCurrent()
        assertTrue(saves.isEmpty()); assertNull(coordinator.terminationSave())
        player.play(); runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertTrue("intent alone is not playback", saves.isEmpty())
        engine.emit(EngineEvent.State(engine.current!!.occurrence, true, false)); runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("a", "b"), saves.last().songIDs); assertEquals(listOf("a", "b"), copies.last().entries.map { it.songId })
        assertNotNull("normal saves are not wedged", coordinator.terminationSave())
        coordinator.close()
    }

    @Test fun anotherQueueAfterAnAbandonedCandidateSavesNormally() = runTest {
        val engine = FakeAudioEngine(); val saves = mutableListOf<QueueHandoffSnapshot>(); val copies = mutableListOf<LocalQueueCopy>()
        val (player, coordinator) = localThenStart(engine, saves, copies, startsPlaying = false)
        runCurrent(); advanceTimeBy(QueueHandoffCoordinator.START_WINDOW_MS + 1_000); runCurrent()
        player.replaceQueue(listOf(QueueEntry("x")), startsPlaying = false)       // the user picks something else
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("x"), saves.last().songIDs)
        coordinator.close()
    }

    // The gate is bound to the queue the start request installed. A different queue installed before the window
    // ends (the user clears and picks something else after a failed start) releases it at once; nothing stays wedged.
    @Test fun replacementBeforeExpiryAfterAFailedStartIsSavedNormally() = runTest {
        val engine = FakeAudioEngine(); val saves = mutableListOf<QueueHandoffSnapshot>(); val copies = mutableListOf<LocalQueueCopy>()
        val (player, coordinator) = localThenStart(engine, saves, copies, startsPlaying = true)
        engine.emit(EngineEvent.Failed(engine.current!!.occurrence, PlaybackFailure.Decode))
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertTrue("the failed candidate saves nothing", saves.isEmpty())
        player.clear(); runCurrent()
        player.replaceQueue(listOf(QueueEntry("x"), QueueEntry("y")), startsPlaying = false)   // still inside the 60 s window
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals("pause save of the replacement", listOf("x", "y"), saves.last().songIDs)
        assertEquals(listOf("x", "y"), copies.last().entries.map { it.songId })
        advanceTimeBy(QueueHandoffCoordinator.START_WINDOW_MS + 1_000); runCurrent()          // expiry must not re-hold it
        val exit = coordinator.terminationSave()
        assertNotNull("exit save of the replacement", exit)
        saves.clear(); exit!!.invoke()
        assertEquals(listOf("x", "y"), saves.single().songIDs)
        coordinator.close()
    }

    @Test fun replacementWithoutClearingBeforeExpiryAlsoReleasesTheGate() = runTest {
        val engine = FakeAudioEngine(); val saves = mutableListOf<QueueHandoffSnapshot>(); val copies = mutableListOf<LocalQueueCopy>()
        val (player, coordinator) = localThenStart(engine, saves, copies, startsPlaying = false)
        runCurrent()
        player.replaceQueue(listOf(QueueEntry("x")), startsPlaying = true)
        engine.emit(EngineEvent.State(engine.current!!.occurrence, true, false))
        runCurrent(); advanceTimeBy(QueueHandoffCoordinator.START_WINDOW_MS + 1_000); runCurrent(); advanceTimeBy(16_000); runCurrent()
        assertEquals(listOf("x"), saves.last().songIDs)
        player.pause(); runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertNotNull(coordinator.terminationSave())
        coordinator.close()
    }

    @Test fun editingTheCandidateBeforePlaybackIsSaved() = runTest {
        val engine = FakeAudioEngine(); val saves = mutableListOf<QueueHandoffSnapshot>(); val copies = mutableListOf<LocalQueueCopy>()
        val (player, coordinator) = localThenStart(engine, saves, copies, startsPlaying = false)
        runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertTrue(saves.isEmpty()); assertNull(coordinator.terminationSave())
        player.remove(player.queue[0].id)                                       // the user drops a track of the candidate
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("b"), saves.last().songIDs); assertEquals(listOf("b"), copies.last().entries.map { it.songId })
        // Same after the window ended.
        val (player2, coordinator2) = localThenStart(FakeAudioEngine(), saves, copies, startsPlaying = false)
        runCurrent(); advanceTimeBy(QueueHandoffCoordinator.START_WINDOW_MS + 1_000); runCurrent()
        assertNull(coordinator2.terminationSave())
        saves.clear(); player2.remove(player2.queue[1].id)
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("a"), saves.last().songIDs)
        coordinator.close(); coordinator2.close()
    }

    @Test fun theCandidateThatPlaysAfterAnEditAttemptIsStillOnlyHeldUntilItPlays() = runTest {
        val engine = FakeAudioEngine(); val saves = mutableListOf<QueueHandoffSnapshot>(); val copies = mutableListOf<LocalQueueCopy>()
        val (player, coordinator) = localThenStart(engine, saves, copies, startsPlaying = false)
        runCurrent(); advanceTimeBy(QueueHandoffCoordinator.START_WINDOW_MS + 1_000); runCurrent()
        assertTrue(saves.isEmpty())
        player.seek(0, 0); runCurrent(); advanceTimeBy(1_000); runCurrent()           // same queue, other position: still unplayed
        assertTrue("${saves.map { it.songIDs }}", saves.isEmpty()); assertNull(coordinator.terminationSave())
        player.play(); engine.emit(EngineEvent.State(engine.current!!.occurrence, true, false)); runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("a", "b"), saves.last().songIDs)
        coordinator.close()
    }

    // A queue is identified by its generation (an install tagged by the request's token), never by its songs. A later
    // queue, even one with the very same songs, is an ordinary queue.
    @Test fun sameSongsReplacementBeforeExpiryIsSavedNormally() = runTest {
        val engine = FakeAudioEngine(); val saves = mutableListOf<QueueHandoffSnapshot>(); val copies = mutableListOf<LocalQueueCopy>()
        val (player, coordinator) = localThenStart(engine, saves, copies, startsPlaying = true)
        engine.emit(EngineEvent.Failed(engine.current!!.occurrence, PlaybackFailure.Decode))
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertTrue("the failed candidate saves nothing", saves.isEmpty())
        player.replaceQueue(listOf(QueueEntry("a"), QueueEntry("b")), 1, 7_000, startsPlaying = false)   // fresh entries, same songs, inside the window
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals("replacement saved", listOf("a", "b"), saves.last().songIDs); assertEquals(listOf("a", "b"), copies.last().entries.map { it.songId })
        advanceTimeBy(QueueHandoffCoordinator.START_WINDOW_MS + 1_000); runCurrent()
        saves.clear(); val exit = coordinator.terminationSave()
        assertNotNull("exit save of the replacement", exit); exit!!.invoke()
        assertEquals(listOf("a", "b"), saves.single().songIDs)
        coordinator.close()
    }

    @Test fun sameSongsReplacementAfterExpiryIsSavedNormally() = runTest {
        val engine = FakeAudioEngine(); val saves = mutableListOf<QueueHandoffSnapshot>(); val copies = mutableListOf<LocalQueueCopy>()
        val (player, coordinator) = localThenStart(engine, saves, copies, startsPlaying = true)
        engine.emit(EngineEvent.Failed(engine.current!!.occurrence, PlaybackFailure.Decode))
        runCurrent(); advanceTimeBy(QueueHandoffCoordinator.START_WINDOW_MS + 1_000); runCurrent()
        assertTrue(saves.isEmpty()); assertNull("the unplayed candidate is held", coordinator.terminationSave())
        player.replaceQueue(listOf(QueueEntry("a"), QueueEntry("b")), 0, 0, startsPlaying = false)       // not the request's install
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("a", "b"), saves.last().songIDs); assertEquals(listOf("a", "b"), copies.last().entries.map { it.songId })
        advanceTimeBy(16_000); runCurrent()
        saves.clear(); val exit = coordinator.terminationSave()
        assertNotNull("exit save", exit); exit!!.invoke()
        assertEquals(listOf("a", "b"), saves.single().songIDs)
        coordinator.close()
    }

    @Test fun userEditRacingAheadOfTheInstallIsNotTheCandidate() = runTest {
        val engine = FakeAudioEngine(); val saves = mutableListOf<QueueHandoffSnapshot>(); val copies = mutableListOf<LocalQueueCopy>()
        val player = PlaybackController(engine)
        val coordinator = QueueHandoffCoordinator(player, immediate(), "Coda on Android", { null }, { saves += it }, { copies += it })
        runCurrent()
        player.replaceQueue(listOf(QueueEntry("old")), startsPlaying = false)
        runCurrent(); advanceTimeBy(1_000); runCurrent(); saves.clear(); copies.clear()
        val token = coordinator.expectStart()
        player.enqueue(listOf(QueueEntry("e")))                                   // the user's edit lands before the request installs
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals("the edit is an ordinary queue", listOf("old", "e"), saves.last().songIDs)
        // The request never installs anything: the edit stays saveable after the window and at exit.
        advanceTimeBy(QueueHandoffCoordinator.START_WINDOW_MS + 1_000); runCurrent()
        val exit = coordinator.terminationSave()
        assertNotNull("exit save of the edit", exit)
        // A late install of the request is still recognised by its token and held.
        player.replaceQueue(installed(token, "a", "b"), 0, 0, startsPlaying = false)
        runCurrent(); advanceTimeBy(2_000); runCurrent(); saves.clear()
        assertNull("the late install is held", coordinator.terminationSave()); assertTrue(saves.isEmpty())
        coordinator.close()
    }

    @Test fun userEditBeforeTheInstallThenTheInstallIsHeldNotTheEdit() = runTest {
        val engine = FakeAudioEngine(); val saves = mutableListOf<QueueHandoffSnapshot>(); val copies = mutableListOf<LocalQueueCopy>()
        val player = PlaybackController(engine)
        val coordinator = QueueHandoffCoordinator(player, immediate(), "Coda on Android", { null }, { saves += it }, { copies += it })
        runCurrent()
        player.replaceQueue(listOf(QueueEntry("old")), startsPlaying = false)
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        val token = coordinator.expectStart()
        player.enqueue(listOf(QueueEntry("e"))); runCurrent(); advanceTimeBy(1_000); runCurrent()
        saves.clear(); copies.clear()
        player.replaceQueue(installed(token, "a", "b"), 0, 0, startsPlaying = true)
        engine.emit(EngineEvent.Failed(engine.current!!.occurrence, PlaybackFailure.Decode))
        runCurrent(); advanceTimeBy(QueueHandoffCoordinator.START_WINDOW_MS * 2); runCurrent(); advanceTimeBy(16_000); runCurrent()
        assertTrue("${saves.map { it.songIDs }}", saves.isEmpty()); assertTrue(copies.isEmpty()); assertNull(coordinator.terminationSave())
        player.play(); engine.emit(EngineEvent.State(engine.current!!.occurrence, true, false)); runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("a", "b"), saves.last().songIDs)
        coordinator.close()
    }

    @Test fun anInstallOfAnEarlierRequestIsHeldToo() = runTest {
        val engine = FakeAudioEngine(); val saves = mutableListOf<QueueHandoffSnapshot>(); val copies = mutableListOf<LocalQueueCopy>()
        val player = PlaybackController(engine)
        val coordinator = QueueHandoffCoordinator(player, immediate(), "Coda on Android", { null }, { saves += it }, { copies += it })
        runCurrent()
        player.replaceQueue(listOf(QueueEntry("old")), startsPlaying = false)
        runCurrent(); advanceTimeBy(1_000); runCurrent(); saves.clear()
        val first = coordinator.expectStart()
        coordinator.expectStart()                                                 // a second request supersedes the first window
        player.replaceQueue(installed(first, "a", "b"), 0, 0, startsPlaying = false)   // the first request installs late
        runCurrent(); advanceTimeBy(QueueHandoffCoordinator.START_WINDOW_MS + 1_000); runCurrent()
        assertTrue("${saves.map { it.songIDs }}", saves.isEmpty()); assertNull(coordinator.terminationSave())
        coordinator.close()
    }

    // NeedsLogin -> Connected recreates the coordinator while the player keeps its (tagged) queue. Start tokens are
    // process-unique, so a new coordinator's first request can never collide with the retained queue's tag.
    @Test fun requestTokensStayUniqueAcrossCoordinatorRecreation() = runTest {
        val engine = FakeAudioEngine(); val saves = mutableListOf<QueueHandoffSnapshot>(); val copies = mutableListOf<LocalQueueCopy>()
        val player = PlaybackController(engine)
        val first = QueueHandoffCoordinator(player, immediate(), "Coda on Android", { null }, { saves += it }, { copies += it })
        runCurrent()
        val t1 = first.expectStart()
        player.replaceQueue(installed(t1, "a", "b"), 0, 0, startsPlaying = false)   // retained tagged queue, never played
        runCurrent(); first.close()
        val second = QueueHandoffCoordinator(player, immediate(), "Coda on Android", { null }, { saves += it }, { copies += it })
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        player.clear(); runCurrent(); advanceTimeBy(1_000); runCurrent(); saves.clear(); copies.clear()
        val t2 = second.expectStart()
        assertNotEquals(t1, t2)
        player.replaceQueue(installed(t2, "c", "d"), 0, 0, startsPlaying = false)   // buffering / about to fail, never audible
        runCurrent(); advanceTimeBy(QueueHandoffCoordinator.START_WINDOW_MS + 1_000); runCurrent()
        assertTrue("${saves.map { it.songIDs }}", saves.isEmpty()); assertTrue(copies.isEmpty()); assertNull(second.terminationSave())
        second.close()
    }

    // A write that is in flight when the account signs out finishes first and is deleted; later writes are refused.
    @Test fun retiredStoreRefusesLateWrites() {
        val store = LocalQueueStore(java.io.File(folder.root, "retire/account.json"))
        val copy = LocalQueueCopy(listOf(LocalQueueCopy.Entry.of(full)), 0, 0)
        store.write(copy); assertNotNull(store.read())
        store.retire()
        assertNull(store.read())
        store.write(copy)                      // a late exit save or worker
        assertNull(store.read()); assertFalse(store.file.exists())
        // A new sign-in uses a new instance for the same file.
        val fresh = LocalQueueStore(store.file); fresh.write(copy); assertNotNull(fresh.read())
    }

    @Test fun retireWaitsForAWriteInFlightAndThenWins() {
        val gate = java.util.concurrent.CountDownLatch(1); val inWrite = java.util.concurrent.CountDownLatch(1)
        val io = object : LocalQueueStore.FileIo by LocalQueueStore.FileIo.Default {
            override fun write(file: java.io.File, bytes: ByteArray) { inWrite.countDown(); gate.await(10, java.util.concurrent.TimeUnit.SECONDS); LocalQueueStore.FileIo.Default.write(file, bytes) }
        }
        val store = LocalQueueStore(java.io.File(folder.root, "race/account.json"), io)
        val writer = Thread { store.write(LocalQueueCopy(listOf(LocalQueueCopy.Entry.of(full)), 0, 0)) }.apply { start() }
        assertTrue(inWrite.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val retirer = Thread { store.retire() }.apply { start() }
        Thread.sleep(200); assertTrue("retire waits for the writer", retirer.isAlive)
        gate.countDown(); writer.join(10_000); retirer.join(10_000)
        assertNull(store.read()); assertFalse(store.file.exists())
    }

    // Sign-out deletes the copy explicitly; a closed coordinator cannot bring it back by saving the cleared player.
    @Test fun signOutDeletesTheCopyAndNothingWritesItBack() = runTest {
        val store = LocalQueueStore(java.io.File(folder.root, "signout/account.json"))
        val player = PlaybackController(FakeAudioEngine())
        val coordinator = QueueHandoffCoordinator(player, immediate(), "Coda on Android", { null }, { }, { store.write(it) })
        runCurrent()
        player.replaceQueue(listOf(full), 0, 1_000, startsPlaying = false)
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertNotNull("copy written by the pause save", store.read())
        // The order PlaybackService uses on SignedOut: close the coordinator, delete the copy, then clear the player.
        coordinator.close()
        store.delete()
        player.stop(); player.replaceQueue(emptyList())
        runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertNull(store.read()); assertFalse(store.file.exists())
    }

    // A resumption request that only asks for the notification metadata installs no queue and arms nothing.
    @Test fun onlyPlaybackRequestsThatInstallANewQueueArmTheGate() {
        assertTrue(startRequestArmsGate(isForPlayback = true, keepsPlayerQueue = false))
        assertFalse("metadata only", startRequestArmsGate(isForPlayback = false, keepsPlayerQueue = false))
        assertFalse("the player's own queue stays as it is", startRequestArmsGate(isForPlayback = true, keepsPlayerQueue = true))
    }
}
