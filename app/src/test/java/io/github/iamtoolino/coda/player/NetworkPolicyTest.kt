package io.github.iamtoolino.coda.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicyTest {
    @Test
    fun `direct wifi and ethernet use original quality`() {
        assertFalse(shouldUseMobileQuality(NetworkSnapshot(wifi = true)))
        assertFalse(shouldUseMobileQuality(NetworkSnapshot(ethernet = true)))
    }

    @Test
    fun `direct cellular and unknown networks use mobile quality`() {
        assertTrue(shouldUseMobileQuality(NetworkSnapshot(cellular = true)))
        assertTrue(shouldUseMobileQuality(NetworkSnapshot()))
    }

    @Test
    fun `vpn over validated wifi uses original quality`() {
        assertFalse(
            shouldUseMobileQuality(
                active = NetworkSnapshot(vpn = true),
                underlyingCandidates = listOf(
                    NetworkSnapshot(wifi = true, validatedInternet = true),
                ),
            ),
        )
    }

    @Test
    fun `vpn ignores unrelated unvalidated wifi`() {
        assertTrue(
            shouldUseMobileQuality(
                active = NetworkSnapshot(vpn = true),
                underlyingCandidates = listOf(
                    NetworkSnapshot(wifi = true, validatedInternet = false),
                    NetworkSnapshot(cellular = true, validatedInternet = true),
                ),
            ),
        )
    }

    @Test
    fun `validated wifi remains preferred when cellular is also available`() {
        assertFalse(
            shouldUseMobileQuality(
                active = NetworkSnapshot(vpn = true),
                underlyingCandidates = listOf(
                    NetworkSnapshot(wifi = true, validatedInternet = true),
                    NetworkSnapshot(cellular = true, validatedInternet = true),
                ),
            ),
        )
    }

    @Test
    fun `vpn without a validated physical network prefers mobile quality`() {
        assertTrue(
            shouldUseMobileQuality(
                active = NetworkSnapshot(vpn = true),
                underlyingCandidates = listOf(NetworkSnapshot(wifi = true)),
            ),
        )
    }
    @Test fun `cellular preserves completed originals but replaces partial and missing originals`() {
        assertEquals("raw", upcomingStreamVariant(mobile = true, originalFullyCached = true))
        assertEquals("opus", upcomingStreamVariant(mobile = true, originalFullyCached = false))
    }

    @Test fun `wifi requests originals regardless of cache completion`() {
        assertEquals("raw", upcomingStreamVariant(mobile = false, originalFullyCached = true))
        assertEquals("raw", upcomingStreamVariant(mobile = false, originalFullyCached = false))
    }

}
