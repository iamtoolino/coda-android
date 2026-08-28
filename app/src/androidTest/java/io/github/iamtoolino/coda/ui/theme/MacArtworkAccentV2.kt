package io.github.iamtoolino.coda.ui.theme

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Test-only Android port of Coda macOS ArtworkAccentSelector V2. */
internal object MacArtworkAccentV2 {
    private data class Pixel(
        val lightness: Double,
        val a: Double,
        val b: Double,
        val chroma: Double,
        val hue: Double,
        val alpha: Double,
        val visibility: Double,
    )

    fun extract(bitmap: Bitmap): Int {
        val sample = Bitmap.createScaledBitmap(bitmap, SAMPLE_SIZE, SAMPLE_SIZE, true)
        return try {
            val colors = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
            sample.getPixels(colors, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
            select(colors)
        } finally {
            if (sample !== bitmap) sample.recycle()
        }
    }

    internal fun select(colors: IntArray): Int {
        val pixels = colors.asSequence().mapNotNull(::perceptualPixel).toList()
        if (pixels.isEmpty()) return BRAND

        val visibleArea = pixels.sumOf { it.alpha * it.visibility }
        if (visibleArea <= 0.0) return BRAND
        val chromaticCoverage = pixels.sumOf {
            if (it.chroma >= 0.035) it.alpha * it.visibility else 0.0
        } / visibleArea

        val histogram = DoubleArray(BIN_COUNT)
        pixels.forEach { pixel ->
            if (pixel.chroma >= 0.025 && pixel.visibility > 0.0) {
                val index = min((pixel.hue / 360.0 * BIN_COUNT).toInt(), BIN_COUNT - 1)
                histogram[index] += pixel.alpha * pixel.visibility * min(pixel.chroma / 0.12, 1.0)
            }
        }
        val kernel = doubleArrayOf(0.25, 0.60, 1.0, 0.60, 0.25)
        val smoothed = DoubleArray(BIN_COUNT) { index ->
            kernel.indices.sumOf { kernelIndex ->
                val wrapped = (index + kernelIndex - 2 + BIN_COUNT) % BIN_COUNT
                histogram[wrapped] * kernel[kernelIndex]
            }
        }
        val winningIndex = smoothed.indices.maxByOrNull(smoothed::get) ?: 0
        val winningCenter = (winningIndex + 0.5) * 360.0 / BIN_COUNT
        val family = pixels.filter {
            it.chroma >= 0.025 && circularDistance(it.hue, winningCenter) <= 30.0
        }
        val familySupport = family.sumOf { it.alpha * it.visibility } / visibleArea

        if (chromaticCoverage >= 0.12 && familySupport >= 0.12 && family.isNotEmpty()) {
            val weights = family.map { it.alpha * it.visibility * min(it.chroma / 0.12, 1.0) }
            val x = family.indices.sumOf { cos(family[it].hue * PI / 180.0) * weights[it] }
            val y = family.indices.sumOf { sin(family[it].hue * PI / 180.0) * weights[it] }
            var hue = atan2(y, x) * 180.0 / PI
            if (hue < 0.0) hue += 360.0
            val lightness = weightedQuantile(
                family.indices.map { family[it].lightness to weights[it] },
                0.50,
            )
            val chroma = weightedQuantile(
                family.indices.map { family[it].chroma to weights[it] },
                0.60,
            )
            return convertedColor(
                lightness.coerceIn(0.48, 0.62),
                chroma.coerceIn(0.07, 0.16),
                hue,
            ) ?: BRAND
        }

        val weights = pixels.map { it.alpha * maxOf(it.visibility, 0.15) }
        val weightTotal = weights.sum()
        if (weightTotal <= 0.0) return BRAND
        val lightness = weightedQuantile(
            pixels.indices.map { pixels[it].lightness to weights[it] },
            0.50,
        )
        val averageA = pixels.indices.sumOf { pixels[it].a * weights[it] } / weightTotal
        val averageB = pixels.indices.sumOf { pixels[it].b * weights[it] } / weightTotal
        val averageChroma = hypot(averageA, averageB)
        var hue = atan2(averageB, averageA) * 180.0 / PI
        if (hue < 0.0) hue += 360.0
        val isTinted = averageChroma >= 0.012 || chromaticCoverage >= 0.08
        return convertedColor(
            lightness.coerceIn(0.48, 0.62),
            min(averageChroma, if (isTinted) 0.045 else 0.020),
            hue,
        ) ?: BRAND
    }

    private fun perceptualPixel(color: Int): Pixel? {
        val alpha = Color.alpha(color) / 255.0
        if (alpha <= 0.01) return null
        val red = linearized(Color.red(color) / 255.0)
        val green = linearized(Color.green(color) / 255.0)
        val blue = linearized(Color.blue(color) / 255.0)
        val l = 0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue
        val m = 0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue
        val s = 0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue
        val lRoot = cbrt(l)
        val mRoot = cbrt(m)
        val sRoot = cbrt(s)
        val lightness = 0.2104542553 * lRoot + 0.7936177850 * mRoot - 0.0040720468 * sRoot
        val a = 1.9779984951 * lRoot - 2.4285922050 * mRoot + 0.4505937099 * sRoot
        val b = 0.0259040371 * lRoot + 0.7827717662 * mRoot - 0.8086757660 * sRoot
        val chroma = hypot(a, b)
        var hue = atan2(b, a) * 180.0 / PI
        if (hue < 0.0) hue += 360.0
        val visibility = smoothstep(0.05, 0.18, lightness) *
            (1.0 - smoothstep(0.88, 0.98, lightness))
        return Pixel(lightness, a, b, chroma, hue, alpha, visibility)
    }

    private fun weightedQuantile(values: List<Pair<Double, Double>>, quantile: Double): Double {
        val ordered = values.filter { it.second > 0.0 }.sortedBy(Pair<Double, Double>::first)
        val last = ordered.lastOrNull() ?: return 0.0
        val target = ordered.sumOf(Pair<Double, Double>::second) * quantile.coerceIn(0.0, 1.0)
        var cumulative = 0.0
        ordered.forEach { item ->
            cumulative += item.second
            if (cumulative >= target) return item.first
        }
        return last.first
    }

    private fun convertedColor(lightness: Double, chroma: Double, hue: Double): Int? {
        val radians = hue * PI / 180.0
        fun components(scale: Double): Triple<Double, Double, Double> {
            val a = cos(radians) * chroma * scale
            val b = sin(radians) * chroma * scale
            val lRoot = lightness + 0.3963377774 * a + 0.2158037573 * b
            val mRoot = lightness - 0.1055613458 * a - 0.0638541728 * b
            val sRoot = lightness - 0.0894841775 * a - 1.2914855480 * b
            val l = lRoot * lRoot * lRoot
            val m = mRoot * mRoot * mRoot
            val s = sRoot * sRoot * sRoot
            return Triple(
                encoded(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
                encoded(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
                encoded(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s),
            )
        }

        var low = 0.0
        var high = 1.0
        var result = components(0.0)
        repeat(18) {
            val candidate = components((low + high) / 2.0)
            if (candidate.toList().all { it in 0.0..1.0 }) {
                result = candidate
                low = (low + high) / 2.0
            } else {
                high = (low + high) / 2.0
            }
        }
        if (result.toList().any { !it.isFinite() }) return null
        return Color.rgb(
            (result.first.coerceIn(0.0, 1.0) * 255).roundToInt(),
            (result.second.coerceIn(0.0, 1.0) * 255).roundToInt(),
            (result.third.coerceIn(0.0, 1.0) * 255).roundToInt(),
        )
    }

    private fun smoothstep(lower: Double, upper: Double, value: Double): Double {
        val unit = ((value - lower) / (upper - lower)).coerceIn(0.0, 1.0)
        return unit * unit * (3.0 - 2.0 * unit)
    }

    private fun circularDistance(left: Double, right: Double): Double {
        val direct = abs(left - right) % 360.0
        return min(direct, 360.0 - direct)
    }

    private fun linearized(component: Double): Double = if (component <= 0.04045) {
        component / 12.92
    } else {
        ((component + 0.055) / 1.055).pow(2.4)
    }

    private fun encoded(component: Double): Double = if (component <= 0.0031308) {
        12.92 * component
    } else {
        1.055 * component.pow(1.0 / 2.4) - 0.055
    }

    private const val SAMPLE_SIZE = 32
    private const val BIN_COUNT = 36
    private val BRAND = Color.rgb(43, 122, 130)
}
