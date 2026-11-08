package dev.coda.core.accent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class AccentGoldenTest {
    @Test fun everyCgSampleMatchesSwiftIncludingRawBoundaries() {
        val root = "/accent-golden/"
        val manifest = JSONObject(javaClass.getResourceAsStream(root + "manifest.json")!!
            .bufferedReader().use { it.readText() })
        val fixtures = manifest.getJSONArray("fixtures")
        var maxDiff = 0.0
        var rawCount = 0
        val ids = mutableSetOf<String>()
        for (index in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(index)
            val id = fixture.getString("id")
            ids += id
            if (!fixture.has("source")) rawCount++
            val bytes = javaClass.getResourceAsStream(root + fixture.getString("cg32"))!!.use { it.readBytes() }
            val actual = AccentSelector.select(AccentSampler.sample(bytes))
            val expected = fixture.getJSONObject("accent")
            assertEquals(id, expected.getString("kind"), actual.kind.name)
            for ((channel, value) in listOf("red" to actual.color.red, "green" to actual.color.green, "blue" to actual.color.blue)) {
                val diff = abs(expected.getDouble(channel) - value)
                maxDiff = maxOf(maxDiff, diff)
                // Stronger than Gate A's 1/255 tolerance: expect Double precision parity.
                assertEquals("$id $channel", expected.getDouble(channel), value, 1e-9)
            }
        }
        assertTrue(fixtures.length() >= 44)
        assertTrue(rawCount >= 20)
        for (id in listOf("raw-opposing-coverage-81-of-1024", "raw-opposing-coverage-82-of-1024",
            "raw-split-family-122-of-1024", "raw-split-family-123-of-1024", "raw-hue-wrap")) {
            assertTrue("Missing boundary fixture $id", id in ids)
        }
        println("Gate A: ${fixtures.length()} fixtures ($rawCount raw), all kinds match; max channel diff=$maxDiff (${maxDiff * 255} byte units)")
    }

    @Test fun samplerPreservesMemoryOrderAlphaAndSwiftDivisionOrder() {
        val bytes = ByteArray(4096)
        bytes[0] = 255.toByte() // Discard RGB at zero alpha.
        bytes[4] = 32
        bytes[5] = 64
        bytes[6] = 200.toByte() // Clamp malformed premultiplied channel as Swift does.
        bytes[7] = 128.toByte()
        bytes[4092] = 255.toByte()
        bytes[4095] = 255.toByte()
        val pixels = AccentSampler.sample(bytes)
        assertEquals(1024, pixels.size)
        assertEquals(ArtworkPixel(0.0, 0.0, 0.0, 0.0), pixels.first())
        val alpha = 128.0 / 255
        assertEquals(ArtworkPixel(32.0 / 255 / alpha, 64.0 / 255 / alpha, 1.0, alpha), pixels[1])
        assertEquals(ArtworkPixel(1.0, 0.0, 0.0), pixels.last())
    }

    @Test(expected = IllegalArgumentException::class)
    fun samplerRejectsWrongDimensions() { AccentSampler.sample(ByteArray(4)) }

    // Behavior assertions ported from ArtworkAndPresentationTests.swift at b292fdf.
    @Test fun swiftSelectorExamples() {
        fun repeated(r: Double, g: Double, b: Double, count: Int) = List(count) { ArtworkPixel(r, g, b) }
        val coherent = AccentSelector.select(repeated(0.46, 0.46, 0.46, 600) +
            repeated(0.10, 0.38, 0.78, 300) + repeated(0.82, 0.08, 0.05, 100))
        assertEquals(AccentSelectionKind.chromatic, coherent.kind)
        assertTrue(coherent.color.blue > coherent.color.red && coherent.color.blue > coherent.color.green)
        val tiny = AccentSelector.select(repeated(0.42, 0.42, 0.42, 980) + repeated(0.88, 0.04, 0.02, 20))
        assertNotEquals(AccentSelectionKind.chromatic, tiny.kind)
        assertTrue(abs(tiny.color.red - tiny.color.blue) < 0.10)
        val gray = AccentSelector.select(repeated(0.08, 0.08, 0.08, 700) + repeated(0.34, 0.34, 0.34, 300))
        assertEquals(AccentSelectionKind.neutral, gray.kind)
        assertNotEquals(ArtworkColor.fallback, gray.color)
        assertTrue(abs(gray.color.red - gray.color.blue) < 0.02 && gray.color.red > 0.30)
        val dark = AccentSelector.select(repeated(0.02, 0.06, 0.20, 1000))
        assertEquals(AccentSelectionKind.chromatic, dark.kind)
        assertTrue(dark.color.blue > dark.color.red && dark.color.blue > 0.30)
        val brand = AccentSelection(ArtworkColor.fallback, AccentSelectionKind.brand)
        assertEquals(brand, AccentSelector.select(emptyList()))
        assertEquals(brand, AccentSelector.select(repeated(0.0, 0.0, 0.0, 1000)))
    }
}
