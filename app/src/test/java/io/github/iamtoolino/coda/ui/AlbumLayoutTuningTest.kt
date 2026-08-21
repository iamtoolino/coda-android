package io.github.iamtoolino.coda.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumLayoutTuningTest {
    @Test
    fun `wire names resolve only known layouts`() {
        assertEquals(
            AlbumLayoutVariant.METADATA_FIRST,
            AlbumLayoutVariant.fromWireName("metadata_first"),
        )
        assertNull(AlbumLayoutVariant.fromWireName("unknown"))
    }

    @Test
    fun `offsets are bounded before publishing`() {
        val sanitized = AlbumLayoutTuning(
            buttonsOffsetDp = 500f,
            ratingOffsetDp = -500f,
        ).sanitized()

        assertEquals(AlbumLayoutTuning.MAX_OFFSET_DP, sanitized.buttonsOffsetDp)
        assertEquals(AlbumLayoutTuning.MIN_OFFSET_DP, sanitized.ratingOffsetDp)
    }
}
