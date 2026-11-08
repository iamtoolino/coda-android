package dev.coda.android.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.luminance
import dev.coda.core.accent.*

fun ArtworkColor.composeColor() = Color(red.toFloat(), green.toFloat(), blue.toFloat())
data class AccentState(val accent: Color, val text: Color, val fill: Color, val page: Color, val container: Color, val surface: Color, val playerBackdrop: Boolean = false, val homeGlow: Float = .30f) {
    val onFill: Color get() = if (fill.luminance() > 0.46f) Color.Black.copy(alpha = 0.82f) else Color.White
    /** Page-dye middle stop, lerp(base, page, 0.55). */
    val pageMiddle: Color get() = srgbLerp(Base, page, 0.55f)

    companion object {
        /** The derived roles of [selection], without animation. */
        fun of(selection: AccentSelection, playerBackdrop: Boolean = false): AccentState {
            val roles = AccentRoles.from(selection)
            val accent = roles.accent.composeColor()
            return AccentState(accent, roles.text.composeColor(), roles.fill.composeColor(),
                srgbLerp(Color(0xFF08090A), accent, when {
                    playerBackdrop -> OneVoice.playerDye(selection)
                    else -> OneVoice.pageDye(selection)
                }),
                srgbLerp(Base, accent, 0.30f), srgbLerp(Color(0xFF15171A), accent, 0.12f), playerBackdrop, OneVoice.homeGlow(selection))
        }
    }
}

/** The app's one [AccentResolver] (owned by CodaApp); leaf composables use it for their own tile accents. */
val LocalAccentResolver = staticCompositionLocalOf<AccentResolver?> { null }

/**
 * The roles of a committed accent, cross-faded once from the previous one (M6). Only the resolver decides
 * which accent that is; this never derives or substitutes a colour of its own.
 */
@Composable
fun rememberAccentState(selection: AccentSelection, playerBackdrop: Boolean = false, animate: Boolean = true): AccentState {
    val target = remember(selection, playerBackdrop) { AccentState.of(selection, playerBackdrop) }
    // A page that has not shown its colour yet takes a new one without a tween ([animate] false).
    @Composable fun animated(color: Color): Color = animateColorAsState(color,
        if (animate) tween(450, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)) else snap(), label = "Accent").value
    return AccentState(animated(target.accent), animated(target.text), animated(target.fill),
        animated(target.page), animated(target.container), animated(target.surface), playerBackdrop, target.homeGlow)
}

/**
 * [rememberAccentState]'s cross-fade as a [State], read only where its value is read. The shell read the page accent's
 * animated colours itself, so every frame of its 450 ms fade (an album page opening, Back) recomposed the whole shell and,
 * through the shell-wide Material theme (a static composition local), every page under it.
 */
@Composable
fun rememberAccentStateOf(selection: AccentSelection): State<AccentState> {
    val target = remember(selection) { AccentState.of(selection) }
    @Composable fun animated(color: Color): State<Color> = animateColorAsState(color,
        tween(450, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)), label = "Accent")
    val accent = animated(target.accent); val text = animated(target.text); val fill = animated(target.fill)
    val page = animated(target.page); val container = animated(target.container); val surface = animated(target.surface)
    val glow = rememberUpdatedState(target.homeGlow)
    return remember(accent, text, fill, page, container, surface) {
        derivedStateOf { AccentState(accent.value, text.value, fill.value, page.value, container.value, surface.value, false, glow.value) }
    }
}

/**
 * The one place that decides the accent of a badge (rating badge, Auto/Setlist badge, resume bar): the accent of the view it is
 * shown in, as the central resolver fed the page's [AccentTheme] (the playing colour on Home, the library tabs, Search and the playlist
 * page; the album's own colour on its page). Never a colour extracted from the badge's own cover. Raw accent role (`inversePrimary`).
 */
@Composable
fun rememberViewAccent(): Color = LocalViewAccentOverride.current ?: MaterialTheme.colorScheme.inversePrimary

/**
 * A view whose accent is not its Material theme's provides it here (the artist page's Timeline covers, whose view accent is the artist
 * colour while the page itself wears the playing colour). Read only by [rememberViewAccent].
 */
val LocalViewAccentOverride = compositionLocalOf<Color?> { null }

/** The view accent's readable text role (the resume bar, "★ 4" marks): the same view accent as [rememberViewAccent]. */
@Composable
fun rememberViewAccentText(): Color = MaterialTheme.colorScheme.primary

/** A tile's own album accent (used by Home's handoff card only since PF-9a, never by badges). Null until ready and outside a session: no colour is invented meanwhile. */
@Composable
fun rememberTileAccent(coverId: String?): AccentState? {
    val resolver = LocalAccentResolver.current ?: return null
    val revision by resolver.artworkRevision.collectAsState()
    // A failed derivation yields no tint and is tried again whenever the network comes back.
    val recovered = (LocalContext.current.applicationContext as dev.coda.android.CodaApp).networkRecovered
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(recovered) { recovered.collect { attempt++ } }
    // The state lives per resolver + cover: a new cover starts at null (plain plate) instead of keeping the previous cover's accent
    // until its own derivation finishes. A revision or retry for the same cover keeps its last accent meanwhile.
    val state = remember(resolver, coverId) { mutableStateOf<AccentSelection?>(null) }
    LaunchedEffect(resolver, coverId, attempt, revision) { state.value = resolver.tileAccent(coverId) }
    val selection = state.value
    return selection?.let { remember(it) { AccentState.of(it) } }
}

/**
 * Neutral page roles, used while a page's own colour is not ready past the 150 ms cap: play button
 * #3A3D42 with a white icon, no dye, neutral text and containers. Never derived from artwork.
 */
internal val NeutralPageAccent = AccentState(
    accent = Color(0xFF3A3D42), text = Color(0xFFF1F1EE).copy(alpha = .72f), fill = Color(0xFF3A3D42), page = Color(0xFF08090A),
    container = Color(0xFF1D2024), surface = Color(0xFF15171A))

/** Every role of this accent moved [fraction] of the way towards [to] (sRGB, like the 450 ms accent tween's endpoints). */
internal fun AccentState.mix(to: AccentState, fraction: Float) = AccentState(srgbLerp(accent, to.accent, fraction),
    srgbLerp(text, to.text, fraction), srgbLerp(fill, to.fill, fraction), srgbLerp(page, to.page, fraction),
    srgbLerp(container, to.container, fraction), srgbLerp(surface, to.surface, fraction), to.playerBackdrop, to.homeGlow)

/** Spec §3.2 uses encoded sRGB channels; Compose lerp interpolates in OKLab. */
internal fun srgbLerp(start: Color, end: Color, fraction: Float): Color {
    val a = start.convert(ColorSpaces.Srgb)
    val b = end.convert(ColorSpaces.Srgb)
    fun mix(x: Float, y: Float) = x + (y - x) * fraction
    return Color(mix(a.red, b.red), mix(a.green, b.green), mix(a.blue, b.blue), mix(a.alpha, b.alpha))
}

/**
 * Fixed sign-in and re-login palette (cyan instead of Espresso): the no-artwork roles of
 * `ArtworkColor.fallback` from tokens.json `color.examples.noArtwork`. Never substitutes an artwork-derived accent.
 */
internal val LoginAccent = AccentState(
    accent = Color(0xFF2B7A82), text = Color(0xFF77C3CB), fill = Color(0xFF2B7A82), page = Color(0xFF16363A),
    container = Color(0xFF132C30), surface = Color(0xFF19272B))
