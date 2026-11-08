package dev.coda.android.artwork

import org.junit.Assert.*
import org.junit.Test

class CoverArtKeyerTest {
    @Test fun cacheIdentityUsesStableIdsAndSizeOnly() {
        val server = CoverArtKeyer.digest("https://placeholder.invalid|synthetic")
        val key = CoverArtKeyer.key(server, CoverArt("cover-1", 472))
        assertEquals("art:$server:cover-1:472", key)
        listOf("?", "t=", "s=", "p=", "placeholder", "synthetic").forEach { assertFalse(key.contains(it)) }
        assertNotEquals(key, CoverArtKeyer.key(server, CoverArt("cover-1", 1440)))
        assertNotEquals(key, CoverArtKeyer.key(server, CoverArt("cover-2", 472)))
    }
}
