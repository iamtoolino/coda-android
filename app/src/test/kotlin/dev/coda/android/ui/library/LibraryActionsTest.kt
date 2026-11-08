package dev.coda.android.ui.library

import dev.coda.core.subsonic.SubsonicError
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryActionsTest {
    private fun check(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block() } finally { Dispatchers.resetMain() }
    }
    @Test fun optimisticEditSerializesRollsBackAndCanClear() = check {
        val gate = CompletableDeferred<Unit>()
        var fail = false
        val calls = mutableListOf<Pair<String, Int>>()
        val model = LibraryActions({ true }, {}, { id, rating ->
            calls += id to rating; gate.await(); if (fail) throw java.io.IOException()
        })
        model.rate("album", 4)
        assertEquals(4, model.ratings.value.overrides["album"])
        assertTrue(model.ratings.value.busy)
        model.rate("song", 2); runCurrent()
        assertEquals(listOf("album" to 4), calls)
        gate.complete(Unit); advanceUntilIdle()
        fail = true; model.rate("album", 5); advanceUntilIdle()
        assertEquals(4, model.ratings.value.overrides["album"])
        assertTrue(model.ratings.value.failed)
        assertEquals("album", model.ratings.value.failedId)
        fail = false; model.rate("album", 0); advanceUntilIdle()
        assertEquals(0, model.ratings.value.overrides["album"])
        assertFalse(model.ratings.value.failed)
        assertNull(model.ratings.value.failedId)
    }
    @Test fun dragKeepsLatestOptimisticValueAndSerializesServerWrites() = check {
        val gate = CompletableDeferred<Unit>()
        val calls = mutableListOf<Int>()
        val model = LibraryActions({ true }, {}, { _, rating -> calls += rating; if (calls.size == 1) gate.await() })
        model.rate("album", 1); runCurrent()
        model.rate("album", 3); model.rate("album", 5)
        assertEquals(5, model.ratings.value.overrides["album"])
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(1, 5), calls)
        assertFalse(model.ratings.value.busy)
    }
    @Test fun staleSessionDoesNotPublishOrExpireNewLogin() = check {
        var current = true
        val gate = CompletableDeferred<Unit>()
        val model = LibraryActions({ current }, { fail("Stale response must not expire a new session") }, { _, _ ->
            gate.await(); throw SubsonicError.Unauthorized()
        })
        model.rate("song", 2); runCurrent(); current = false
        gate.complete(Unit); advanceUntilIdle()
        assertFalse(model.ratings.value.failed)
    }
}
