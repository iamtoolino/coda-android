package dev.coda.core.accent

import kotlin.math.*

/** 1:1 port of ArtworkAccentSelector.swift, coda-macos b292fdf. */
object AccentSelector {
    private data class PerceptualPixel(
        val lightness: Double, val a: Double, val b: Double, val chroma: Double,
        val hue: Double, val alpha: Double, val visibility: Double,
    )

    private val brandSelection = AccentSelection(ArtworkColor.fallback, AccentSelectionKind.brand)

    fun select(sourcePixels: List<ArtworkPixel>): AccentSelection {
        val pixels = sourcePixels.mapNotNull(::perceptualPixel)
        if (pixels.isEmpty()) return brandSelection
        val visibleArea = pixels.fold(0.0) { sum, p -> sum + p.alpha * p.visibility }
        if (visibleArea <= 0) return brandSelection
        val chromaticCoverage = pixels.fold(0.0) { sum, p ->
            sum + if (p.chroma >= 0.035) p.alpha * p.visibility else 0.0
        } / visibleArea
        val binCount = 36
        val histogram = DoubleArray(binCount)
        for (p in pixels) {
            if (p.chroma >= 0.025 && p.visibility > 0) {
                val index = min((p.hue / 360 * binCount.toDouble()).toInt(), binCount - 1)
                histogram[index] += p.alpha * p.visibility * min(p.chroma / 0.12, 1.0)
            }
        }
        val kernel = doubleArrayOf(0.25, 0.60, 1.0, 0.60, 0.25)
        val smoothed = DoubleArray(binCount) { index ->
            kernel.indices.fold(0.0) { sum, k ->
                sum + histogram[(index + k - 2 + binCount) % binCount] * kernel[k]
            }
        }
        // Swift max(by:) retains the first maximum when values tie.
        var winningIndex = 0
        for (index in 1 until binCount) {
            if (smoothed[winningIndex] < smoothed[index]) winningIndex = index
        }
        val winningCenter = (winningIndex.toDouble() + 0.5) * 360 / binCount.toDouble()
        val familyPixels = pixels.filter { it.chroma >= 0.025 && circularDistance(it.hue, winningCenter) <= 30 }
        val familySupport = familyPixels.fold(0.0) { sum, p -> sum + p.alpha * p.visibility } / visibleArea
        if (chromaticCoverage >= 0.12 && familySupport >= 0.12 && familyPixels.isNotEmpty()) {
            val weights = familyPixels.map { it.alpha * it.visibility * min(it.chroma / 0.12, 1.0) }
            val weighted = familyPixels.zip(weights)
            val x = weighted.fold(0.0) { sum, (p, w) -> sum + cos(p.hue * PI / 180) * w }
            val y = weighted.fold(0.0) { sum, (p, w) -> sum + sin(p.hue * PI / 180) * w }
            var hue = atan2(y, x) * 180 / PI
            if (hue < 0) hue += 360
            val lightness = weightedQuantile(weighted.map { (p, w) -> p.lightness to w }, 0.50)
            val chroma = weightedQuantile(weighted.map { (p, w) -> p.chroma to w }, 0.60)
            val color = convertedColor(lightness.coerceIn(0.48, 0.62), chroma.coerceIn(0.07, 0.16), hue)
                ?: return brandSelection
            return AccentSelection(color, AccentSelectionKind.chromatic)
        }
        val weights = pixels.map { it.alpha * max(it.visibility, 0.15) }
        val weightTotal = weights.fold(0.0) { sum, w -> sum + w }
        if (weightTotal <= 0) return brandSelection
        val weighted = pixels.zip(weights)
        val lightness = weightedQuantile(weighted.map { (p, w) -> p.lightness to w }, 0.50)
        val averageA = weighted.fold(0.0) { sum, (p, w) -> sum + p.a * w } / weightTotal
        val averageB = weighted.fold(0.0) { sum, (p, w) -> sum + p.b * w } / weightTotal
        val averageChroma = hypot(averageA, averageB)
        var hue = atan2(averageB, averageA) * 180 / PI
        if (hue < 0) hue += 360
        val isTinted = averageChroma >= 0.012 || chromaticCoverage >= 0.08
        val color = convertedColor(lightness.coerceIn(0.48, 0.62), min(averageChroma, if (isTinted) 0.045 else 0.020), hue)
            ?: return brandSelection
        return AccentSelection(color, if (isTinted) AccentSelectionKind.tintedNeutral else AccentSelectionKind.neutral)
    }

    private fun perceptualPixel(pixel: ArtworkPixel): PerceptualPixel? {
        if (pixel.alpha <= 0.01) return null
        val red = linearized(pixel.red)
        val green = linearized(pixel.green)
        val blue = linearized(pixel.blue)
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
        var hue = atan2(b, a) * 180 / PI
        if (hue < 0) hue += 360
        val visibility = smoothstep(0.05, 0.18, lightness) * (1 - smoothstep(0.88, 0.98, lightness))
        return PerceptualPixel(lightness, a, b, chroma, hue, pixel.alpha, visibility)
    }

    private fun weightedQuantile(values: List<Pair<Double, Double>>, quantile: Double): Double {
        // sortedBy is stable, preserving Swift's order for equal values.
        val ordered = values.filter { it.second > 0 }.sortedBy { it.first }
        if (ordered.isEmpty()) return 0.0
        val target = ordered.fold(0.0) { sum, item -> sum + item.second } * quantile.coerceIn(0.0, 1.0)
        var cumulative = 0.0
        for ((value, weight) in ordered) {
            cumulative += weight
            if (cumulative >= target) return value
        }
        return ordered.last().first
    }

    private fun convertedColor(lightness: Double, chroma: Double, hue: Double): ArtworkColor? {
        val radians = hue * PI / 180
        fun components(chromaScale: Double): ArtworkColor {
            val a = cos(radians) * chroma * chromaScale
            val b = sin(radians) * chroma * chromaScale
            val lRoot = lightness + 0.3963377774 * a + 0.2158037573 * b
            val mRoot = lightness - 0.1055613458 * a - 0.0638541728 * b
            val sRoot = lightness - 0.0894841775 * a - 1.2914855480 * b
            val l = lRoot * lRoot * lRoot
            val m = mRoot * mRoot * mRoot
            val s = sRoot * sRoot * sRoot
            return ArtworkColor(
                encoded(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
                encoded(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
                encoded(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s),
            )
        }
        var low = 0.0
        var high = 1.0
        var result = components(0.0)
        repeat(18) {
            val scale = (low + high) / 2
            val candidate = components(scale)
            if (candidate.red in 0.0..1.0 && candidate.green in 0.0..1.0 && candidate.blue in 0.0..1.0) {
                result = candidate
                low = scale
            } else {
                high = scale
            }
        }
        if (!result.red.isFinite() || !result.green.isFinite() || !result.blue.isFinite()) return null
        return ArtworkColor(result.red.coerceIn(0.0, 1.0), result.green.coerceIn(0.0, 1.0), result.blue.coerceIn(0.0, 1.0))
    }

    private fun smoothstep(lower: Double, upper: Double, value: Double): Double {
        val unit = ((value - lower) / (upper - lower)).coerceIn(0.0, 1.0)
        return unit * unit * (3 - 2 * unit)
    }

    private fun circularDistance(lhs: Double, rhs: Double): Double {
        val direct = abs(lhs - rhs) % 360
        return min(direct, 360 - direct)
    }

    private fun linearized(component: Double): Double =
        if (component <= 0.04045) component / 12.92 else ((component + 0.055) / 1.055).pow(2.4)

    private fun encoded(component: Double): Double =
        if (component <= 0.0031308) 12.92 * component else 1.055 * component.pow(1 / 2.4) - 0.055
}
