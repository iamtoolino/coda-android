package io.github.iamtoolino.coda.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodaAccentExtractorTest {
    @Test
    fun `algorithm version invalidates cached V1 colors`() {
        assertEquals(2, CodaAccentExtractor.ALGORITHM_VERSION)
    }

    @Test
    fun `unreadable black falls back to Brand`() {
        assertColor(
            CodaAccentExtractor.GENERIC_FALLBACK,
            extract(solid(rgb(0, 0, 0))),
        )
    }

    @Test
    fun `transparent artwork falls back to Brand`() {
        assertColor(
            CodaAccentExtractor.GENERIC_FALLBACK,
            extract(solid(0x00000000)),
        )
    }

    @Test
    fun `supported blue family wins over neutral field and smaller red family`() {
        val pixels = IntArray(PIXEL_COUNT) { index ->
            when {
                index < 614 -> rgb(120, 120, 120)
                index < 921 -> rgb(30, 80, 220)
                else -> rgb(220, 45, 35)
            }
        }

        val result = extract(pixels)

        assertTrue(result.blue > result.red)
        assertTrue(result.blue > result.green)
    }

    @Test
    fun `tiny vivid accent does not overtake neutral artwork`() {
        val pixels = IntArray(PIXEL_COUNT) { index ->
            if (index < 20) rgb(240, 25, 25) else rgb(110, 110, 110)
        }

        val result = extract(pixels)
        val spread = maxOf(result.red, result.green, result.blue) -
            minOf(result.red, result.green, result.blue)

        assertTrue(spread < 20 / 255.0)
    }

    @Test
    fun `grayscale artwork produces a restrained derived neutral`() {
        val result = extract(solid(rgb(110, 110, 110)))

        assertEquals(result.red, result.green, CHANNEL_TOLERANCE)
        assertEquals(result.green, result.blue, CHANNEL_TOLERANCE)
        assertTrue(result.red in 0.35..0.65)
    }

    @Test
    fun `dark blue remains blue and is lifted for UI use`() {
        val result = extract(solid(rgb(4, 16, 48)))

        assertTrue(result.blue > result.red)
        assertTrue(result.blue > result.green)
        assertTrue(maxOf(result.red, result.green, result.blue) >= 0.35)
    }

    private fun extract(pixels: IntArray): CodaAccentColor =
        CodaAccentExtractor.extractFromArgbPixels(pixels)

    private fun solid(color: Int): IntArray = IntArray(PIXEL_COUNT) { color }

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

    private fun assertColor(expected: CodaAccentColor, actual: CodaAccentColor) {
        assertEquals(expected.red, actual.red, EXACT_TOLERANCE)
        assertEquals(expected.green, actual.green, EXACT_TOLERANCE)
        assertEquals(expected.blue, actual.blue, EXACT_TOLERANCE)
    }

    private companion object {
        const val PIXEL_COUNT = 32 * 32
        const val EXACT_TOLERANCE = 1e-9
        const val CHANNEL_TOLERANCE = 1e-6
    }
}
