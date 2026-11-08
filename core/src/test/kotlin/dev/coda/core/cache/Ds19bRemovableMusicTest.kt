package dev.coda.core.cache

import dev.coda.core.playback.StreamFormat.RAW
import org.junit.Assert.assertEquals
import org.junit.Test

/** The status card's "To clean up" counts only music maintenance could remove: not the window, leases or writes. */
class Ds19bRemovableMusicTest : CacheFixture() {
    @Test fun pinnedLeasedAndWritingSongsAreNotRemovable() {
        val cache = open()
        cache.put("a", size = 100); cache.put("b", size = 200); cache.put("c", size = 400)
        assertEquals(700, cache.removableMusicBytes())
        cache.pin(listOf("a"))
        assertEquals(600, cache.removableMusicBytes())
        cache.acquire("b")
        assertEquals(400, cache.removableMusicBytes())
        val write = cache.begin(dev.coda.core.cache.TrackKey("d", RAW))!!
        assertEquals("a song still downloading is no committed music", 400, cache.removableMusicBytes())
        write.close()
        cache.pin(emptyList()); cache.retainLeases(emptyList())
        assertEquals(700, cache.removableMusicBytes())
    }
}
