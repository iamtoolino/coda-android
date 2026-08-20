package io.github.iamtoolino.coda.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class CodaAccentColor(
    val red: Double,
    val green: Double,
    val blue: Double,
)

internal object CodaAccentExtractor {
    const val ALGORITHM_VERSION = 1

    private const val SAMPLE_SIZE = 32
    private const val BUCKET_COUNT = 18
    private const val MIN_VALUE = 0.12
    private const val MAX_VALUE = 0.94
    private const val MIN_SATURATION = 0.16
    private const val PREFERRED_VALUE = 0.58
    private const val WEIGHT_BASE = 0.45
    private const val OUTPUT_VALUE_FLOOR = 0.50

    val GENERIC_FALLBACK = CodaAccentColor(
        red = 43 / 255.0,
        green = 122 / 255.0,
        blue = 130 / 255.0,
    )
    val MONOCHROME_FALLBACK = CodaAccentColor(red = 0.56, green = 0.58, blue = 0.60)

    private data class Bucket(
        var score: Double = 0.0,
        var red: Double = 0.0,
        var green: Double = 0.0,
        var blue: Double = 0.0,
        var weight: Double = 0.0,
    )

    fun extractOrFallback(source: Bitmap?): CodaAccentColor {
        if (source == null) return GENERIC_FALLBACK
        return runCatching {
            val sample = renderToSrgb32(source)
            try {
                val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
                sample.getPixels(
                    pixels,
                    0,
                    SAMPLE_SIZE,
                    0,
                    0,
                    SAMPLE_SIZE,
                    SAMPLE_SIZE,
                )
                extractFromArgbPixels(pixels)
            } finally {
                sample.recycle()
            }
        }.getOrDefault(GENERIC_FALLBACK)
    }

    internal fun extractFromArgbPixels(pixels: IntArray): CodaAccentColor {
        require(pixels.size == SAMPLE_SIZE * SAMPLE_SIZE)

        val buckets = Array(BUCKET_COUNT) { Bucket() }
        for (pixel in pixels) {
            val red8 = pixel shr 16 and 0xFF
            val green8 = pixel shr 8 and 0xFF
            val blue8 = pixel and 0xFF

            val red = red8 / 255.0
            val green = green8 / 255.0
            val blue = blue8 / 255.0

            val maximum = max(red, max(green, blue))
            val minimum = min(red, min(green, blue))
            val value = maximum
            val saturation =
                if (maximum == 0.0) 0.0 else (maximum - minimum) / maximum

            if (
                value <= MIN_VALUE ||
                value >= MAX_VALUE ||
                saturation <= MIN_SATURATION
            ) {
                continue
            }

            val delta = maximum - minimum
            val hueSector = when (maximum) {
                red -> (green - blue) / delta
                green -> (blue - red) / delta + 2.0
                else -> (red - green) / delta + 4.0
            }
            val hue = (hueSector / 6.0 + 1.0) % 1.0
            val bucketIndex = min((hue * BUCKET_COUNT).toInt(), BUCKET_COUNT - 1)
            val middleBrightness = 1.0 - abs(value - PREFERRED_VALUE)
            val weight = saturation * saturation * (WEIGHT_BASE + middleBrightness)

            buckets[bucketIndex].apply {
                score += weight
                this.red += red * weight
                this.green += green * weight
                this.blue += blue * weight
                this.weight += weight
            }
        }

        val winner = buckets.maxByOrNull { it.score }
        if (winner == null || winner.weight <= 0.0) {
            return MONOCHROME_FALLBACK
        }

        val red = winner.red / winner.weight
        val green = winner.green / winner.weight
        val blue = winner.blue / winner.weight
        val lift = max(0.0, OUTPUT_VALUE_FLOOR - max(red, max(green, blue)))

        return CodaAccentColor(
            red = min(red + lift, 1.0),
            green = min(green + lift, 1.0),
            blue = min(blue + lift, 1.0),
        )
    }

    private fun renderToSrgb32(source: Bitmap): Bitmap {
        val target = Bitmap.createBitmap(
            SAMPLE_SIZE,
            SAMPLE_SIZE,
            Bitmap.Config.ARGB_8888,
            true,
            ColorSpace.get(ColorSpace.Named.SRGB),
        )
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        Canvas(target).drawBitmap(
            source,
            null,
            Rect(0, 0, SAMPLE_SIZE, SAMPLE_SIZE),
            paint,
        )
        return target
    }
}
