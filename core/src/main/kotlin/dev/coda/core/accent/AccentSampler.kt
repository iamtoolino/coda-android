package dev.coda.core.accent

import kotlin.math.min

/** The byte-to-pixel portion of ArtworkAccentExtraction.swift at b292fdf. */
object AccentSampler {
    const val sampleDimension = 32

    /** Contiguous 32×32 premultiplied sRGB RGBA8, in memory order (no row flip). */
    fun sample(bytes: ByteArray): List<ArtworkPixel> {
        require(bytes.size == sampleDimension * sampleDimension * 4) {
            "Expected 32×32 RGBA8 bytes"
        }
        return List(sampleDimension * sampleDimension) { index ->
            val offset = index * 4
            val alpha = (bytes[offset + 3].toInt() and 255).toDouble() / 255
            if (alpha == 0.0) {
                ArtworkPixel(0.0, 0.0, 0.0, 0.0)
            } else {
                fun channel(offset: Int) = min(1.0, (bytes[offset].toInt() and 255).toDouble() / 255 / alpha)
                ArtworkPixel(channel(offset), channel(offset + 1), channel(offset + 2), alpha)
            }
        }
    }
}
