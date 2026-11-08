package dev.coda.android.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** Undo: removal ranges come from queue-entry ids, never song ids or recomputed indices. */
class QueueInsertionsTest {
    @Test fun emptyQueueBeforeInsertAndAfterAdvance() {
        // Inserted into an empty queue: entries sit at 0..1 (the old index calculation looked at 1 and failed).
        assertEquals(listOf(0..1), QueueInsertions.removalRanges(listOf("a", "b"), setOf("a", "b")))
        // Playback advanced past the first inserted entry: only what is still queued is removed.
        assertEquals(listOf(0..0), QueueInsertions.removalRanges(listOf("b", null), setOf("a", "b")))
    }
    @Test fun duplicateSongsAreNotConfused() {
        // The queue holds the same songs twice; only entries 3..4 carry the inserted ids.
        assertEquals(listOf(3..4), QueueInsertions.removalRanges(listOf(null, null, null, "x1", "x2"), setOf("x1", "x2")))
    }
    @Test fun replacedQueueRemovesNothing() {
        assertEquals(emptyList<IntRange>(), QueueInsertions.removalRanges(listOf(null, null, "other"), setOf("x1", "x2")))
        assertEquals(emptyList<IntRange>(), QueueInsertions.removalRanges(emptyList(), setOf("x1")))
    }
    @Test fun splitRunsAreRemovedHighestFirst() {
        assertEquals(listOf(4..4, 1..2), QueueInsertions.removalRanges(listOf(null, "a", "b", null, "c"), setOf("a", "b", "c")))
    }
}
