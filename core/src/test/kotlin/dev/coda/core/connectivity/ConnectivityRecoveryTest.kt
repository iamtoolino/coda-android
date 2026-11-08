package dev.coda.core.connectivity

import dev.coda.core.testing.testTrackCache
import dev.coda.core.playback.*
import org.junit.Assert.*
import kotlinx.coroutines.launch
import org.junit.Test

class ConnectivityRecoveryTest {
    @Test fun cachedOpusPlaysOffOrOnMobileAndACachedOriginalPlaysEverywhere() {
        val directory = java.nio.file.Files.createTempDirectory("sources").toFile()
        try {
            val cache = testTrackCache(directory)
            val opus = dev.coda.core.cache.TrackKey("a", StreamFormat.OPUS_128)
            val raw = opus.copy(format = StreamFormat.RAW)
            val oggOpus = "OggS".toByteArray() + ByteArray(24) + "OpusHead".toByteArray()
            cache.begin(opus)!!.use { it.output.write(oggOpus); it.commit("audio/ogg") }
            for (network in listOf(NetworkState(false, false), NetworkState(true, false), NetworkState(true, true, cellular = true))) {
                val resolver = CachedSourceResolver({ cache }, { network })
                assertEquals(TrackSource.CachedFile(cache.find(opus)!!.path), resolver.resolve(QueueEntry("a")))
            }
            cache.begin(raw)!!.use { it.output.write("fLaC".toByteArray()); it.commit() }
            for (network in listOf(NetworkState(false, false), NetworkState(true, true), NetworkState(true, false))) {
                val resolver = CachedSourceResolver({ cache }, { network })
                assertEquals(TrackSource.CachedFile(cache.find(raw)!!.path), resolver.resolve(QueueEntry("a")))
            }
            assertFalse(StreamFormatPolicy.metered(NetworkState(true, true)))
            assertTrue(StreamFormatPolicy.metered(NetworkState(true, false)))
            assertTrue(StreamFormatPolicy.metered(NetworkState(true, true, cellular = true)))
        } finally { directory.deleteRecursively() }
    }
    @Test fun cappedBackoffCallbackPausePlayAndClear() {
        val recovery = ConnectivityRecovery()
        recovery.waitFor(OccurrenceId(), 1234, true, 0)
        assertFalse(recovery.due(999)); assertTrue(recovery.due(1000))
        var now = 1000L
        for (delay in listOf(2000L, 4000, 8000, 16000, 30000, 30000, 30000)) {
            recovery.failed(now)
            assertEquals(delay, recovery.waiting!!.retryAtMs - now)
            now += delay
        }
        recovery.pause(); assertFalse(recovery.waiting!!.autoResume)
        recovery.retryNow(now); assertTrue(recovery.due(now)); assertFalse(recovery.waiting!!.autoResume)
        recovery.networkChanged(now); assertEquals(0, recovery.waiting!!.attempt); assertTrue(recovery.due(now))
        assertFalse(recovery.waiting!!.autoResume); assertEquals(1234, recovery.waiting!!.positionMs)
        recovery.play(now); assertTrue(recovery.waiting!!.autoResume)
        recovery.clear(); assertNull(recovery.waiting); assertFalse(recovery.due(now))
    }
    @Test fun retriesRelaxAfterTheLockCapByScreenStateAndCallbackStillProbesAtOnce() {
        val recovery = ConnectivityRecovery(RecoveryPolicy(lockCapMs = 10_000, relaxedScreenOnMs = 2_000, relaxedScreenOffMs = 5_000))
        recovery.waitFor(OccurrenceId(), 0, true, 100)
        assertEquals(10_100, recovery.waiting!!.lockUntilMs)
        recovery.failed(9_000)
        assertEquals(9_000 + ConnectivityRecovery.retryDelayMs(1), recovery.waiting!!.retryAtMs)
        recovery.failed(10_100); assertEquals(12_100, recovery.waiting!!.retryAtMs)
        recovery.failed(12_100, screenOn = false); assertEquals(17_100, recovery.waiting!!.retryAtMs)
        recovery.networkChanged(13_000); assertTrue(recovery.due(13_000))
        assertEquals(10_100, recovery.waiting!!.lockUntilMs)
    }
    @Test fun formatChangesOnlyForNextOccurrenceAndCacheWinsOnEveryNetwork() {
        val engine = FakeAudioEngine()
        var network = NetworkState(true, true)
        val cached = QueueEntry("cache", source = TrackSource.CachedFile("/synthetic.flac"))
        val player = PlaybackController(engine, sourceResolver = SourceResolver {
            if (it == cached) it.source else TrackSource.Remote(it.songId, dev.coda.core.cache.CacheFormatPolicy.fetchFormat(
                network, dev.coda.core.cache.SongFormat()))
        })
        player.replaceQueue(listOf(QueueEntry("a"), QueueEntry("b"), cached))
        val original = engine.current!!
        network = NetworkState(true, false); player.networkChanged()
        assertEquals(original, engine.current)
        assertEquals(StreamFormat.OPUS_128, (engine.next!!.source as TrackSource.Remote).format)
        engine.advance(); assertEquals(cached.source, engine.next!!.source)
    }
    @Test fun offlineBoundaryWaitsResumesPausedAndRejectsStaleProbeThenCachedSkipClears() {
        val engine = FakeAudioEngine()
        var online = true
        val player = PlaybackController(engine, networkAvailable = { online }, nowMs = { 0 })
        val cached = QueueEntry("cached", source = TrackSource.CachedFile("/synthetic"))
        player.replaceQueue(listOf(cached, QueueEntry("remote"), cached.copy(id = java.util.UUID.randomUUID())))
        online = false; player.networkChanged(); assertNull(engine.next)
        engine.finish()
        assertEquals(1, player.currentIndex); assertEquals("Waiting for connection", player.status)
        assertTrue(player.playWhenReady); assertTrue(player.state.value.isBuffering)
        player.pause(); val target = player.recovery.waiting!!.target
        player.probeSucceeded(OccurrenceId()); assertNotNull(player.recovery.waiting)
        online = true; player.probeSucceeded(target)
        assertFalse(engine.loadCalls.last().playWhenReady); assertEquals(0, engine.loadCalls.last().startPositionMs)
        engine.failCurrent(PlaybackFailure.Network); player.next()
        assertNull(player.recovery.waiting); assertEquals(cached.source, engine.current!!.source)
        player.clear(); assertNull(player.state.value.waiting)
    }
    /** The read-ahead copy that completed while waiting is played; the stream is not fetched again. */
    @Test fun recoveryResumesFromACopyCompletedWhileWaiting() {
        val engine = FakeAudioEngine()
        var cached = false
        val player = PlaybackController(engine, nowMs = { 0 },
            sourceResolver = SourceResolver { e -> if (cached) TrackSource.CachedFile("/cache/${e.songId}") else e.source })
        val opus = { id: String -> QueueEntry(id, durationMs = 200_000, source = TrackSource.Remote(id, StreamFormat.OPUS_128)) }
        player.replaceQueue(listOf(opus("a"), opus("b")), startAt = 0)
        engine.position = 90_000
        engine.failCurrent(PlaybackFailure.Network)
        val target = player.recovery.waiting!!.target
        cached = true
        player.probeSucceeded(target)
        assertEquals(TrackSource.CachedFile("/cache/a"), engine.loadCalls.last().current.source)
        assertEquals(90_000L, engine.loadCalls.last().startPositionMs)
        assertEquals(TrackSource.CachedFile("/cache/a"), player.state.value.currentOccurrence!!.item.source)
    }
    @Test fun appendingWhileWaitingKeepsTheRecoveryTargetPositionAndAutomaticResume() {
        val engine = FakeAudioEngine()
        var online = false
        val player = PlaybackController(engine, networkAvailable = { online }, nowMs = { 0 })
        val first = QueueEntry("remote-a")
        player.replaceQueue(listOf(first, QueueEntry("remote-b")), startPositionMs = 1234)
        val waiting = player.recovery.waiting!!
        player.enqueue(listOf(QueueEntry("appended")))
        assertEquals(waiting, player.recovery.waiting)
        assertEquals(0, player.currentIndex); assertEquals(first, player.currentEntry)
        assertEquals(3, player.queue.size); assertEquals(1234, player.positionMs)
        assertTrue(player.playWhenReady); assertEquals("Waiting for connection", player.status)
        assertTrue(engine.loadCalls.isEmpty())
        online = true; player.probeSucceeded(waiting.target)
        val load = engine.loadCalls.single()
        assertEquals(TrackSource.Remote("remote-a", StreamFormat.RAW), load.current.source)
        assertEquals(1234, load.startPositionMs); assertTrue(load.playWhenReady)
    }
    @Test fun acceptedFallbackIsFrozenForPresentationSuccessorAndRecovery() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine, sourceResolver = { TrackSource.Remote(it.songId, StreamFormat.OPUS_128) }, nowMs = { 0 })
        player.replaceQueue(listOf(QueueEntry("a"), QueueEntry("b"), QueueEntry("c")))
        val next = engine.next!!
        engine.emit(EngineEvent.SourceFallback(next.occurrence, TrackSource.Remote("b", StreamFormat.RAW)))
        val updates = engine.updatedNextItems.size
        player.networkChanged() // Re-resolves the successor as Opus; the fallback must not be replaced.
        assertEquals(updates, engine.updatedNextItems.size)
        assertTrue(player.engineSources.contains(TrackSource.Remote("b", StreamFormat.RAW)))
        engine.advance()
        assertEquals(TrackSource.Remote("b", StreamFormat.RAW), player.state.value.currentOccurrence!!.item.source)
        assertEquals(TrackSource.Remote("b", StreamFormat.OPUS_128), player.state.value.currentOccurrence!!.fallbackFrom)
        engine.position = 700
        engine.failCurrent(PlaybackFailure.Network)
        player.probeSucceeded(player.recovery.waiting!!.target)
        assertEquals(TrackSource.Remote("b", StreamFormat.RAW), engine.loadCalls.last().current.source)
        assertEquals(700, engine.loadCalls.last().startPositionMs)
        // A stale event for another occurrence changes nothing.
        engine.emit(EngineEvent.SourceFallback(OccurrenceId(), TrackSource.Remote("x", StreamFormat.RAW)))
        assertEquals(TrackSource.Remote("b", StreamFormat.RAW), player.state.value.currentOccurrence!!.item.source)
    }

    /** Stopping while waiting publishes one state, never "not waiting but still buffering" while unprepared. */
    @Test fun stopWhileWaitingPublishesOneState() = kotlinx.coroutines.test.runTest {
        val engine = FakeAudioEngine()
        var online = true
        val player = PlaybackController(engine, networkAvailable = { online }, nowMs = { 0 })
        val cached = QueueEntry("cached", source = TrackSource.CachedFile("/synthetic"))
        player.replaceQueue(listOf(cached, QueueEntry("remote")))
        online = false; player.networkChanged(); engine.finish()
        assertNotNull(player.state.value.waiting); assertTrue(player.state.value.isBuffering)
        val seen = mutableListOf<PlaybackState>()
        val watch = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { player.state.collect { seen += it } }
        seen.clear()
        player.stop()
        watch.cancel()
        assertEquals("one state for the stop: ${seen.map { it.status }}", 1, seen.size)
        assertNull(seen.single().waiting); assertFalse(seen.single().isBuffering)
    }
}
