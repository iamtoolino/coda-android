package dev.coda.core.cache

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

/** Every download line comes from one place, for the sheets, the queue and the notification. */
class DownloadLinesTest {
    private fun status(cached: Int, total: Int, added: Boolean, pending: Int = 0) =
        ItemStatus(cached, total, 0, null, added, pendingTracks = pending)

    /** A kept set with tracks missing and nothing downloading looks not kept; incidental tracks never make a set look partly offline. */
    @Test fun aPartlyMissingKeptSetLooksNotKeptUnlessItIsDownloading() {
        assertTrue(status(10, 10, added = true).looksKept)
        assertTrue("downloading", status(6, 10, added = true, pending = 4).looksKept)
        assertTrue("queued, nothing there yet", status(0, 12, added = true, pending = 12).looksKept)
        assertFalse("tracks aged out", status(7, 10, added = true).looksKept)
        assertFalse("not kept, all on the phone from playing", status(10, 10, added = false).looksKept)
        assertFalse(status(0, 10, added = true).looksKept)
        assertNull(DownloadLines.keepSheet(status(7, 10, added = true), SetPhase.IDLE, "28 Nov"))
        assertNull(DownloadLines.keepSheet(status(1, 12, added = false), SetPhase.IDLE, null))
    }

    /** The album and playlist sheets show no cache origin; only a time limit, or download state. */
    @Test fun sheetKeepLineShowsOnlyTheDateOrDownloadState() {
        assertNull(DownloadLines.keepSheet(status(0, 10, added = false), SetPhase.IDLE, null))
        assertNull("not kept, fully on the phone from playing", DownloadLines.keepSheet(status(10, 10, added = false), SetPhase.IDLE, "28 Nov"))
        assertNull("not kept, partly on the phone", DownloadLines.keepSheet(status(6, 10, added = false), SetPhase.IDLE, "28 Nov"))
        assertNull("kept as long as possible", DownloadLines.keepSheet(status(10, 10, added = true), SetPhase.IDLE, null))
        assertEquals("Preferred until 28 Nov", DownloadLines.keepSheet(status(10, 10, added = true), SetPhase.IDLE, "28 Nov"))
        assertEquals("Downloading 6 of 10", DownloadLines.keepSheet(status(6, 10, added = true, pending = 4), SetPhase.DOWNLOADING, null))
        assertEquals("Queued · 0 of 12", DownloadLines.keepSheet(status(0, 12, added = true, pending = 12), SetPhase.QUEUED, null))
        assertEquals("Waiting for Wi-Fi · 6 of 10", DownloadLines.keepSheet(status(6, 10, added = true, pending = 4), SetPhase.WAITING_FOR_WIFI, null))
    }

    @Test fun phasesComeFromTheSharedState() {
        val state = DownloadState(done = 3, pending = 9, currentKey = "album/a", pendingKeys = setOf("album/a", "album/b"))
        assertEquals(SetPhase.DOWNLOADING, state.phase("album/a"))
        assertEquals(SetPhase.QUEUED, state.phase("album/b"))
        assertEquals(SetPhase.IDLE, state.phase("album/c"))
        assertEquals(SetPhase.WAITING_FOR_WIFI, state.copy(waitingForWifi = true).phase("album/b"))
        assertEquals("Downloading 4 of 12", DownloadLines.notification(state))
        assertEquals("Waiting for Wi-Fi · 3 of 12", DownloadLines.notification(state.copy(waitingForWifi = true)))
        // Only kept covers left is never "Downloading 0 of 0".
        assertEquals("Saving artwork for offline", DownloadLines.notification(DownloadState(coversPending = true, coversDue = true)))
        assertEquals("Saving artwork for offline", DownloadLines.notification(DownloadState(coversDue = true)))
    }

    /** "Ready offline": the queue ⋮ entry and header lines for the four states. */
    @Test fun queueEntryAndHeaderFollowThePf10cStates() {
        val idle = DownloadState()
        assertEquals(DownloadLines.QueueEntry("Cache queue ahead", "17 of 40 still stream", true, false), DownloadLines.queueEntry(40, 23, idle))
        assertEquals("23 ready offline", DownloadLines.queueHeader(40, 23, idle))
        assertEquals(DownloadLines.QueueEntry("Cache queue ahead", "All 40 ready offline", false, false), DownloadLines.queueEntry(40, 40, idle))
        assertEquals(DownloadLines.QueueEntry("Cache queue ahead", "All 40 ready offline until Sat", false, false), DownloadLines.queueEntry(40, 40, idle, "Sat"))
        assertEquals("All 40 ready offline until space runs low", DownloadLines.queueEntry(40, 40, idle, "space runs low").line)
        assertEquals("all ready offline", DownloadLines.queueHeader(40, 40, idle))
        val running = DownloadState(pending = 14, queue = QueueDownload(40, 26))
        assertEquals(DownloadLines.QueueEntry("Caching queue ahead… 26 of 40", "", true, true), DownloadLines.queueEntry(43, 26, running))
        assertEquals("Caching 26 of 40", DownloadLines.queueHeader(43, 26, running))
        val waiting = running.copy(waitingForWifi = true)
        assertEquals(DownloadLines.QueueEntry("Waiting for a network", "26 of 40 ready offline", true, true), DownloadLines.queueEntry(40, 26, waiting))
        assertEquals("Waiting for a network · 26 of 40", DownloadLines.queueHeader(40, 26, waiting))
        assertEquals("Waiting for a network · 0 of 14", DownloadLines.notification(waiting))  // RC-1b; kept-only stays "Waiting for Wi-Fi"
        // Tracks added after the load: the entry is live again and says what still streams.
        assertEquals("3 of 43 still stream", DownloadLines.queueEntry(43, 40, idle).line)
        assertEquals("", DownloadLines.queueHeader(0, 0, idle))
    }

    @Test fun snackbarNamesHowLongLoadedTracksStay() {
        assertEquals("Caching 7 tracks · offline for 3 days", DownloadLines.queueLoadMessage(7, "3 days"))
        assertEquals("Caching 1 track · offline for 12 hours", DownloadLines.queueLoadMessage(1, "12 hours"))
        assertEquals("Caching 7 tracks · offline until space runs low", DownloadLines.queueLoadMessage(7, null))
        // Short enough for the Night snackbar's Short duration (<= 50 characters) even for a long queue.
        assertTrue(DownloadLines.queueLoadMessage(9999, null).length <= 50)
    }

    @Test fun dayIsShortDayAndMonth() {
        val nov28 = java.time.LocalDate.of(2026, 11, 28).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val now = java.time.LocalDate.of(2026, 10, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()   // fixed: the words depend on today
        assertEquals("28 Nov", DownloadLines.day(nov28, Locale.UK, ZoneOffset.UTC, now))
    }

    @Test fun underADayShowsATimeInsteadOfToday() {
        val noon = java.time.LocalDate.of(2026, 11, 20).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val hour = 3_600_000L
        // 12 h step: music arriving now lives until 00:00 tomorrow; one fetched 4 h ago until 08:00 today.
        assertEquals("20:00", DownloadLines.day(noon + 8 * hour, Locale.UK, ZoneOffset.UTC, noon))
        assertEquals("tomorrow 08:00", DownloadLines.day(noon + 20 * hour, Locale.UK, ZoneOffset.UTC, noon))
        // A day or more away keeps the date words.
        assertEquals("tomorrow", DownloadLines.day(noon + 24 * hour, Locale.UK, ZoneOffset.UTC, noon))
        assertEquals("Mon", DownloadLines.day(noon + 3 * 24 * hour, Locale.UK, ZoneOffset.UTC, noon))
    }
}
