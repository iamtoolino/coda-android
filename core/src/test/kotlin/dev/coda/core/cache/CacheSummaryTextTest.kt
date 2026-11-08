package dev.coda.core.cache

import dev.coda.core.cache.StorageSegment.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The storage bar's parts, the legend texts and the card's one TalkBack text come
 * from one source, in the four states of Dana's mockups (a 128 GB phone, Keep free 8 GB).
 */
class CacheSummaryTextTest {
    private val gb = CachePolicy.GB
    private fun phone(music: Long, art: Long, other: Long, free: Long, line: Long = 8 * gb) =
        CacheSummary(totalBytes = music + art, freeBytes = free, lineBytes = line, capacityBytes = music + art + other + free, artworkBytes = art)

    @Test fun state1Normal() {
        val s = phone(music = 18_400_000_000, art = 600_000_000, other = 61 * gb, free = 48 * gb)
        assertEquals(listOf(ARTWORK to 600_000_000L, MUSIC to 18_400_000_000L, AVAILABLE to 40 * gb, KEEP_FREE to 8 * gb, OTHER to 61 * gb), s.segments)
        assertEquals(128 * gb, s.segments.sumOf { it.second })
        assertEquals("18 GB", s.musicText)
        assertEquals("40 GB", s.availableText)
        assertEquals("8 GB", s.keepFreeText)
        assertNull(s.caution); assertNull(s.cautionHint)
        assertEquals("Coda uses 19 GB: artwork 600 MB, music 18 GB, available to Coda 40 GB, Keep free 8 GB, other apps and files 61 GB.", s.spoken)
    }

    @Test fun state2Tight() {
        val s = phone(music = 31 * gb, art = 700_000_000, other = 86 * gb, free = 10_300_000_000)
        assertEquals(listOf(ARTWORK, MUSIC, AVAILABLE, KEEP_FREE, OTHER), s.segments.map { it.first })
        assertEquals("2.3 GB", s.availableText)
        assertEquals("8 GB", s.keepFreeText)
        assertNull(s.caution)
        assertTrue(s.spoken, s.spoken.contains("available to Coda 2.3 GB, Keep free 8 GB,"))
    }

    @Test fun state3OverTheLineCodaCleansUp() {
        val s = phone(music = 31 * gb, art = 700_000_000, other = 91_300_000_000, free = 5 * gb)
        // The Keep free zone reaches into Music: that slice is the clean-up part; Keep free is only what is really free.
        assertEquals(listOf(ARTWORK to 700_000_000L, MUSIC to 28 * gb, CLEAN_UP to 3 * gb, KEEP_FREE to 5 * gb, OTHER to 91_300_000_000L), s.segments)
        assertEquals(3 * gb, s.cleanUpBytes)
        assertFalse(s.nothingToCleanUp)
        assertEquals("31 GB", s.musicText)
        assertEquals("None", s.availableText)
        assertEquals("5.0 GB of 8 GB", s.keepFreeText)
        assertEquals("Only 5.0 GB free, under your 8 GB Keep free. Coda will clean up about 3.0 GB of music.", s.caution)
        assertNull(s.cautionHint)
        assertEquals("Coda uses 32 GB: artwork 700 MB, music 31 GB, available to Coda none, Keep free 5.0 GB of 8 GB, " +
            "other apps and files 91 GB. Only 5.0 GB free, under your 8 GB Keep free. Coda will clean up about 3.0 GB of music.", s.spoken)
    }

    @Test fun state3MixedLessMusicThanTheDeficit() {
        // All music is the clean-up part (the mark sits at Music's start); the state 3 wording stays, with what can go.
        val s = phone(music = gb, art = 400_000_000, other = 100 * gb, free = 5 * gb)
        assertEquals(listOf(ARTWORK to 400_000_000L, CLEAN_UP to gb, KEEP_FREE to 5 * gb, OTHER to 100 * gb), s.segments)
        assertEquals("Only 5.0 GB free, under your 8 GB Keep free. Coda will clean up about 1.0 GB of music.", s.caution)
    }

    @Test fun state4NothingToCleanUp() {
        val s = phone(music = 0, art = 400_000_000, other = 122_600_000_000, free = 5 * gb)
        assertTrue(s.nothingToCleanUp)
        assertEquals(listOf(ARTWORK to 400_000_000L, KEEP_FREE to 5 * gb, OTHER to 122_600_000_000L), s.segments)
        assertEquals("None", s.musicText)
        assertEquals("None", s.availableText)
        assertEquals("5.0 GB of 8 GB", s.keepFreeText)
        val caution = "Only 5.0 GB free, under your 8 GB Keep free, and Coda has no music left to remove. " +
            "Songs stream; only the next few are saved while you listen."
        val hint = "To keep music offline, free up space on your phone or lower Keep free."
        assertEquals(caution, s.caution)
        assertEquals(hint, s.cautionHint)
        assertEquals("Coda uses 400 MB: artwork 400 MB, music none, available to Coda none, Keep free 5.0 GB of 8 GB, " +
            "other apps and files 123 GB. $caution $hint", s.spoken)
    }

    @Test fun atTheLineIsNotOverIt() {
        val s = phone(music = 10 * gb, art = 0, other = 50 * gb, free = 8 * gb)
        assertNull(s.caution)
        assertEquals(listOf(MUSIC to 10 * gb, KEEP_FREE to 8 * gb, OTHER to 50 * gb), s.segments)
        assertEquals("8 GB", s.keepFreeText)
    }

    @Test fun unknownVolumeSizeHasNoOtherPart() {
        val unknown = CacheSummary(totalBytes = gb, freeBytes = 150 * gb, lineBytes = 50 * gb)
        assertEquals(0, unknown.otherBytes)
        assertFalse(unknown.segments.any { it.first == OTHER })
        assertFalse(unknown.spoken.contains("other apps"))
        // The placeholder before the first measurement (free unknown as Long.MAX_VALUE) does not overflow into an Other part.
        assertEquals(0, CacheSummary(5 * gb, Long.MAX_VALUE, 0).otherBytes)
        // Free plus Coda above the reported size: never negative.
        assertEquals(0, CacheSummary(totalBytes = 10 * gb, freeBytes = 100 * gb, lineBytes = 5 * gb, capacityBytes = 100 * gb).otherBytes)
        // Negative free space counts as 0, as in the bar.
        assertEquals(90 * gb, CacheSummary(totalBytes = 10 * gb, freeBytes = -gb, lineBytes = 5 * gb, capacityBytes = 100 * gb).otherBytes)
    }

    @Test fun underOneGigabyteAvailableIsNone() {
        val s = CacheSummary(totalBytes = 0, freeBytes = 50 * gb + 999_000_000, lineBytes = 50 * gb)
        assertEquals(999_000_000, s.availableBytes)
        assertEquals("None", s.availableText)
        assertEquals("1.0 GB", CacheSummary(0, 51 * gb, 50 * gb).availableText)
    }

    @Test fun keepFreeNotInWholeGigabytesUsesTheSizeFormat() {
        assertEquals("1.5 GB", CacheSummary(0, 10 * gb, 1_500_000_000).keepFreeText)
    }

    @Test fun state4WhileListeningOnlyWindowSongsLeft() {
        // Rex: the read-ahead window and the player's files can't go, so this is state 4, not a clean-up that never happens.
        val s = CacheSummary(totalBytes = 430_000_000, freeBytes = 5 * gb, lineBytes = 8 * gb, capacityBytes = 128 * gb,
            artworkBytes = 400_000_000, removableMusicBytes = 0)
        assertTrue(s.nothingToCleanUp)
        assertEquals(0, s.cleanUpBytes)
        assertEquals(listOf(ARTWORK to 400_000_000L, MUSIC to 30_000_000L, KEEP_FREE to 5 * gb, OTHER to 122_570_000_000L), s.segments)
        assertEquals("30 MB", s.musicText)
        assertTrue(s.caution!!.contains("Coda has no music left to remove"))
        assertEquals("To keep music offline, free up space on your phone or lower Keep free.", s.cautionHint)
    }

    @Test fun cleanUpIsAtMostTheRemovableMusic() {
        val s = CacheSummary(totalBytes = 31_700_000_000, freeBytes = 5 * gb, lineBytes = 8 * gb, capacityBytes = 128 * gb,
            artworkBytes = 700_000_000, removableMusicBytes = gb)
        assertEquals(gb, s.cleanUpBytes)
        assertEquals(listOf(ARTWORK to 700_000_000L, MUSIC to 30 * gb, CLEAN_UP to gb, KEEP_FREE to 5 * gb, OTHER to 91_300_000_000L), s.segments)
        assertEquals("Only 5.0 GB free, under your 8 GB Keep free. Coda will clean up about 1.0 GB of music.", s.caution)
    }

    @Test fun thePlaceholderBeforeTheFirstMeasurementIsNotMeasured() {
        assertFalse(CacheSummary(5 * gb, Long.MAX_VALUE, 0).measured)
        assertTrue(CacheSummary(5 * gb, 10 * gb, 0).measured)
    }
}
