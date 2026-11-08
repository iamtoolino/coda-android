package dev.coda.core.playback

import org.junit.Assert.*
import org.junit.Test

/** The seek decision inside an uncached transcode, and the reopen debounce. */
class TranscodeSeekTest {
    private fun inBuffer(targetMs: Long, readMs: Long?, bufferedMs: Long?) = (targetMs * 1_000).let { target ->
        TranscodeSeek.buffered(target, OpusSeekHead.preRollStartUs(target), readMs?.times(1_000) ?: Long.MIN_VALUE, bufferedMs?.times(1_000))
    }

    @Test fun onlyAForwardTargetWhosePreRollIsStillQueuedIsBuffered() {
        assertTrue("ahead, loaded", inBuffer(8_500, readMs = 1_000, bufferedMs = 12_000))
        assertTrue("everything loaded", inBuffer(15_000, readMs = 1_000, bufferedMs = null))
        assertTrue("nothing read yet", inBuffer(700, readMs = null, bufferedMs = 5_000))
        assertFalse("past the loaded edge", inBuffer(12_000, readMs = 1_000, bufferedMs = 12_000))
        assertFalse("backwards: read samples are gone", inBuffer(500, readMs = 4_000, bufferedMs = null))
        assertFalse("the pre-roll was already read", inBuffer(4_500, readMs = 4_000, bufferedMs = null))
        assertTrue("just past it (600 ms pre-roll + 200 ms margin)", inBuffer(4_800, readMs = 4_000, bufferedMs = null))
    }

    @Test fun planBufferedBeyondExtensionAndOffsetStreams() {
        assertEquals(TranscodeSeek.Plan.Local, TranscodeSeek.plan(8_500, 16_000, inBuffer = true, offsetStream = false, serverSupportsOffset = false))
        assertEquals(TranscodeSeek.Plan.Local, TranscodeSeek.plan(8_500, 16_000, inBuffer = true, offsetStream = true, serverSupportsOffset = true))
        assertEquals(TranscodeSeek.Plan.Reopen(8_500), TranscodeSeek.plan(8_500, 16_000, inBuffer = false, offsetStream = false, serverSupportsOffset = true))
        assertEquals("without the extension: as before", TranscodeSeek.Plan.Player,
            TranscodeSeek.plan(8_500, 16_000, inBuffer = false, offsetStream = false, serverSupportsOffset = false))
        assertEquals("a stream started at timeOffset always reopens (MP-2b)", TranscodeSeek.Plan.Reopen(0),
            TranscodeSeek.plan(0, null, inBuffer = false, offsetStream = true, serverSupportsOffset = false))
    }

    @Test fun reopensAreClampedInsideTheSong() {
        assertEquals(TranscodeSeek.Plan.Reopen(15_999), TranscodeSeek.plan(40_000, 16_000, inBuffer = false, offsetStream = true, serverSupportsOffset = true))
        assertEquals(15_999L, TranscodeSeek.clamp(16_000, 16_000))
        assertEquals("unknown length", 40_000L, TranscodeSeek.clamp(40_000, null))
        assertEquals(0L, TranscodeSeek.clamp(-5, 16_000))
        assertEquals("floor(15.999 s) stays below the end", 15, TranscodeOffset.requestSeconds(TrackSource.Remote("s", StreamFormat.OPUS_128), 15_999))
    }

    /** seek(0) / "previous" before the first open of a resume landed: the stream may still start at the offset (Rex R2-P3-1). */
    @Test fun seekToZeroBeforeTheFirstOpenReopensFromZero() {
        assertEquals(TranscodeSeek.Plan.Reopen(0), TranscodeSeek.plan(0, null, inBuffer = false, offsetStream = true, serverSupportsOffset = false))
        assertEquals(0, TranscodeOffset.requestSeconds(TrackSource.Remote("s", StreamFormat.OPUS_128), 0))
    }

    private class Clock {
        var now = 0L
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val post: (Long, () -> Unit) -> () -> Unit = { delay, block ->
            val task = (now + delay) to block
            tasks += task
            ({ tasks.remove(task) })
        }
        fun advance(ms: Long) {
            now += ms
            tasks.filter { it.first <= now }.forEach { tasks.remove(it); it.second() }
        }
    }

    @Test fun aBurstOfSeeksOpensOnlyTheLastTarget() {
        val clock = Clock()
        val reopen = LatestReopen(clock.post)
        val opened = mutableListOf<Long>()
        for (target in listOf(2_000L, 4_000, 6_000, 8_500)) { reopen.request(target, opened::add); clock.advance(50) }
        assertEquals("nothing while the drag goes on", emptyList<Long>(), opened)
        assertEquals("the position shows the latest target", 8_500L, reopen.pendingMs)
        clock.advance(TranscodeSeek.REOPEN_DEBOUNCE_MS)
        assertEquals(listOf(8_500L), opened)
        assertNull(reopen.pendingMs)
        reopen.request(3_000, opened::add)
        reopen.clear() // superseded (a local seek, a load, stop)
        clock.advance(1_000)
        assertEquals(listOf(8_500L), opened)
        assertNull(reopen.pendingMs)
    }
}
