package dev.coda.core.accent

import kotlin.math.*

/** Presentation values from One Voice shared/coda.js; extraction stays unchanged. */
object OneVoice {
    fun pageDye(selection: AccentSelection) = if (selection.neutral) .26f else .24f
    fun playerDye(selection: AccentSelection) = if (selection.neutral) .30f else .28f
    fun homeGlow(selection: AccentSelection) = if (selection.neutral) .24f else .30f
    const val HomeArtwork = .12f
    const val PlayerGlow = .14f
    const val PageFadeDp = 640f

    /** Both colours must have OKLCh chroma > .04; grey covers never trigger the courtesy guard. */
    fun dockTint(playback: AccentSelection, viewed: AccentSelection?): Float {
        if (viewed == null || viewed.color == playback.color) return .12f
        val (pc, ph) = chromaHue(playback.color)
        val (vc, vh) = chromaHue(viewed.color)
        val distance = abs(ph - vh).let { min(it, 360 - it) }
        return if (pc > .04 && vc > .04 && distance > 45) .05f else .12f
    }

    private val AccentSelection.neutral get() = kind == AccentSelectionKind.neutral || kind == AccentSelectionKind.tintedNeutral
    private fun chromaHue(c: ArtworkColor): Pair<Double, Double> {
        fun linear(v: Double) = if (v <= .04045) v / 12.92 else ((v + .055) / 1.055).pow(2.4)
        val r = linear(c.red); val g = linear(c.green); val b = linear(c.blue)
        val l = cbrt(.4122214708*r + .5363325363*g + .0514459929*b)
        val m = cbrt(.2119034982*r + .6806995451*g + .1073969566*b)
        val s = cbrt(.0883024619*r + .2817188376*g + .6299787005*b)
        val a = 1.9779984951*l - 2.4285922050*m + .4505937099*s
        val bb = .0259040371*l + .7827717662*m - .8086757660*s
        return hypot(a, bb) to atan2(bb, a) * 180 / PI
    }
}
