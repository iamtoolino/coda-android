package dev.coda.core.accent

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Artist page background colours (studio A, `ds11-artist-stage.html`). The artist page carries no accent: its
 * page colour is the artist photo's own bottom edge pushed to near-black ("Photo-matched"), with the photo's years
 * (AR-3 Artist-photo).
 */
object ArtistBackdrop {
    /** OKLab lightness and chroma cap of the Photo-matched page colour. */
    const val PHOTO_LIGHTNESS = .17
    const val PHOTO_CHROMA = .03
    /** The visible bottom share of the banner that counts as the photo's "bottom edge". */
    const val EDGE_BAND = 1.0 / 6.0

    /** `base` (#090B0D), where both page gradients end. */
    val Base = ArtworkColor(0x09 / 255.0, 0x0B / 255.0, 0x0D / 255.0)
    /** No photo (or no usable pixels): graphite #15171A. */
    val Graphite = ArtworkColor(0x15 / 255.0, 0x17 / 255.0, 0x1A / 255.0)

    /** Photo-matched page colour: [edge]'s hue at OKLab L .17 with chroma ≤ .03; graphite without an edge colour. */
    fun photoMatched(edge: ArtworkColor?): ArtworkColor =
        edge?.let { AccentRoles.tone(it, PHOTO_LIGHTNESS, PHOTO_CHROMA) } ?: Graphite

    /**
     * The rows of an [imageWidth]×[imageHeight] image that a [viewWidth]×[viewHeight] view shows with a centre-crop fill
     * and vertical alignment [biasY] (-1 top … 1 bottom), as `[first, last)`.
     */
    fun visibleRows(imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int, biasY: Float): IntRange {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return 0 until imageHeight.coerceAtLeast(0)
        val scale = maxOf(viewWidth.toDouble() / imageWidth, viewHeight.toDouble() / imageHeight)
        val shown = (viewHeight / scale).coerceAtMost(imageHeight.toDouble())
        val top = (imageHeight - shown) * (1 + biasY.coerceIn(-1f, 1f)) / 2
        val first = top.roundToInt().coerceIn(0, imageHeight - 1)
        return first until (top + shown).roundToInt().coerceIn(first + 1, imageHeight)
    }

    /**
     * The photo's bottom-edge colour: the mean in OKLab of the bottom [EDGE_BAND] of [rows] in [argb] (row-major,
     * [width] wide). A perceptual mean keeps a few bright figures from washing out the ground's hue (a linear-light
     * mean is dominated by highlights). Pixels under 50 % alpha are skipped; null when none are left.
     */
    fun edgeColor(argb: IntArray, width: Int, height: Int, rows: IntRange = 0 until height): ArtworkColor? {
        if (width <= 0 || height <= 0 || argb.size < width * height || rows.isEmpty()) return null
        val last = minOf(rows.last, height - 1)
        val first = maxOf(rows.first, last - ((rows.last - rows.first + 1) * EDGE_BAND).roundToInt().coerceAtLeast(1) + 1, 0)
        var sl = 0.0; var sa = 0.0; var sb = 0.0; var n = 0
        for (y in first..last) for (x in 0 until width) {
            val p = argb[y * width + x]
            if ((p ushr 24) < 128) continue
            val (l, a, b) = oklab(ArtworkColor((p shr 16 and 0xFF) / 255.0, (p shr 8 and 0xFF) / 255.0, (p and 0xFF) / 255.0))
            sl += l; sa += a; sb += b; n++
        }
        if (n == 0) return null
        return fromOklab(sl / n, sa / n, sb / n)
    }

    /** [photoMatched] of the [edgeColor] of the banner's visible window. */
    fun photoPage(argb: IntArray, width: Int, height: Int, viewWidth: Int, viewHeight: Int, biasY: Float): ArtworkColor =
        photoMatched(edgeColor(argb, width, height, visibleRows(width, height, viewWidth, viewHeight, biasY)))

    /**
     * The columns of the same centre-crop fill (horizontally centred), as `[first, last)`. A photo wider than the
     * banner loses its sides.
     */
    fun visibleColumns(imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int): IntRange {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return 0 until imageWidth.coerceAtLeast(0)
        val scale = maxOf(viewWidth.toDouble() / imageWidth, viewHeight.toDouble() / imageHeight)
        val shown = (viewWidth / scale).coerceAtMost(imageWidth.toDouble())
        val left = (imageWidth - shown) / 2
        val first = left.roundToInt().coerceIn(0, imageWidth - 1)
        return first until (left + shown).roundToInt().coerceIn(first + 1, imageWidth)
    }

    /**
     * Artist-photo years: the accent of the whole visible banner window of [argb] (row-major, [width] wide,
     * non-premultiplied ARGB) with the cover accent algorithm (CG box downsample to 32×32, [AccentSampler],
     * [AccentSelector]), not the bottom-edge mean, which is near-grey by design.
     */
    fun photoAccent(argb: IntArray, width: Int, height: Int, viewWidth: Int, viewHeight: Int, biasY: Float): AccentSelection {
        val rows = visibleRows(width, height, viewWidth, viewHeight, biasY)
        val columns = visibleColumns(width, height, viewWidth, viewHeight)
        val w = columns.last - columns.first + 1; val h = rows.last - rows.first + 1
        val rgba = ByteArray(w * h * 4)
        var o = 0
        for (y in rows) for (x in columns) {
            val p = argb[y * width + x]
            val alpha = p ushr 24
            // The sampler expects premultiplied RGBA8, as the CG bitmap context the cover path mirrors.
            fun premultiplied(channel: Int) = ((channel * alpha + 127) / 255).toByte()
            rgba[o] = premultiplied(p shr 16 and 0xFF); rgba[o + 1] = premultiplied(p shr 8 and 0xFF)
            rgba[o + 2] = premultiplied(p and 0xFF); rgba[o + 3] = alpha.toByte(); o += 4
        }
        return AccentSelector.select(AccentSampler.sample(CgBoxDownsampler.downsample(rgba, w, h)))
    }

    /**
     * Artist-photo year colour (drawn at [YEAR_ALPHA]): the photo accent's text role, the same mapping as album
     * accents for every kind (no floor, no neutral special case; coordinator 2026-10-29). The one place to change if
     * Re-decides how grey accents map. Null when the photo has no usable pixels: the page then uses grey years.
     */
    fun photoYears(selection: AccentSelection): ArtworkColor? =
        if (selection.kind == AccentSelectionKind.brand) null else AccentRoles.from(selection).text

    /** Alpha of the Timeline years (DS-15 "Slight accent": the accent text tone at 72 %). */
    const val YEAR_ALPHA = .72

    /** OKLab lightness and chroma of [color] (tests and diagnostics). */
    fun lightnessChroma(color: ArtworkColor): Pair<Double, Double> = oklab(color).let { (l, a, b) -> l to kotlin.math.hypot(a, b) }

    private fun oklab(color: ArtworkColor): Triple<Double, Double, Double> {
        val r = linear(color.red); val g = linear(color.green); val b = linear(color.blue)
        val l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return Triple(0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s)
    }

    /** OKLab → encoded sRGB, clamped (the mean of in-gamut colours can sit just outside the gamut). */
    private fun fromOklab(lightness: Double, a: Double, b: Double): ArtworkColor {
        val l = (lightness + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m = (lightness - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s = (lightness - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        fun channel(v: Double) = encoded(v.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
        return ArtworkColor(channel(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
            channel(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
            channel(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s))
    }

    private fun linear(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun encoded(c: Double) = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055
}
