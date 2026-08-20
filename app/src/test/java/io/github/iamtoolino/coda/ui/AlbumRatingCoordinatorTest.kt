package io.github.iamtoolino.coda.ui

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumRatingCoordinatorTest {
    @Test
    fun `selection is optimistic and selecting current rating clears it`() = runBlocking {
        val writes = mutableListOf<Int>()
        val coordinator = AlbumRatingCoordinator(CoroutineScope(coroutineContext)) { _, rating ->
            writes += rating
        }

        val first = coordinator.select("album", serverRating = 2, selectedRating = 4)
        assertEquals(4, coordinator.state("album", 2).rating)
        assertEquals(2, coordinator.state("album", 2).confirmedRating)
        assertTrue(coordinator.state("album", 2).isUpdating)
        first.join()

        coordinator.select("album", serverRating = 2, selectedRating = 4).join()

        assertEquals(listOf(4, 0), writes)
        assertEquals(0, coordinator.state("album", 2).rating)
        assertEquals(0, coordinator.state("album", 2).confirmedRating)
        assertFalse(coordinator.state("album", 2).isUpdating)
    }

    @Test
    fun `rapid changes serialize and conflate to latest pending rating`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val writes = mutableListOf<Int>()
        val coordinator = AlbumRatingCoordinator(CoroutineScope(coroutineContext)) { _, rating ->
            writes += rating
            if (writes.size == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }

        val worker = coordinator.select("album", serverRating = 2, selectedRating = 3)
        firstStarted.await()
        assertSame(worker, coordinator.select("album", serverRating = 2, selectedRating = 4))
        assertSame(worker, coordinator.select("album", serverRating = 2, selectedRating = 5))
        assertEquals(5, coordinator.state("album", 2).rating)

        releaseFirst.complete(Unit)
        worker.join()

        assertEquals(listOf(3, 5), writes)
        assertEquals(5, coordinator.state("album", 2).confirmedRating)
        assertEquals(3L, coordinator.state("album", 2).revision)
        assertFalse(coordinator.state("album", 2).isUpdating)
    }

    @Test
    fun `obsolete failure does not rollback newer optimistic rating`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var attempts = 0
        val coordinator = AlbumRatingCoordinator(CoroutineScope(coroutineContext)) { _, _ ->
            attempts++
            if (attempts == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
                throw IOException("first failed")
            }
        }

        val worker = coordinator.select("album", serverRating = 2, selectedRating = 3)
        firstStarted.await()
        coordinator.select("album", serverRating = 2, selectedRating = 5)
        releaseFirst.complete(Unit)
        worker.join()

        assertEquals(2, attempts)
        assertEquals(5, coordinator.state("album", 2).rating)
        assertEquals(5, coordinator.state("album", 2).confirmedRating)
        assertFalse(coordinator.state("album", 2).isUpdating)
    }

    @Test
    fun `latest failure rolls back to last confirmed rating and emits failure`() = runBlocking {
        var attempts = 0
        val coordinator = AlbumRatingCoordinator(CoroutineScope(coroutineContext)) { _, _ ->
            attempts++
            if (attempts == 2) throw IOException("latest failed")
        }

        coordinator.select("album", serverRating = 2, selectedRating = 3).join()
        coordinator.select("album", serverRating = 2, selectedRating = 5).join()
        val failure = coordinator.failures.first()

        assertEquals("album", failure.albumId)
        assertEquals(3, coordinator.state("album", 2).rating)
        assertEquals(3, coordinator.state("album", 2).confirmedRating)
        assertFalse(coordinator.state("album", 2).isUpdating)
    }

    @Test
    fun `closing session cancels write without publishing rollback`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<Unit>()
        val coordinator = AlbumRatingCoordinator(CoroutineScope(coroutineContext)) { _, _ ->
            started.complete(Unit)
            neverCompletes.await()
        }

        val worker = coordinator.select("album", serverRating = 2, selectedRating = 5)
        started.await()
        coordinator.close()
        worker.join()

        assertTrue(worker.isCancelled)
        assertEquals(5, coordinator.state("album", 2).rating)
        assertEquals(2, coordinator.state("album", 2).confirmedRating)
    }
}
