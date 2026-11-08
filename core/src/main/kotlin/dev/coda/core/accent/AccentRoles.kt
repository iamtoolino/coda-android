package dev.coda.core.accent

import kotlin.math.*

/** Design spec §3.2. This changes presentation roles, never the extracted accent. */
data class AccentRoles(val accent: ArtworkColor, val text: ArtworkColor, val fill: ArtworkColor) {
    companion object {
        fun from(selection: AccentSelection): AccentRoles {
            val color = if (selection.kind == AccentSelectionKind.brand) ArtworkColor.fallback else selection.color
            val neutral = selection.kind in listOf(AccentSelectionKind.neutral, AccentSelectionKind.tintedNeutral)
            return AccentRoles(color, tone(color, 0.77, 0.12), if (neutral) tone(color, 0.88, 0.10) else color)
        }
        /** [color] at OKLab [lightness] with its hue kept and chroma capped at [cap] (and to the sRGB gamut). */
        internal fun tone(color: ArtworkColor, lightness: Double, cap: Double): ArtworkColor {
            fun linear(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            val r = linear(color.red); val g = linear(color.green); val b = linear(color.blue)
            val l = cbrt(0.4122214708*r + 0.5363325363*g + 0.0514459929*b)
            val m = cbrt(0.2119034982*r + 0.6806995451*g + 0.1073969566*b)
            val s = cbrt(0.0883024619*r + 0.2817188376*g + 0.6299787005*b)
            val a = 1.9779984951*l - 2.4285922050*m + 0.4505937099*s
            val bb = 0.0259040371*l + 0.7827717662*m - 0.8086757660*s
            val hue = atan2(bb, a); val chroma = min(hypot(a, bb), cap)
            fun encoded(c: Double) = if (c <= 0.0031308) c*12.92 else 1.055*c.pow(1.0/2.4)-0.055
            fun candidate(c: Double): ArtworkColor {
                val aa = cos(hue)*c; val ab = sin(hue)*c
                val ll = (lightness + 0.3963377774*aa + 0.2158037573*ab).pow(3)
                val mm = (lightness - 0.1055613458*aa - 0.0638541728*ab).pow(3)
                val ss = (lightness - 0.0894841775*aa - 1.2914855480*ab).pow(3)
                return ArtworkColor(encoded(4.0767416621*ll - 3.3077115913*mm + 0.2309699292*ss),
                    encoded(-1.2684380046*ll + 2.6097574011*mm - 0.3413193965*ss),
                    encoded(-0.0041960863*ll - 0.7034186147*mm + 1.7076147010*ss))
            }
            var low = 0.0; var high = chroma; var result = candidate(0.0)
            repeat(18) {
                val c = (low+high)/2; val next = candidate(c)
                if (next.red in 0.0..1.0 && next.green in 0.0..1.0 && next.blue in 0.0..1.0) { low=c; result=next } else high=c
            }
            return result
        }
    }
}
