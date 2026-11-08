package dev.coda.android.ui.queue

import org.junit.Assert.assertEquals
import org.junit.Test

/** Undo Clear queue restores the entries, the position and the play intent (Swift QueueSnapshot.wasPlaying). */
class ClearedQueueTest {
    private fun undo(wasPlaying: Boolean): List<String> {
        val calls = mutableListOf<String>()
        ClearedQueue(listOf("e1", "e2", "e3"), 1, 42_000L, wasPlaying)
            .restore({ items, index, position -> calls += "set $items $index $position" }, { calls += "play" })
        return calls
    }

    @Test fun aPlayingQueuePlaysAgainAfterUndo() {
        assertEquals(listOf("set [e1, e2, e3] 1 42000", "play"), undo(wasPlaying = true))
    }

    @Test fun aPausedQueueStaysPausedAfterUndo() {
        assertEquals(listOf("set [e1, e2, e3] 1 42000"), undo(wasPlaying = false))
    }

    @Test fun theIndexStaysInsideTheRestoredQueue() {
        val calls = mutableListOf<Int>()
        ClearedQueue(listOf("e1"), 4, 0L, false).restore({ _, index, _ -> calls += index }, {})
        assertEquals(listOf(0), calls)
    }
}
