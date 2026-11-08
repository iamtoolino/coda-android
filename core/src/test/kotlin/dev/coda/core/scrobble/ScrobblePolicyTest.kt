package dev.coda.core.scrobble

import dev.coda.core.subsonic.SubsonicError
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ScrobblePolicyTest {
    private val rows = javaClass.getResourceAsStream("/scrobble/swift-golden.tsv")!!
        .bufferedReader().useLines { lines -> lines.filterNot { it.startsWith("#") }.map { it.split('\t') }.toList() }

    @Test fun retryDelaysMatchSwift() {
        val cases = rows.filter { it[0] == "retry" }
        assertEquals(8, cases.size)
        for (r in cases) {
            val expected = if (r[3] == "-") emptyList() else r[3].split(',').map(String::toLong)
            assertEquals(r.toString(), expected, ScrobblePolicy.retryDelaysMs(r[1].toInt(), r[2].toLong()))
        }
        assertEquals(listOf(1000L, 2000, 4000, 8000, 16000), ScrobblePolicy.retryDelaysMs())
    }

    @Test fun songIdIsTrimmedAndMustNotBeEmpty() {
        assertEquals("song/1", ScrobblePolicy.normalizedSongId(" song/1 "))
        assertNull(ScrobblePolicy.normalizedSongId("  \n"))
    }

    @Test fun failuresAreClassifiedForTheOutbox() {
        assertEquals(ScrobblePolicy.Failure.RETRY, ScrobblePolicy.classify(SubsonicError.Network(IOException())))
        assertEquals(ScrobblePolicy.Failure.RETRY, ScrobblePolicy.classify(SubsonicError.Http(503)))
        assertEquals(ScrobblePolicy.Failure.RETRY, ScrobblePolicy.classify(SubsonicError.Http(429)))
        assertEquals(ScrobblePolicy.Failure.REJECTED, ScrobblePolicy.classify(SubsonicError.Http(404)))
        assertEquals(ScrobblePolicy.Failure.REJECTED, ScrobblePolicy.classify(SubsonicError.NotFound()))
        assertEquals(ScrobblePolicy.Failure.RETRY, ScrobblePolicy.classify(SubsonicError.Server(0, null)))
        assertEquals(ScrobblePolicy.Failure.RETRY, ScrobblePolicy.classify(SubsonicError.Server(null, null)))
        assertEquals(ScrobblePolicy.Failure.RETRY, ScrobblePolicy.classify(SubsonicError.Decoding()))
        assertEquals(ScrobblePolicy.Failure.RETRY, ScrobblePolicy.classify(IllegalStateException()))
        assertEquals(ScrobblePolicy.Failure.RETRY, ScrobblePolicy.classify(SubsonicError.Http(408)))
        assertEquals(ScrobblePolicy.Failure.UNAUTHORIZED, ScrobblePolicy.classify(SubsonicError.Http(401)))
        assertEquals(ScrobblePolicy.Failure.UNAUTHORIZED, ScrobblePolicy.classify(SubsonicError.Http(403)))
        assertEquals(ScrobblePolicy.Failure.UNAUTHORIZED, ScrobblePolicy.classify(SubsonicError.Unauthorized()))
        assertThrows(CancellationException::class.java) { ScrobblePolicy.classify(CancellationException()) }
    }

    @Test fun pruneDropsOldEntriesThenTheOldestBeyondTheCap() {
        val now = 100L * 24 * 3600 * 1000
        val day = 24L * 3600 * 1000
        fun entry(i: Int, at: Long) = PendingScrobble("e$i", "k", "s$i", at)
        val aged = listOf(entry(0, now - 15 * day), entry(1, now - 14 * day), entry(2, now - 1))
        assertEquals(listOf("e1", "e2"), ScrobblePolicy.prune(aged, now).map { it.id })
        val many = (0 until 503).map { entry(it, now - 503 + it) }
        val pruned = ScrobblePolicy.prune(many, now)
        assertEquals(500, pruned.size)
        assertEquals("e3", pruned.first().id); assertEquals("e502", pruned.last().id)
    }
}
