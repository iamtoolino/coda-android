package io.github.iamtoolino.coda.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackScrobbleTest {
    @Test
    fun `completion callback is independent of scrobble operation availability`() = runBlocking {
        val completed = mutableListOf<String>()
        val coordinator = ScrobbleCoordinator(this, onCompleted = { completed += it }) { _, _, _ -> null }
        coordinator.synchronize("first")
        coordinator.transition("second", PlaybackTransition.MANUAL)
        assertTrue(completed.isEmpty())
        coordinator.transition("third", PlaybackTransition.AUTOMATIC)
        coordinator.playbackEnded()
        coordinator.playbackEnded()
        assertEquals(listOf("second", "third"), completed)
    }
    @Test
    fun `automatic transition submits outgoing occurrence and begins next`() {
        val policy = ScrobblePolicy()
        val first = policy.synchronize("first").single() as ScrobbleAction.NowPlaying

        val actions = policy.transition("second", PlaybackTransition.AUTOMATIC)

        assertEquals(2, actions.size)
        assertEquals(first.occurrence, (actions[0] as ScrobbleAction.Submission).occurrence)
        assertEquals("second", (actions[1] as ScrobbleAction.NowPlaying).occurrence.songId)
    }

    @Test
    fun `final ended state submits once`() {
        val policy = ScrobblePolicy()
        val occurrence = (policy.synchronize("last").single() as ScrobbleAction.NowPlaying).occurrence

        assertEquals(
            occurrence,
            (policy.playbackEnded().single() as ScrobbleAction.Submission).occurrence,
        )
        assertTrue(policy.playbackEnded().isEmpty())
    }

    @Test
    fun `manual transition does not submit outgoing occurrence`() {
        val policy = ScrobblePolicy()
        policy.synchronize("first")

        val actions = policy.transition("second", PlaybackTransition.MANUAL)

        assertEquals(1, actions.size)
        assertEquals("second", (actions.single() as ScrobbleAction.NowPlaying).occurrence.songId)
    }

    @Test
    fun `restored occurrence is not submitted until it actually ends`() {
        val policy = ScrobblePolicy()
        val restored = policy.synchronize("restored").single() as ScrobbleAction.NowPlaying

        assertTrue(policy.synchronize("restored").isEmpty())
        assertEquals(
            restored.occurrence,
            (policy.playbackEnded().single() as ScrobbleAction.Submission).occurrence,
        )
    }

    @Test
    fun `playlist metadata replacement preserves current occurrence`() {
        val policy = ScrobblePolicy()
        policy.synchronize("current")

        assertTrue(
            policy.transition("current", PlaybackTransition.PLAYLIST_CHANGED).isEmpty(),
        )
    }

    @Test
    fun `repeat submits completed occurrence and begins a distinct occurrence`() {
        val policy = ScrobblePolicy()
        val first = policy.synchronize("repeat").single() as ScrobbleAction.NowPlaying

        val actions = policy.transition("repeat", PlaybackTransition.REPEAT)
        val submission = actions[0] as ScrobbleAction.Submission
        val repeated = actions[1] as ScrobbleAction.NowPlaying

        assertEquals(first.occurrence, submission.occurrence)
        assertEquals(first.occurrence.songId, repeated.occurrence.songId)
        assertTrue(first.occurrence.id != repeated.occurrence.id)
    }

    @Test
    fun `session invalidation discards occurrence without submission`() {
        val policy = ScrobblePolicy()
        policy.synchronize("current")

        policy.invalidate()

        assertTrue(policy.playbackEnded().isEmpty())
    }

    @Test
    fun `scrobble retries until it succeeds`() = runBlocking {
        var attempts = 0

        val result = retryScrobble(maxAttempts = 5, initialDelayMs = 0) {
            attempts++
            if (attempts < 3) error("Temporary failure")
        }

        assertTrue(result)
        assertEquals(3, attempts)
    }

    @Test
    fun `scrobble stops after maximum attempts`() = runBlocking {
        var attempts = 0

        val result = retryScrobble(maxAttempts = 4, initialDelayMs = 0) {
            attempts++
            error("Still offline")
        }

        assertFalse(result)
        assertEquals(4, attempts)
    }
}
