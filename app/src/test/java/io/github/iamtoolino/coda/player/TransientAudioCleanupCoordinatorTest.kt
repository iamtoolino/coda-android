package io.github.iamtoolino.coda.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TransientAudioCleanupCoordinatorTest {
    @Test
    fun `inactive service leaves durable cleanup pending`() {
        val ledger = FakeLedger()
        val dispatched = mutableListOf<Set<String>>()
        val coordinator = TransientAudioCleanupCoordinator(ledger, dispatched::add)

        coordinator.request("old-account")

        assertEquals(setOf("old-account"), ledger.pending())
        assertEquals(listOf(setOf("old-account")), dispatched)
    }

    @Test
    fun `teardown before completion is replayed on next start`() {
        val ledger = FakeLedger(setOf("old-account"))
        val dispatched = mutableListOf<Set<String>>()
        val coordinator = TransientAudioCleanupCoordinator(ledger, dispatched::add)

        coordinator.resumePending()

        assertEquals(setOf("old-account"), ledger.pending())
        assertEquals(listOf(setOf("old-account")), dispatched)
    }

    @Test
    fun `only completed namespace is acknowledged`() {
        val ledger = FakeLedger(setOf("old-account", "other-account"))
        val coordinator = TransientAudioCleanupCoordinator(ledger) {}

        coordinator.complete("old-account")

        assertEquals(setOf("other-account"), ledger.pending())
    }

    @Test
    fun `new request redispatches every still pending namespace`() {
        val ledger = FakeLedger(setOf("first-account"))
        val dispatched = mutableListOf<Set<String>>()
        val coordinator = TransientAudioCleanupCoordinator(ledger, dispatched::add)

        coordinator.request("second-account")

        assertEquals(
            listOf(setOf("first-account", "second-account")),
            dispatched,
        )
    }

    private class FakeLedger(initial: Set<String> = emptySet()) : PendingAudioCleanupLedger {
        private var namespaces = initial

        override fun pending(): Set<String> = namespaces

        override fun replace(namespaces: Set<String>) {
            this.namespaces = namespaces
        }
    }
}
