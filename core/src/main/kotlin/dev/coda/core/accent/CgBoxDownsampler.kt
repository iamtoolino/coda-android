package dev.coda.core.accent

import kotlin.math.ceil
import kotlin.math.floor

/** Native memory-row, premultiplied sRGB RGBA8 → 32×32 premultiplied RGBA8. */
object CgBoxDownsampler {
    fun downsample(rgba: ByteArray, width: Int, height: Int): ByteArray {
        require(width > 0 && height > 0 && width.toLong() * height * 4 == rgba.size.toLong()) {
            "Expected contiguous RGBA pixels with positive dimensions"
        }
        // CG shrinks before enlarging; with two enlarging axes V precedes H.
        return if (width < 32) {
            pass(pass(rgba, width, height, false), width, 32, true)
        } else {
            pass(pass(rgba, width, height, true), 32, height, false)
        }
    }

    private fun pass(src: ByteArray, width: Int, height: Int, horizontal: Boolean): ByteArray {
        val major = if (horizontal) width else height
        val minor = if (horizontal) height else width
        val out = ByteArray(minor * 32 * 4)
        // CG enlargement phases match a Float forward scale inverted in Double.
        // Keeping major/32 exact instead changes phase ties at 12/20/28 pixels.
        val scale = if (major < 32) 1.0 / (32.0 / major).toFloat().toDouble() else major / 32.0
        for (row in 0 until minor) {
            fun at(p: Int, c: Int): Int {
                val clamped = p.coerceIn(0, major - 1)
                val pixel = if (horizontal) row * width + clamped else clamped * width + row
                return src[pixel * 4 + c].toInt() and 255
            }
            for (x in 0 until 32) {
                val left = x * scale
                val right = (x + 1) * scale
                val center = (x + 0.5) * scale - 0.5
                val base = floor(center).toInt()
                val fraction = center - base
                val step = floor(fraction * 8 + 0.5).toInt()
                for (c in 0 until 4) {
                    val value = if (major < 32) {
                        // CG's dyadic interpolation is deliberately not a linear blend:
                        // truncate each divided channel separately, then add/subtract.
                        val divisor = when (step) {
                            1, 7 -> 16
                            2, 6 -> 8
                            3, 5 -> 4
                            4 -> 2
                            else -> 0
                        }
                        val a = at(base, c)
                        val b = at(base + 1, c)
                        if (fraction < 0.5) {
                            if (divisor == 0) a else a - a / divisor + b / divisor
                        } else {
                            if (divisor == 0) b else b - b / divisor + a / divisor
                        }
                    } else {
                        var sum = 0.0
                        for (p in floor(left).toInt() until minOf(major, ceil(right).toInt())) {
                            val weight = minOf(right, p + 1.0) - maxOf(left, p.toDouble())
                            sum += at(p, c) * weight
                        }
                        floor(sum / scale + 0.5).toInt().coerceIn(0, 255)
                    }
                    val pixel = if (horizontal) row * 32 + x else x * minor + row
                    out[pixel * 4 + c] = value.toByte()
                }
            }
        }
        return out
    }
}
