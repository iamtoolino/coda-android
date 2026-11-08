package dev.coda.android.ui.queue

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueAlbumSubtitleTest {
    private fun min(m: Long, s: Long = 0) = (m * 60 + s) * 1000

    @Test fun formatsDurationLikeMacOs() {
        assertEquals("0m", formatCollectionDuration(0))
        assertEquals("0m", formatCollectionDuration(min(0, 59)))
        assertEquals("59m", formatCollectionDuration(min(59, 59)))
        assertEquals("1h 0m", formatCollectionDuration(min(60)))
        assertEquals("1h 12m", formatCollectionDuration(min(72, 40)))
        assertEquals("10h 5m", formatCollectionDuration(min(605)))
        assertEquals("53m", formatCollectionDuration(min(53, 25)))
    }

    @Test fun subtitleWithAndWithoutYear() {
        assertEquals("Artist · 2019 · 53m", albumSubtitle("Artist", 2019, min(53, 25)))
        assertEquals("Artist · 53m", albumSubtitle("Artist", null, min(53, 25)))
        assertEquals("Artist · 53m", albumSubtitle("Artist", 0, min(53, 25)))
    }

    @Test fun spokenFormIncludesYearAndDuration() {
        assertEquals("Artist, 2019, 1 hour 12 minutes", albumSubtitleSpoken("Artist", 2019, min(72)))
        assertEquals("Artist, 2 hours 0 minutes", albumSubtitleSpoken("Artist", null, min(120)))
        assertEquals("Artist, 2019", albumSubtitleSpoken("Artist", 2019, 0))
        assertEquals("Artist, 53 minutes", albumSubtitleSpoken("Artist", null, min(53)))
    }
}
