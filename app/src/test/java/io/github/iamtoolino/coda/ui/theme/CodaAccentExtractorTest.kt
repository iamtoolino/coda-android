package io.github.iamtoolino.coda.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CodaAccentExtractorTest {
    @Test
    fun `all black uses monochrome fallback`() {
        assertColor(
            CodaAccentExtractor.MONOCHROME_FALLBACK,
            extract(solid(rgb(0, 0, 0))),
        )
    }

    @Test
    fun `neutral gray uses monochrome fallback`() {
        assertColor(
            CodaAccentExtractor.MONOCHROME_FALLBACK,
            extract(solid(rgb(128, 128, 128))),
        )
    }

    @Test
    fun `solid red is preserved`() {
        assertColor(
            CodaAccentColor(128 / 255.0, 0.0, 0.0),
            extract(solid(rgb(128, 0, 0))),
        )
    }

    @Test
    fun `solid blue is preserved`() {
        assertColor(
            CodaAccentColor(0.0, 0.0, 128 / 255.0),
            extract(solid(rgb(0, 0, 128))),
        )
    }

    @Test
    fun `lower value threshold is strict`() {
        assertColor(
            CodaAccentExtractor.MONOCHROME_FALLBACK,
            extract(solid(rgb(30, 0, 0))),
        )

        val accepted = extract(solid(rgb(31, 0, 0)))
        assertNotEquals(CodaAccentExtractor.MONOCHROME_FALLBACK, accepted)
        assertEquals(0.50, maxOf(accepted.red, accepted.green, accepted.blue), TOLERANCE)
    }

    @Test
    fun `upper value threshold is strict`() {
        assertColor(
            CodaAccentColor(239 / 255.0, 0.0, 0.0),
            extract(solid(rgb(239, 0, 0))),
        )
        assertColor(
            CodaAccentExtractor.MONOCHROME_FALLBACK,
            extract(solid(rgb(240, 0, 0))),
        )
    }

    @Test
    fun `saturation threshold is strict`() {
        assertColor(
            CodaAccentExtractor.MONOCHROME_FALLBACK,
            extract(solid(rgb(128, 108, 108))),
        )
        assertColor(
            CodaAccentColor(128 / 255.0, 107 / 255.0, 107 / 255.0),
            extract(solid(rgb(128, 107, 107))),
        )
    }

    @Test
    fun `hue bucket with greater accumulated score wins`() {
        val pixels = IntArray(PIXEL_COUNT) { rgb(0, 0, 0) }
        repeat(100) { pixels[it] = rgb(128, 0, 0) }
        repeat(50) { pixels[100 + it] = rgb(0, 0, 128) }

        assertColor(
            CodaAccentColor(128 / 255.0, 0.0, 0.0),
            extract(pixels),
        )
    }

    @Test
    fun `output lift is added equally to every channel`() {
        val originalRed = 64 / 255.0
        val lift = 0.50 - originalRed
        assertColor(
            CodaAccentColor(0.50, lift, lift),
            extract(solid(rgb(64, 0, 0))),
        )
    }

    private fun extract(pixels: IntArray): CodaAccentColor =
        CodaAccentExtractor.extractFromArgbPixels(pixels)

    private fun solid(color: Int): IntArray = IntArray(PIXEL_COUNT) { color }

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

    private fun assertColor(expected: CodaAccentColor, actual: CodaAccentColor) {
        assertEquals(expected.red, actual.red, TOLERANCE)
        assertEquals(expected.green, actual.green, TOLERANCE)
        assertEquals(expected.blue, actual.blue, TOLERANCE)
    }

    private companion object {
        const val PIXEL_COUNT = 32 * 32
        const val TOLERANCE = 1e-6
    }
}
