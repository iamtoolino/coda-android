package dev.coda.core.accent

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test

/** Non-JPEG source-edge cap. Synthetic images only. */
class ReducedDecodePlanCapTest {
    private fun syntheticPng(size: Int, rgb: Int): ByteArray {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until size) for (x in 0 until size) image.setRGB(x, y, rgb or (0xFF shl 24))
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /** Stand-in for BitmapFactory inSampleSize: keeps every factor-th pixel, premultiplied RGBA. */
    private fun sample(png: ByteArray, plan: ReducedDecodePlan): ByteArray {
        val image = ImageIO.read(ByteArrayInputStream(png))
        val out = ByteArray(plan.width * plan.height * 4)
        for (y in 0 until plan.height) for (x in 0 until plan.width) {
            val p = image.getRGB(x * plan.factor, y * plan.factor)
            val o = (y * plan.width + x) * 4
            out[o] = (p shr 16).toByte(); out[o + 1] = (p shr 8).toByte(); out[o + 2] = p.toByte(); out[o + 3] = (p shr 24).toByte()
        }
        return out
    }

    @Test fun largePngIsReducedAndStillYieldsAChromaticAccent() {
        val png = syntheticPng(3000, 0xD02040)
        val image = ImageIO.read(ByteArrayInputStream(png))
        val plan = ReducedDecodePlan.forSourceCapped(image.width, image.height, "image/png")
        assertEquals(4, plan.factor)
        assertEquals(750, plan.width)
        assertEquals(750, plan.height)
        // Parity plan is untouched: macOS keeps full resolution.
        assertEquals(1, ReducedDecodePlan.forSource(3000, 3000, "image/png").factor)
        val accent = AccentSelector.select(AccentSampler.sample(
            CgBoxDownsampler.downsample(sample(png, plan), plan.width, plan.height)))
        assertEquals(AccentSelectionKind.chromatic, accent.kind)
    }

    @Test fun capLeavesJpegAndSmallOrModerateSourcesExactlyAsForSource() {
        for ((w, h, f) in listOf(
            Triple(3000, 3000, "image/jpeg"), Triple(1500, 700, "JPEG"), Triple(472, 472, "image/png"),
            Triple(1024, 1024, "image/webp"), Triple(1024, 300, "PNG"), Triple(10, 10, "image/png"),
        )) assertEquals("$f $w×$h", ReducedDecodePlan.forSource(w, h, f), ReducedDecodePlan.forSourceCapped(w, h, f))
    }

    @Test fun jpegThinStripsAreNeverCapped() {
        for ((w, h) in listOf(5000 to 60, 100 to 20000, 4000 to 64, 3000 to 1)) for (f in listOf("image/jpeg", "JPEG", "jpg"))
            assertEquals("$f $w×$h", ReducedDecodePlan.forSource(w, h, f), ReducedDecodePlan.forSourceCapped(w, h, f))
        assertEquals(ReducedDecodePlan(1, 5000, 60), ReducedDecodePlan.forSourceCapped(5000, 60, "image/jpeg"))
        // Same strip as PNG is capped.
        assertEquals(8, ReducedDecodePlan.forSourceCapped(5000, 60, "image/png").factor)
    }

    @Test fun capKeepsLongEdgeAtOrBelowLimitAndTheThirtyTwoFit() {
        for ((w, h) in listOf(1025 to 1025, 4000 to 3000, 20000 to 100, 100 to 20000, 5000 to 40)) {
            val plan = ReducedDecodePlan.forSourceCapped(w, h, "image/png")
            val factor = plan.factor
            assertEquals("power of two $w×$h", 0, factor and (factor - 1))
            assertEquals(maxOf(1, w / factor), plan.width)
            assertEquals(maxOf(1, h / factor), plan.height)
            val fittedShort = ((32L * minOf(w, h) + maxOf(w, h) - 1) / maxOf(w, h)).toInt()
            assertTrue("fit $w×$h", minOf(plan.width, plan.height) >= fittedShort)
            if (minOf(w, h) / 2 >= fittedShort || maxOf(w, h) <= 2048) assertTrue("edge $w×$h", maxOf(plan.width, plan.height) <= 1024)
        }
    }
}
