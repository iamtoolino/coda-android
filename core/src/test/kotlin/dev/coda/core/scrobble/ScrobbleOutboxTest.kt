package dev.coda.core.scrobble

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScrobbleOutboxTest {
    @get:Rule val folder = TemporaryFolder()
    private val now = 1_800_000_000_000L
    private fun file() = File(folder.root, "scrobbles.json")
    private fun entry(id: String, at: Long, key: String = "k") = PendingScrobble(id, key, "song-$id", at)

    @Test fun entriesSurviveARestartInOrderWithTheirOriginalTime() {
        val outbox = ScrobbleOutbox(file())
        outbox.add(entry("a", now - 3000), now); outbox.add(entry("b", now - 2000), now); outbox.add(entry("c", now - 1000), now)
        val reopened = ScrobbleOutbox(file())
        assertEquals(listOf("a", "b", "c"), reopened.all().map { it.id })
        assertEquals(now - 3000, reopened.peek("k")!!.playedAtMs)
        reopened.remove("a")
        assertEquals("b", ScrobbleOutbox(file()).peek("k")!!.id)
    }

    @Test fun peekIsPerAccount() {
        val outbox = ScrobbleOutbox(file())
        outbox.add(entry("a", now, "one"), now); outbox.add(entry("b", now, "two"), now)
        assertEquals("b", outbox.peek("two")!!.id)
        assertNull(outbox.peek("three"))
        assertEquals(2, outbox.all().size)
    }

    @Test fun capKeepsTheNewest500() {
        val outbox = ScrobbleOutbox(file())
        for (i in 0 until 510) outbox.add(entry("e$i", now - 510 + i), now)
        val all = ScrobbleOutbox(file()).all()
        assertEquals(500, all.size)
        assertEquals("e10", all.first().id); assertEquals("e509", all.last().id)
    }

    @Test fun ageLimitIsFourteenDays() {
        val day = 24L * 3600 * 1000
        val outbox = ScrobbleOutbox(file())
        outbox.add(entry("old", now - 14 * day - 1), now - 14 * day - 1)
        outbox.add(entry("edge", now - 14 * day), now - 14 * day)
        outbox.add(entry("new", now), now)
        assertEquals(listOf("edge", "new"), outbox.all().map { it.id })
        outbox.prune(now + 1)
        assertEquals(listOf("new"), outbox.all().map { it.id })
        assertEquals(listOf("new"), ScrobbleOutbox(file()).all().map { it.id })
    }

    @Test fun corruptOrMissingFileReadsAsEmptyAndRecovers() {
        assertTrue(ScrobbleOutbox(file()).all().isEmpty())
        file().writeText("{ not json")
        val outbox = ScrobbleOutbox(file())
        assertTrue(outbox.all().isEmpty())
        outbox.add(entry("a", now), now)
        assertEquals(listOf("a"), ScrobbleOutbox(file()).all().map { it.id })
    }

    @Test fun writesAreAtomicAndLeaveNoTemporaryFile() {
        val outbox = ScrobbleOutbox(file())
        outbox.add(entry("a", now), now)
        assertEquals(listOf("scrobbles.json"), folder.root.list()!!.toList())
        assertFalse(file().readText().contains("http"))
        outbox.remove("a")
        assertTrue(file().readText().contains("\"entries\":[]"))
    }

    @Test fun retryingAnAddAfterAnUnobservedCommitKeepsOneEntryAndItsOrder() {
        val outbox = ScrobbleOutbox(file())
        val first = entry("a", now - 1000)
        outbox.add(first, now); outbox.add(entry("b", now), now)
        outbox.add(first, now)
        assertEquals(listOf("a", "b"), ScrobbleOutbox(file()).all().map { it.id })
        assertEquals(now - 1000, outbox.peek("k")!!.playedAtMs)
    }

    @Test fun failedWriteAndRemovalLeaveThePreviousDurableStateAndCanBeRetried() {
        val outbox = ScrobbleOutbox(file())
        outbox.add(entry("a", now), now)
        val temp = File(folder.root, "scrobbles.json.tmp")
        assertTrue(temp.mkdir()) // FileOutputStream cannot open this as a file.
        assertThrows(java.io.IOException::class.java) { outbox.add(entry("b", now), now) }
        assertThrows(java.io.IOException::class.java) { outbox.remove("a") }
        assertEquals(listOf("a"), outbox.all().map { it.id })
        assertEquals(listOf("a"), ScrobbleOutbox(file()).all().map { it.id })
        assertTrue(temp.delete())
        outbox.add(entry("b", now), now); outbox.remove("a")
        assertEquals(listOf("b"), ScrobbleOutbox(file()).all().map { it.id })
    }
    /** A sign-in as another account keeps only that account's unsent plays (also after a restart). */
    @Test fun retainOnlyDropsOtherAccounts() {
        val outbox = ScrobbleOutbox(file())
        outbox.add(entry("a", now, "one"), now); outbox.add(entry("b", now, "two"), now); outbox.add(entry("c", now, "one"), now)
        outbox.retainOnly("one")
        assertEquals(listOf("a", "c"), ScrobbleOutbox(file()).all().map { it.id })
        assertNull(outbox.peek("two"))
    }

    /** A transient read error is not an empty outbox; once storage reads again, the original entries survive an add. */
    @Test fun aTransientReadErrorKeepsTheOriginalEntries() {
        ScrobbleOutbox(file()).apply { add(entry("a", now - 2000), now); add(entry("b", now - 1000), now) }
        val outbox = ScrobbleOutbox(file())
        assertTrue(file().setReadable(false))
        org.junit.Assume.assumeFalse("needs a filesystem that enforces permissions", file().canRead())
        try { outbox.add(entry("c", now), now); fail("expected the read error") } catch (_: java.io.IOException) { }
        assertTrue(file().setReadable(true))
        outbox.add(entry("c", now), now)
        assertEquals(listOf("a", "b", "c"), outbox.all().map { it.id })
        assertEquals(listOf("a", "b", "c"), ScrobbleOutbox(file()).all().map { it.id })
    }
}
