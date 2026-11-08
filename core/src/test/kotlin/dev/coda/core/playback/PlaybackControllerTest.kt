package dev.coda.core.playback

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PlaybackControllerTest {
    /** Headphones unplugged pause at once; every other pause may fade (the engine decides). */
    @Test fun anImmediatePauseSkipsTheFade() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        player.replaceQueue(listOf(song("a")), startAt = 0)
        player.play()
        val pauses = engine.pauseCalls
        player.pause(immediate = true)
        assertEquals(1, engine.pauseNowCalls)
        assertEquals(pauses, engine.pauseCalls)
        assertFalse(player.state.value.isPlaying)
        player.play(); player.pause()
        assertEquals(pauses + 1, engine.pauseCalls)
        assertEquals(1, engine.pauseNowCalls)
    }

    @Test fun oneShotRemainingShufflePreservesOccurrencePositionAndAlbumRuns() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        val entries = (0..13).map { song("s$it").copy(albumId = "album-${it / 3}") }
        player.replaceQueue(entries, startAt = 1, startPositionMs = 567)
        val loads = engine.loadCalls.size
        val occurrence = player.currentEntry?.id
        repeat(10) { seed ->
            player.shuffleRemaining(kotlin.random.Random(seed))
            assertEquals(entries.take(2), player.queue.take(2))
            assertEquals(occurrence, player.currentEntry?.id)
            assertEquals(567L, player.positionMs)
            assertEquals(loads, engine.loadCalls.size)
            assertFalse(player.state.value.shuffleEnabled)
            entries.drop(2).groupBy { it.albumId }.values.filter { it.size >= 3 }.forEach { group ->
                val at = player.queue.indexOfFirst { it.id == group.first().id }
                assertEquals(group, player.queue.drop(at).take(group.size))
            }
        }
    }

    @Test fun stopAfterCompletionRetainsTheSessionsLastItemAndEndPosition() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        player.replaceQueue(listOf(song("a"), song("b")), startAt = 1)
        engine.finish()
        assertEquals("Finished", player.status)
        player.stop()
        assertEquals(1, player.currentIndex)
        assertEquals(120_000L, player.positionMs)
        assertFalse(player.state.value.isPrepared)
        player.prepare()
        assertEquals(120_000L, engine.loadCalls.last().startPositionMs)
        assertFalse(engine.loadCalls.last().playWhenReady)
    }

    @Test fun stopReleasesEngineAndRetainsQueueSelectionAndPositionForPrepare() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        val entries = listOf(song("a"), song("b"), song("c"))
        player.replaceQueue(entries, startAt = 1)
        val stale = engine.current!!.occurrence
        engine.position = 1234
        player.stop()
        assertEquals(2, engine.stopCalls)
        assertNull(engine.current)
        assertNull(engine.next)
        assertEquals(entries, player.queue)
        assertEquals(1, player.currentIndex)
        assertEquals(1234L, player.positionMs)
        assertFalse(player.state.value.isPrepared)
        assertFalse(player.playWhenReady)
        engine.emit(EngineEvent.State(stale, true, true))
        assertFalse(player.state.value.isBuffering)
        player.seek(1500)
        assertTrue(engine.seeks.isEmpty())
        player.prepare()
        assertTrue(player.state.value.isPrepared)
        assertEquals(1500L, engine.loadCalls.last().startPositionMs)
        assertFalse(engine.loadCalls.last().playWhenReady)
        assertEquals(entries[1].source, engine.current!!.source)
        assertEquals(entries[2].source, engine.next!!.source)
        player.stop()
        player.play()
        assertTrue(engine.loadCalls.last().playWhenReady)
        assertEquals(1500L, engine.loadCalls.last().startPositionMs)
    }

    private fun song(id: String, album: String? = id) = QueueEntry(
        songId = id, albumId = album, durationMs = 120_000,
        replayGain = ReplayGainMetadata(trackGain = -3.0, albumGain = -8.0),
    )

    @Test fun bufferingPreservesPlayIntentForNextPreviousAndRemoveCurrent() {
        for (action in listOf<(PlaybackController) -> Unit>(
            { it.next() }, { it.previous() }, { it.remove(it.currentEntry!!.id) },
        )) {
            val engine = FakeAudioEngine()
            val player = PlaybackController(engine)
            player.replaceQueue(listOf(song("a"), song("b"), song("c")), startAt = 1)
            engine.emit(EngineEvent.State(engine.current!!.occurrence, false, true))
            assertTrue(player.playWhenReady)
            assertTrue(player.state.value.isBuffering)
            assertFalse(player.state.value.isActuallyPlaying)
            action(player)
            assertTrue(engine.loadCalls.last().playWhenReady)
            assertTrue(player.playWhenReady)
        }
    }

    @Test fun staleStateCannotChangeNewOccurrenceAndEngineCannotOverridePauseIntent() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        player.replaceQueue(listOf(song("a"), song("b")))
        val oldId = engine.current!!.occurrence
        player.next()
        val state = player.state.value
        engine.emit(EngineEvent.State(oldId, false, true))
        assertEquals(state, player.state.value)
        engine.emit(EngineEvent.State(engine.current!!.occurrence, true, false))
        assertTrue(player.state.value.isActuallyPlaying)
        player.pause()
        engine.emit(EngineEvent.State(engine.current!!.occurrence, true, false))
        assertFalse(player.playWhenReady)
        player.previous()
        assertFalse(engine.loadCalls.last().playWhenReady)
    }

    @Test fun unchangedNextKeepsOccurrenceAndSkipsEngineUpdatesIncludingAbsentNext() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine, true)
        val a = song("a"); val b = song("b"); val c = song("c"); val d = song("d")
        player.replaceQueue(listOf(a, b, c))
        val next = engine.next
        player.enqueue(listOf(d))
        player.move(listOf(d.id), before = c.id)
        player.remove(c.id)
        assertEquals(next, engine.next)
        assertTrue(engine.updatedNextItems.isEmpty())
        player.play(2)
        player.remove(a.id)
        assertNull(engine.next)
        assertTrue(engine.updatedNextItems.isEmpty())
    }

    @Test fun queuedAdvanceAdoptsSupersededPreparationWithItsFrozenGain() {
        val engine = FakeAudioEngine(queuedDelivery = true)
        val player = PlaybackController(engine, true)
        val a = song("a"); val b = song("b", "B"); val c = song("c", "B")
        player.replaceQueue(listOf(a, b))
        val originalNext = engine.next!!
        engine.advance() // Audio is already on b, but the controller has not received Advanced.
        player.enqueue(listOf(c)) // Reprepare b with album gain while that callback is pending.
        assertNotEquals(originalNext.occurrence, engine.next!!.occurrence)
        assertEquals(-8.0, engine.next!!.gainDb, 0.0)
        assertEquals(a, player.currentEntry)
        engine.deliverEvents()
        assertEquals(b, player.currentEntry)
        assertEquals(originalNext.occurrence, player.playbackOccurrenceId)
        assertEquals(originalNext, engine.current)
        assertEquals(-3.0, player.currentReplayGainDb, 0.0)
        assertFalse(player.currentAlbumSequence!!.isAlbumSequence)
        assertEquals(c.source, engine.next!!.source)
        assertEquals(-8.0, engine.next!!.gainDb, 0.0)
        assertEquals(a.songId, player.completedPlayback!!.songId)
        assertTrue(engine.gains.isEmpty()) // Never correct gain after audio has already started.
        val state = player.state.value
        engine.emit(EngineEvent.Advanced(originalNext.occurrence))
        engine.deliverEvents()
        assertEquals(state, player.state.value)
    }

    @Test fun supersededAdvanceRequiresImmediateSuccessorAndDoesNotSurviveManualLoad() {
        val engine = FakeAudioEngine(queuedDelivery = true)
        val player = PlaybackController(engine, true)
        val a = song("a"); val b = song("b", "B"); val c = song("c", "B")
        player.replaceQueue(listOf(a, b))
        val oldNext = engine.next!!.occurrence
        engine.advance()
        player.insert(listOf(c), before = b.id)
        engine.deliverEvents()
        assertEquals(a, player.currentEntry)
        assertNull(player.completedPlayback)
        player.remove(c.id)
        player.play(0) // Clears the superseded history even though b is still index + 1.
        engine.emit(EngineEvent.Advanced(oldNext))
        engine.deliverEvents()
        assertEquals(a, player.currentEntry)
        assertNull(player.completedPlayback)
    }

    @Test fun supersededHistoryRetainsEightPreparationsAndEvictsOlderOnes() {
        for (edits in listOf(8, 9)) {
            val engine = FakeAudioEngine(queuedDelivery = true)
            var version = 0
            val player = PlaybackController(engine, sourceResolver = SourceResolver {
                TrackSource.CachedFile("synthetic-${it.songId}-$version.flac")
            })
            val a = song("a"); val b = song("b")
            player.replaceQueue(listOf(a, b))
            val originalNext = engine.next!!
            engine.advance()
            repeat(edits) {
                version++
                player.enqueue(listOf(song("tail$it")))
            }
            engine.deliverEvents()
            if (edits == 8) {
                assertEquals(b, player.currentEntry)
                assertEquals(originalNext.occurrence, player.playbackOccurrenceId)
            } else {
                assertEquals(a, player.currentEntry)
                assertNull(player.completedPlayback)
            }
        }
    }

    @Test fun failedNextPausesAtBoundaryAndRetryRepreparesIt() {
        for (failure in listOf(PlaybackFailure.Network, PlaybackFailure.Decode)) {
            val engine = FakeAudioEngine()
            val player = PlaybackController(engine)
            val a = song("a"); val b = song("b")
            player.replaceQueue(listOf(a, b))
            val failedNext = engine.next!!.occurrence
            engine.emit(EngineEvent.Failed(failedNext, failure))
            assertTrue(player.playWhenReady)
            assertNull(player.state.value.failure)
            engine.finish()
            assertEquals(a.songId, player.completedPlayback!!.songId)
            assertEquals(b, player.currentEntry)
            assertEquals(1, player.currentIndex)
            assertEquals(failure == PlaybackFailure.Network, player.playWhenReady)
            assertEquals(if (failure == PlaybackFailure.Network) null else failure, player.state.value.failure)
            assertEquals(failure, player.state.value.entryFailures[b.id])
            assertEquals(if (failure == PlaybackFailure.Network) "Waiting for connection" else "Playback failed",
                player.status)
            assertEquals(1, engine.loadCalls.size)
            val boundary = player.state.value
            engine.emit(EngineEvent.State(failedNext, true, false))
            engine.emit(EngineEvent.Ended(failedNext))
            assertEquals(boundary, player.state.value)
            player.play()
            player.recovery.waiting?.let { player.probeSucceeded(it.target) }
            assertEquals(2, engine.loadCalls.size) // hasLoadedItem was false at the boundary.
            assertEquals(b.source, engine.current!!.source)
            assertEquals(0L, engine.loadCalls.last().startPositionMs)
            assertNotEquals(failedNext, player.playbackOccurrenceId)
            assertTrue(player.state.value.entryFailures.isEmpty())
            assertNull(player.state.value.failure)
        }
    }

    @Test fun rePreparingNextClearsItsFailureButUnchangedPreparationDoesNot() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine, true)
        val a = song("a"); val b = song("b")
        player.replaceQueue(listOf(a, b))
        engine.emit(EngineEvent.Failed(engine.next!!.occurrence, PlaybackFailure.Network))
        player.enqueue(listOf(song("c")))
        assertEquals(PlaybackFailure.Network, player.state.value.entryFailures[b.id])
        player.setReplayGainEnabled(false)
        assertTrue(player.state.value.entryFailures.isEmpty())
        engine.emit(EngineEvent.Failed(engine.next!!.occurrence, PlaybackFailure.Decode))
        player.play(0)
        assertTrue(player.state.value.entryFailures.isEmpty())
    }

    @Test fun sourceResolverRunsAtPreparationAndFreezesCurrentSource() {
        val engine = FakeAudioEngine()
        var format = StreamFormat.RAW
        var cached = false
        val resolvedEntries = mutableListOf<UUID>()
        val player = PlaybackController(engine, sourceResolver = SourceResolver {
            resolvedEntries += it.id
            if (cached) TrackSource.CachedFile("synthetic-${it.songId}.flac")
            else TrackSource.Remote(it.songId, format)
        })
        val a = song("a"); val b = song("b"); val c = song("c")
        player.replaceQueue(listOf(a, b, c))
        val current = engine.current!!
        val firstNext = engine.next!!
        assertEquals(listOf(a.id, b.id), resolvedEntries)
        format = StreamFormat.OPUS_128
        player.enqueue(listOf(song("d")))
        assertEquals(TrackSource.Remote("b", StreamFormat.OPUS_128), engine.next!!.source)
        assertNotEquals(firstNext.occurrence, engine.next!!.occurrence)
        assertEquals(current, engine.current)
        val incoming = engine.next!!
        cached = true
        engine.advance()
        assertEquals(incoming, engine.current)
        assertEquals(incoming.source, player.state.value.currentOccurrence!!.item.source)
        assertEquals(TrackSource.CachedFile("synthetic-c.flac"), engine.next!!.source)
        player.play(1)
        assertEquals(TrackSource.CachedFile("synthetic-b.flac"), engine.current!!.source)
    }

    /** A successor prepared as a stream is prepared from the file once its download completes. */
    @Test fun completedDownloadRepreparesStreamingSuccessorOnly() {
        val engine = FakeAudioEngine()
        val cached = mutableSetOf<String>()
        val player = PlaybackController(engine, sourceResolver = SourceResolver {
            if (it.songId in cached) TrackSource.CachedFile("synthetic-${it.songId}.opus") else TrackSource.Remote(it.songId, StreamFormat.OPUS_128)
        })
        player.replaceQueue(listOf(song("a"), song("b"), song("c")))
        val current = engine.current!!
        val streamed = engine.next!!
        assertEquals(TrackSource.Remote("b", StreamFormat.OPUS_128), streamed.source)
        val updates = engine.updatedNextItems.size
        // Not downloaded yet, or another song: nothing happens.
        player.trackCached("b"); player.trackCached("c")
        assertEquals(updates, engine.updatedNextItems.size)
        cached += "b"
        player.trackCached("c")
        assertEquals(updates, engine.updatedNextItems.size)
        player.trackCached("b")
        assertEquals(TrackSource.CachedFile("synthetic-b.opus"), engine.next!!.source)
        assertNotEquals(streamed.occurrence, engine.next!!.occurrence)
        assertEquals(current, engine.current)
        assertEquals(engine.next, engine.updatedNextItems.last())
        // Already a file: a repeat call changes nothing.
        player.trackCached("b")
        assertEquals(updates + 1, engine.updatedNextItems.size)
        // The superseded stream preparation still advances (a queued Advanced for it), as for any next swap.
        engine.advance()
        assertEquals(TrackSource.CachedFile("synthetic-b.opus"), player.state.value.currentOccurrence!!.item.source)
    }

    /** Never near the end of the current item (the successor may already be loading for the gapless join). */
    @Test fun completedDownloadLeavesSuccessorAloneNearTheEnd() {
        val engine = FakeAudioEngine()
        val cached = mutableSetOf<String>()
        val player = PlaybackController(engine, sourceResolver = SourceResolver {
            if (it.songId in cached) TrackSource.CachedFile("synthetic-${it.songId}.opus") else TrackSource.Remote(it.songId, StreamFormat.OPUS_128)
        })
        player.replaceQueue(listOf(song("a"), song("b")))
        val streamed = engine.next!!
        cached += "b"
        engine.position = 120_000 - PlaybackController.NEXT_REFRESH_MIN_REMAINING_MS
        player.trackCached("b")
        assertEquals(streamed, engine.next)
        engine.position = 120_000 - PlaybackController.NEXT_REFRESH_MIN_REMAINING_MS - 1
        player.trackCached("b")
        assertEquals(TrackSource.CachedFile("synthetic-b.opus"), engine.next!!.source)
    }

    /** A cache change from any downloader goes through the same guards for the prepared successor. */
    @Test fun cacheChangeRepreparesTheStreamingSuccessorWithTheSameGuards() {
        val engine = FakeAudioEngine()
        val cached = mutableSetOf<String>()
        val player = PlaybackController(engine, sourceResolver = SourceResolver {
            if (it.songId in cached) TrackSource.CachedFile("synthetic-${it.songId}.opus") else TrackSource.Remote(it.songId, StreamFormat.OPUS_128)
        })
        player.replaceQueue(listOf(song("a"), song("b")))
        val streamed = engine.next!!
        player.cacheChanged()
        assertEquals(streamed, engine.next)
        cached += "b"
        engine.position = 120_000 - PlaybackController.NEXT_REFRESH_MIN_REMAINING_MS
        player.cacheChanged()
        assertEquals(streamed, engine.next)
        engine.position = 0
        player.cacheChanged()
        assertEquals(TrackSource.CachedFile("synthetic-b.opus"), engine.next!!.source)
    }

    @Test fun refreshRuleNeedsAKnownLengthAndRoomBeforeTheEnd() {
        assertFalse(PlaybackController.refreshesPreparedNext(null))
        assertFalse(PlaybackController.refreshesPreparedNext(0))
        assertFalse(PlaybackController.refreshesPreparedNext(PlaybackController.NEXT_REFRESH_MIN_REMAINING_MS))
        assertTrue(PlaybackController.refreshesPreparedNext(PlaybackController.NEXT_REFRESH_MIN_REMAINING_MS + 1))
    }

    /** A successor that fell back to another source keeps it; a cached successor is not touched. */
    @Test fun completedDownloadKeepsFallbackSuccessor() {
        val engine = FakeAudioEngine()
        val cached = mutableSetOf<String>()
        val player = PlaybackController(engine, sourceResolver = SourceResolver {
            if (it.songId in cached) TrackSource.CachedFile("synthetic-${it.songId}.opus") else TrackSource.Remote(it.songId, StreamFormat.OPUS_128)
        })
        player.replaceQueue(listOf(song("a"), song("b")))
        val next = engine.next!!
        val fallback = TrackSource.Remote("b", StreamFormat.RAW)
        engine.emit(EngineEvent.SourceFallback(next.occurrence, fallback))
        cached += "b"
        val updates = engine.updatedNextItems.size
        player.trackCached("b")
        assertEquals(updates, engine.updatedNextItems.size)
    }

    @Test fun seekCanPassShortServerDurationAndClampsOnlyNegativePositions() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        player.replaceQueue(listOf(song("a").copy(durationMs = 1_000)))
        player.seek(5_000)
        player.seek(-100)
        assertEquals(listOf(5_000L, 0L), engine.seeks)
    }

    @Test fun completionsSurviveBeforeSubscriptionAndRelease() = runBlocking {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        val completions = (0 until 80).map {
            player.replaceQueue(listOf(song("s$it")))
            engine.finish()
            player.completedPlayback!!
        }
        player.release()
        val events = withTimeout(2_000) { player.events.toList() }
        assertEquals(completions, events.filterIsInstance<PlaybackEvent.Completed>().map { it.playback })
        assertEquals(80, events.filterIsInstance<PlaybackEvent.OccurrenceStarted>().size)
    }

    @Test fun completionsSurviveAStalledCollectorBeyondOldBufferCapacity() = runBlocking {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        val resume = CompletableDeferred<Unit>()
        val events = mutableListOf<PlaybackEvent>()
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            player.events.collect { resume.await(); events += it }
        }
        repeat(80) {
            player.replaceQueue(listOf(song("s$it")))
            engine.finish()
        }
        player.release()
        resume.complete(Unit)
        withTimeout(2_000) { collector.join() }
        assertEquals((0 until 80).map { "s$it" },
            events.filterIsInstance<PlaybackEvent.Completed>().map { it.playback.songId })
    }

    @Test fun loadAdvanceFinishPublishesNaturalCompletionsAndStartsExactlyOnce() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        val events = mutableListOf<PlaybackEvent>()
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { player.events.collect { events += it } }
        try {
            val a = song("a", "A"); val b = song("b", "A")
            player.replaceQueue(listOf(a, b), startPositionMs = 83_250, startsPlaying = false)
            val load = engine.loadCalls.single()
            assertEquals(83_250L, load.startPositionMs)
            assertFalse(load.playWhenReady)
            assertEquals(a.source, load.current.source)
            assertEquals(b.source, load.next?.source)
            assertEquals(0.0, load.current.gainDb, 0.0)
            assertEquals(83_250L, player.positionMs)
            assertTrue(events.isEmpty())
            val firstId = player.playbackOccurrenceId
            player.play(); player.pause(); player.seek(20_000); player.play()
            engine.emit(EngineEvent.State(engine.current!!.occurrence, true, false))
            assertEquals(firstId, player.playbackOccurrenceId)
            assertEquals(listOf(PlaybackEvent.OccurrenceStarted(a)), events)
            assertNull(player.completedPlayback)
            engine.advance()
            assertNotEquals(firstId, player.playbackOccurrenceId)
            assertEquals(1, player.currentIndex)
            assertEquals(0L, player.positionMs)
            assertNull(engine.next)
            assertEquals(a.songId, player.completedPlayback?.songId)
            assertTrue(player.completedPlayback!!.albumSequence!!.isAlbumSequence)
            assertEquals(PlaybackEvent.OccurrenceStarted(b), events.last())
            engine.finish()
            assertEquals(listOf(a, b), player.queue)
            assertNull(player.currentIndex)
            assertNull(player.playbackOccurrenceId)
            assertEquals("Finished", player.status)
            assertFalse(player.isPlaying)
            assertEquals(0L, player.positionMs)
            assertEquals(2, events.filterIsInstance<PlaybackEvent.Completed>().size)
            assertEquals(b.songId, player.completedPlayback?.songId)
        } finally { scope.cancel() }
    }

    @Test fun replayGainFreezesCurrentButRepreparesSameSourceNext() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine, replayGainEnabled = true)
        val a = song("a"); val b = song("b", "B"); val c = song("c", "B")
        player.replaceQueue(listOf(a, b))
        val first = engine.loadCalls.single()
        assertEquals(-3.0, first.current.gainDb, 0.0)
        assertEquals(-3.0, first.next!!.gainDb, 0.0)
        player.enqueue(listOf(c))
        assertEquals(first.next.source, engine.next?.source)
        assertEquals(-8.0, engine.next!!.gainDb, 0.0)
        assertNotEquals(first.next.occurrence, engine.next!!.occurrence)
        assertEquals(-3.0, player.currentReplayGainDb, 0.0)
        assertTrue(engine.gains.isEmpty())
        engine.advance()
        val incoming = player.playbackOccurrenceId
        assertEquals(-8.0, player.currentReplayGainDb, 0.0)
        player.remove(c.id); player.seek(1_000); player.pause(); player.play()
        assertEquals(incoming, player.playbackOccurrenceId)
        assertEquals(-8.0, player.currentReplayGainDb, 0.0)
        assertTrue(player.currentAlbumSequence!!.isAlbumSequence)
        engine.finish()
        assertTrue(player.completedPlayback!!.albumSequence!!.isAlbumSequence)
        player.play(1)
        assertEquals(-3.0, player.currentReplayGainDb, 0.0)
        assertFalse(player.currentAlbumSequence!!.isAlbumSequence)
    }

    @Test fun pausedPreparationCanAdoptEditsButPausingPlayedOccurrenceCannot() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine, true)
        val a = song("a", "A"); val b = song("b", "A")
        player.replaceQueue(listOf(a), startsPlaying = false)
        val id = player.playbackOccurrenceId
        player.enqueue(listOf(b))
        assertEquals(id, player.playbackOccurrenceId)
        assertEquals(-8.0, player.currentReplayGainDb, 0.0)
        assertEquals(listOf(-8.0), engine.gains)
        player.play(); player.pause(); player.remove(b.id)
        assertEquals(-8.0, player.currentReplayGainDb, 0.0)
        player.setReplayGainEnabled(false)
        assertEquals(id, player.playbackOccurrenceId)
        assertEquals(0.0, engine.gains.last(), 0.0)
        player.setReplayGainEnabled(true)
        assertEquals(-8.0, engine.gains.last(), 0.0)
    }

    /** A toggle while waiting for connection reaches the recovery load; the album context stays frozen. */
    @Test fun replayGainToggleWhileWaitingAppliesToTheRecoveryLoad() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine, true, nowMs = { 0 })
        player.replaceQueue(listOf(song("a", "A"), song("b", "A")))
        val context = player.currentAlbumSequence
        assertEquals(-8.0, engine.loadCalls.last().current.gainDb, 0.0)
        engine.failCurrent(PlaybackFailure.Network)
        player.setReplayGainEnabled(false)
        assertEquals(0.0, player.currentReplayGainDb, 0.0)
        player.probeSucceeded(player.recovery.waiting!!.target)
        assertEquals(0.0, engine.loadCalls.last().current.gainDb, 0.0)
        engine.failCurrent(PlaybackFailure.Network)
        player.setReplayGainEnabled(true)
        player.probeSucceeded(player.recovery.waiting!!.target)
        assertEquals(-8.0, engine.loadCalls.last().current.gainDb, 0.0)
        assertEquals(context, player.currentAlbumSequence)
    }

    @Test fun untaggedTransitionsAndToggleCarryGainBeforeAdvance() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine, true)
        player.replaceQueue(listOf(song("a"), song("b").copy(replayGain = null), song("c")))
        assertEquals(-9.0, engine.next!!.gainDb, 0.0)
        engine.advance()
        assertEquals(-9.0, player.currentReplayGainDb, 0.0)
        assertEquals(-3.0, engine.next!!.gainDb, 0.0)
        player.setReplayGainEnabled(false)
        assertEquals(0.0, player.currentReplayGainDb, 0.0)
        assertEquals(0.0, engine.next!!.gainDb, 0.0)
        val loadCount = engine.loadCalls.size
        engine.advance()
        assertEquals(0.0, player.currentReplayGainDb, 0.0)
        assertEquals(loadCount, engine.loadCalls.size)
        assertEquals(listOf(0.0), engine.gains)
    }

    @Test fun queueEditsUpdateChangedNextAndPreserveCurrentOccurrenceAndPosition() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine, true)
        val a = song("a"); val b = song("b"); val c = song("c"); val d = song("d")
        player.replaceQueue(listOf(a, b, c), startAt = 1)
        engine.position = 31_000
        val current = player.state.value.currentOccurrence
        player.insert(listOf(d), before = c.id)
        assertEquals(d.source, engine.next?.source)
        assertTrue(player.move(listOf(a.id, b.id), before = null))
        assertEquals(listOf(d, c, a, b), player.queue)
        assertNull(engine.next)
        assertEquals(3, player.currentIndex)
        player.remove(a.id)
        assertEquals(2, player.currentIndex)
        player.enqueue(listOf(c.copy(id = UUID.randomUUID())))
        assertEquals(c.source, engine.next?.source)
        assertEquals(current, player.state.value.currentOccurrence)
        assertEquals(31_000L, player.positionMs)
        assertEquals(1, engine.loadCalls.size)
        assertEquals(3, engine.updatedNextItems.size)
        assertNull(player.completedPlayback)
    }

    @Test fun previousUsesLivePositionAndStrictThreeSecondBoundary() {
        for (position in listOf(0L, 2_999L, 3_000L, 3_001L, 90_000L)) {
            val engine = FakeAudioEngine()
            val player = PlaybackController(engine)
            player.replaceQueue(listOf(song("a"), song("b")), startAt = 1, startsPlaying = false)
            val id = player.playbackOccurrenceId
            engine.position = position
            player.previous()
            assertFalse(player.isPlaying)
            if (position > 3_000) {
                assertEquals(1, player.currentIndex)
                assertEquals(id, player.playbackOccurrenceId)
                assertEquals(listOf(0L), engine.seeks)
            } else {
                assertEquals(0, player.currentIndex)
                assertNotEquals(id, player.playbackOccurrenceId)
            }
            assertNull(player.completedPlayback)
        }
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        assertFalse(player.canGoPrevious)
        player.replaceQueue(listOf(song("only")))
        assertTrue(player.canGoPrevious)
        player.previous()
        assertEquals(1, engine.loadCalls.size)
        assertEquals(listOf(0L), engine.seeks)
    }

    @Test fun removeCurrentChoosesFirstSurvivingSuccessorThenLastSurvivingPredecessor() {
        for (playing in listOf(false, true)) {
            val engine = FakeAudioEngine()
            val player = PlaybackController(engine)
            val entries = (0..5).map { song("s$it") }
            player.replaceQueue(entries, startAt = 2, startsPlaying = playing)
            player.remove(listOf(entries[2].id, entries[3].id))
            assertEquals(entries[4], player.currentEntry)
            assertEquals(playing, player.isPlaying)
            assertEquals(0L, engine.loadCalls.last().startPositionMs)
            player.remove(listOf(entries[4].id, entries[5].id))
            assertEquals(entries[1], player.currentEntry)
            assertEquals(playing, player.isPlaying)
            assertNull(player.completedPlayback)
            player.remove(player.queue.map { it.id })
            assertTrue(player.queue.isEmpty())
            assertNull(player.currentEntry)
            assertNull(engine.current)
            assertEquals("Nothing queued", player.status)
        }
    }

    @Test fun failuresAreAttributedAndStaleEventsCannotMutateCurrentPlayback() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        val a = song("a"); val b = song("b")
        player.replaceQueue(listOf(a, b))
        val oldCurrent = engine.current!!.occurrence
        val staleNext = engine.next!!.occurrence
        val c = song("c")
        player.insert(listOf(c), before = b.id)
        engine.emit(EngineEvent.Advanced(staleNext))
        engine.emit(EngineEvent.Failed(staleNext, PlaybackFailure.Decode))
        assertEquals(a, player.currentEntry)
        assertTrue(player.state.value.entryFailures.isEmpty())
        player.remove(c.id)
        val next = engine.next!!.occurrence
        engine.emit(EngineEvent.Failed(next, PlaybackFailure.NotFound))
        assertTrue(player.isPlaying)
        assertNull(player.state.value.failure)
        assertEquals(PlaybackFailure.NotFound, player.state.value.entryFailures[b.id])
        assertEquals(1, engine.stopCalls) // initial replace only
        engine.position = 19_000
        engine.failCurrent(PlaybackFailure.Network)
        assertTrue(player.isPlaying)
        assertEquals("Waiting for connection", player.status)
        assertNull(player.state.value.failure)
        assertNull(player.completedPlayback)
        player.play()
        player.probeSucceeded(player.recovery.waiting!!.target)
        assertEquals(19_000L, engine.loadCalls.last().startPositionMs)
        assertNotEquals(oldCurrent, player.playbackOccurrenceId)
        engine.emit(EngineEvent.Ended(oldCurrent))
        engine.emit(EngineEvent.Failed(oldCurrent, PlaybackFailure.Unknown))
        assertTrue(player.isPlaying)
        assertNull(player.completedPlayback)
        assertNull(player.state.value.failure)
        engine.advance()
        engine.emit(EngineEvent.Ended(oldCurrent))
        assertEquals(b, player.currentEntry)
        assertEquals(a.songId, player.completedPlayback?.songId)
    }

    @Test fun removingCurrentPublishesQueueAndReplacementAtomically() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        val states = mutableListOf<PlaybackState>()
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { player.state.collect { states += it } }
        try {
            val entries = (0..3).map { song("s$it") }
            player.replaceQueue(entries, startAt = 3)
            player.remove(listOf(entries[2].id, entries[3].id))
            assertEquals(entries[1], player.currentEntry)
            for (state in states) {
                assertTrue(state.currentIndex == null || state.currentIndex in state.queue.indices)
                assertEquals(state.currentEntry?.id, state.currentOccurrence?.entry?.id)
            }
        } finally { scope.cancel() }
    }

    @Test fun repeatedSameSongHasDistinctQueueAndPlaybackIdentitiesAndCompletions() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        val a = song("same", "A"); val b = song("same", "A")
        assertNotEquals(a.id, b.id)
        player.replaceQueue(listOf(a, b))
        assertNotEquals(engine.current!!.occurrence, engine.next!!.occurrence)
        val id = player.playbackOccurrenceId
        engine.advance()
        val first = player.completedPlayback!!
        assertNotEquals(id, player.playbackOccurrenceId)
        engine.finish()
        assertEquals(first.songId, player.completedPlayback!!.songId)
        assertNotEquals(first.id, player.completedPlayback!!.id)
        player.play()
        assertNull(player.currentEntry)
        player.enqueue(listOf(song("new")))
        assertEquals("new", player.currentEntry?.songId)
        assertFalse(player.isPlaying)
    }

    @Test fun seekBoundsManualSkipsSourceFreezingClearAndRelease() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine, true)
        val a = song("a").copy(source = TrackSource.CachedFile("synthetic.flac"))
        val b = song("b").copy(source = TrackSource.Remote("b", StreamFormat.OPUS_128))
        player.replaceQueue(listOf(a, b), startAt = -10, startPositionMs = -5)
        assertEquals(a.source, engine.current!!.source)
        assertEquals(b.source, engine.next!!.source)
        player.seek(-1); assertEquals(0L, player.positionMs)
        player.seek(Long.MAX_VALUE); assertEquals(Long.MAX_VALUE, player.positionMs)
        player.next()
        assertEquals(b.source, engine.current!!.source)
        assertNull(player.completedPlayback)
        assertFalse(player.canGoNext)
        val calls = engine.loadCalls.size
        player.next(); player.play(-1); player.play(20)
        assertEquals(calls, engine.loadCalls.size)
        player.replaceQueue(listOf(a, b), startAt = 100, startsPlaying = false)
        assertEquals(1, player.currentIndex)
        assertTrue(player.replayGainEnabled)
        player.release()
        assertTrue(engine.released)
        assertNull(engine.listener)
        assertTrue(player.queue.isEmpty())
        assertFalse(player.isPlaying)
        assertEquals(0.0, player.currentReplayGainDb, 0.0)
    }
}
