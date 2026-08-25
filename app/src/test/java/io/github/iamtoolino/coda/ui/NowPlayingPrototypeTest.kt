package io.github.iamtoolino.coda.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowPlayingPrototypeTest {
    @Test
    fun `wire names resolve only known Now Playing concepts`() {
        NowPlayingPrototype.entries.forEach { prototype ->
            assertEquals(prototype, NowPlayingPrototype.fromWireName(prototype.wireName))
        }
        assertNull(NowPlayingPrototype.fromWireName("old-theme-preset"))
        assertNull(NowPlayingPrototype.fromWireName(null))
    }

    @Test
    fun `title stress wire names reject obsolete theme values`() {
        NowPlayingTitleStress.entries.forEach { stress ->
            assertEquals(stress, NowPlayingTitleStress.fromWireName(stress.wireName))
        }
        assertNull(NowPlayingTitleStress.fromWireName("manrope"))
        assertNull(NowPlayingTitleStress.fromWireName(null))
    }
}
