package dev.coda.core.accent

/** ImageIO's measured JPEG reduction policy for a 32×32 accent sample. */
data class ReducedDecodePlan(val factor: Int, val width: Int, val height: Int) {
    companion object {
        /** [format] is a format name or MIME type; non-JPEG images retain full resolution. */
        fun forSource(width: Int, height: Int, format: String): ReducedDecodePlan {
            require(width > 0 && height > 0) { "Source dimensions must be positive" }
            val jpeg = isJpeg(format)
            val short = minOf(width, height)
            val long = maxOf(width, height)
            // CG first fits the source aspect ratio inside 32×32, rounding the
            // fitted short axis up. JPEG reduction must still supply that axis.
            // Compute the ceiling with Long arithmetic (dimensions may be Int.MAX_VALUE).
            val fittedShort = ((32L * short + long - 1) / long).toInt()
            val factor = if (jpeg && short > 64) {
                listOf(8, 4, 2).firstOrNull { short / it >= fittedShort } ?: 1
            } else 1
            return ReducedDecodePlan(factor, maxOf(1, width / factor), maxOf(1, height / factor))
        }

        /**
         * Android-only plan: [forSource], plus a source-edge cap for non-JPEG images.
         *
         * Deliberate divergence from macOS (* FX-12): macOS decodes PNG and other non-JPEG covers at full resolution, which
         * for a 3000 px PNG means a ~36 MB RGBA array and a possible OutOfMemoryError on
         * Android. Here a non-JPEG whose long edge exceeds [maxEdge] is decoded with a
         * power-of-two sample size (BitmapFactory inSampleSize) until the long edge is
         * <= [maxEdge] (extreme thin strips such as 20000x3 may keep a longer edge: the 32x32 fit wins; memory stays tiny), never reducing the short axis below what the 32×32 fit needs.
         * JPEG plans and every non-JPEG with long edge <= [maxEdge] equal [forSource]
         * exactly, so all parity fixtures are unaffected.
         */
        fun forSourceCapped(width: Int, height: Int, format: String, maxEdge: Int = MAX_NON_JPEG_EDGE): ReducedDecodePlan {
            require(maxEdge >= 32) { "maxEdge must be at least 32" }
            val base = forSource(width, height, format)
            // JPEG parity is explicit: forSource may yield factor 1 for a JPEG with short edge <= 64
            // (e.g. 5000x60), which must NOT be capped.
            if (isJpeg(format) || base.factor != 1) return base
            val long = maxOf(width, height)
            if (long <= maxEdge) return base
            val short = minOf(width, height)
            val fittedShort = ((32L * short + long - 1) / long).toInt()
            var factor = 1
            while (long / (factor * 2) >= 1 && long / factor > maxEdge && short / (factor * 2) >= fittedShort) factor *= 2
            return ReducedDecodePlan(factor, maxOf(1, width / factor), maxOf(1, height / factor))
        }

        private fun isJpeg(format: String): Boolean =
            format.equals("JPEG", true) || format.equals("JPG", true) || format.equals("image/jpeg", true)

        /** Longest source edge kept for non-JPEG accent decodes: 1024² RGBA is 4 MB. */
        const val MAX_NON_JPEG_EDGE = 1024
    }
}
