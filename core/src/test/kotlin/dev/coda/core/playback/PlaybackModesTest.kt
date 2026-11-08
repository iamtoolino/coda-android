package dev.coda.core.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackModesTest {
    @Test fun exposedSuccessorMatchesPreparedOccurrenceAcrossRepeatAndQueueEdits() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        // Same song, distinct queue occurrences: identity must survive the adapter round trip.
        val queue = listOf(QueueEntry("one"), QueueEntry("one"), QueueEntry("two"))
        assertNull(player.successorEntry)
        player.replaceQueue(queue, startsPlaying = false)
        fun assertSuccessor(entry: QueueEntry?) {
            assertEquals(entry, player.successorEntry)
            assertEquals(entry?.songId, (engine.next?.source as? TrackSource.Remote)?.songId)
        }
        assertSuccessor(queue[1])
        player.setRepeatMode(RepeatMode.ONE)
        assertSuccessor(queue[0])
        player.next()
        assertSuccessor(queue[1])
        player.setRepeatMode(RepeatMode.OFF)
        assertSuccessor(queue[2])
        player.move(listOf(queue[0].id), null)
        assertSuccessor(queue[2])
        player.play(2)
        assertSuccessor(null)
        player.setRepeatMode(RepeatMode.ALL)
        assertSuccessor(queue[1])
        player.remove(queue[1].id)
        assertSuccessor(queue[2])
        player.clear()
        assertSuccessor(null)
    }

    @Test fun repeatPreparesFreshOccurrencesAndManualNextLeavesRepeatOne() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        val queue = listOf(QueueEntry("one"), QueueEntry("two"))
        player.replaceQueue(queue)
        val first = player.playbackOccurrenceId
        player.setRepeatMode(RepeatMode.ONE)
        assertEquals("one", (engine.next!!.source as TrackSource.Remote).songId)
        assertNotEquals(first, engine.next!!.occurrence)
        engine.advance()
        assertEquals(0, player.currentIndex)
        assertNotEquals(first, player.playbackOccurrenceId)
        assertEquals("one", player.completedPlayback!!.songId)
        player.next()
        assertEquals(1, player.currentIndex)
        player.setRepeatMode(RepeatMode.ALL)
        assertEquals("one", (engine.next!!.source as TrackSource.Remote).songId)
        engine.advance()
        assertEquals(0, player.currentIndex)
        player.setRepeatMode(RepeatMode.OFF)
        player.next()
        assertNull(engine.next)
        engine.finish()
        assertEquals("Finished", player.status)
    }

    @Test fun legacyShuffleIsIgnored() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        val queue = (0..10).map { QueueEntry("song-$it") }
        player.replaceQueue(queue, startAt = 2)
        val occurrence = player.playbackOccurrenceId
        player.setShuffleEnabled(true)
        assertFalse(player.state.value.shuffleEnabled)
        assertEquals(queue, player.queue)
        assertEquals(queue.toSet(), player.queue.toSet())
        assertEquals(occurrence, player.playbackOccurrenceId)
        player.remove(queue[5].id)
        val added = QueueEntry("new")
        player.enqueue(listOf(added))
        player.setShuffleEnabled(false)
        assertEquals(queue.filter { it != queue[5] } + added, player.queue)
        assertEquals(occurrence, player.playbackOccurrenceId)
    }
}
