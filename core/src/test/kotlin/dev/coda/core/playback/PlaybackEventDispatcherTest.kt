package dev.coda.core.playback

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackEventDispatcherTest {
    @Test fun allSinksReceiveEveryBufferedEventInOrder() = runBlocking {
        val engine = FakeAudioEngine()
        val controller = PlaybackController(engine)
        controller.replaceQueue(listOf(QueueEntry("a"), QueueEntry("b")))
        val first = mutableListOf<PlaybackEvent>()
        val second = mutableListOf<PlaybackEvent>()
        val dispatcher = PlaybackEventDispatcher(controller, this, listOf({ first += it }, { second += it }))
        engine.emit(EngineEvent.Advanced(engine.next!!.occurrence))
        yield()
        assertEquals(3, first.size)
        assertEquals(first, second)
        assertTrue(first[1] is PlaybackEvent.Completed)
        dispatcher.release()
        controller.release()
    }

    @Test fun selectingAnotherItemWhilePausedDoesNotStartAnOccurrence() = runBlocking {
        val engine = FakeAudioEngine()
        val controller = PlaybackController(engine)
        val events = mutableListOf<PlaybackEvent>()
        val dispatcher = PlaybackEventDispatcher(controller, this, listOf({ events += it }))
        controller.replaceQueue(listOf(QueueEntry("a"), QueueEntry("b")), startsPlaying = false)
        controller.seek(1, 750)
        yield()
        assertFalse(controller.playWhenReady)
        assertFalse(engine.loadCalls.last().playWhenReady)
        assertEquals(750L, engine.loadCalls.last().startPositionMs)
        assertTrue(events.isEmpty())
        controller.play()
        yield()
        assertEquals(1, events.size)
        assertEquals("b", (events.single() as PlaybackEvent.OccurrenceStarted).entry.songId)
        dispatcher.release(); controller.release()
    }

    @Test fun commandsAndLateCallbacksAfterReleaseDoNotThrowOnClosedChannel() {
        val engine = FakeAudioEngine()
        val controller = PlaybackController(engine)
        val listener = engine.listener!!
        controller.replaceQueue(listOf(QueueEntry("a")))
        val occurrence = engine.current!!.occurrence
        controller.release()
        listener.onEvent(EngineEvent.Ended(occurrence))
        controller.replaceQueue(listOf(QueueEntry("b")))
        controller.play()
        controller.release()
    }
}
