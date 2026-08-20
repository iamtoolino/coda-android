package io.github.iamtoolino.coda.ui

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCollectionCoordinatorTest {
    @Test
    fun `refresh retains good content when it fails`() = runBlocking {
        var failure: Throwable? = null
        val coordinator = RemoteCollectionCoordinator(
            scope = CoroutineScope(coroutineContext),
            initialKey = Unit,
        ) {
            failure?.let { throw it }
            listOf("loaded")
        }

        coordinator.load().join()
        failure = IOException("offline")
        val refresh = coordinator.refresh()

        assertEquals(listOf("loaded"), coordinator.state.value)
        assertTrue(coordinator.state.isLoading)
        refresh.join()
        assertEquals(listOf("loaded"), coordinator.state.value)
        assertEquals("offline", coordinator.state.errorMessage)
        assertFalse(coordinator.state.isLoading)
    }

    @Test
    fun `new key clears old content and stale completion cannot publish`() = runBlocking {
        val oldRequest = CompletableDeferred<List<String>>()
        val coordinator = RemoteCollectionCoordinator(
            scope = CoroutineScope(coroutineContext),
            initialKey = "old",
        ) { key ->
            when (key) {
                "old" -> try {
                    oldRequest.await()
                } catch (_: CancellationException) {
                    listOf("stale")
                }
                else -> listOf("fresh")
            }
        }

        coordinator.load("old")
        yield()
        val newestRequest = coordinator.load("new")

        assertEquals("new", coordinator.state.key)
        assertNull(coordinator.state.value)
        newestRequest.join()
        yield()
        assertEquals(listOf("fresh"), coordinator.state.value)
        assertNull(coordinator.state.errorMessage)
    }
}
