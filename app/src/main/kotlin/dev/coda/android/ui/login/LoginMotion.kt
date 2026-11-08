package dev.coda.android.ui.login

import androidx.compose.animation.core.CubicBezierEasing
import kotlin.math.abs
import kotlin.math.max

/**
 * "Lockup" (direction A): the sign-in page follows the keyboard's own inset
 * animation. Everything here is plain geometry in pixels so it can be unit-tested; [LoginScreen] reads the measured
 * rest frame and the keyboard insets and applies the results as graphicsLayer transforms (no relayout per frame).
 * The per-frame path uses the scalar functions and [LockupOut] (no allocation per frame); the data classes are
 * conveniences for tests and per-change bookkeeping.
 */
internal val LoginStandard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

internal fun seg(p: Float, from: Float, to: Float): Float = ((p - from) / (to - from)).coerceIn(0f, 1f)
internal fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** 0 closed .. 1 open: the keyboard's own travel ([now] between the animation's ends; settled: [now] itself). */
internal fun imeProgress(now: Float, source: Float, target: Float): Float {
    val full = max(source, target)
    return if (full <= 0f) 0f else (now / full).coerceIn(0f, 1f)
}

/** Both ends open: the keyboard changes height while open (e.g. the password keyboard drops its strip). */
internal fun imeResizing(source: Float, target: Float): Boolean = source > 0f && target > 0f && source != target

/** A keyboard that reached a new end without an inset animation: the page eases it itself (250 ms). */
internal fun imeJumped(animating: Boolean, progress: Float, shownBefore: Float): Boolean =
    !animating && abs(progress - shownBefore) > .5f

/**
 * Overflow layout: the scroll is driven from progress only while the keyboard opens or closes (a system animation
 * that is not a resize, or our own ease of a jump). A resize, or the settled state, never drives it, so a drag or the
 * current position is kept. Returns NaN when nothing should be driven.
 */
internal fun overflowDriveProgress(now: Float, source: Float, target: Float, jumping: Boolean, jumpValue: Float): Float = when {
    jumping -> jumpValue
    source != target && !imeResizing(source, target) -> imeProgress(now, source, target)
    else -> Float.NaN
}

/** Where the keyboard is: [now] is this frame's height, [source]/[target] the running animation's ends (px). */
internal data class ImeFrame(val now: Float, val source: Float, val target: Float) {
    val full: Float get() = max(source, target)
    val progress: Float get() = imeProgress(now, source, target)
    val resizing: Boolean get() = imeResizing(source, target)
    val animating: Boolean get() = source != target
}

/**
 * The rest frame, in window coordinates (px). [focusedTop] is the focused field's top, or Password's when nothing is
 * focused. [noticeTop] is the notice's top while it shows (NaN otherwise).
 */
internal data class RestFrame(
    val windowHeight: Float, val topInset: Float, val serverTop: Float, val signInBottom: Float, val focusedTop: Float,
    val noticeTop: Float = Float.NaN,
)

internal class LoginMetrics(val dp: Float) {
    val signInGap = 16 * dp // Sign in ends 16 dp above the keyboard
    val minSignInGap = 8 * dp // With the notice the gap gives way down to this before the notice's top does
    val topMargin = 16 * dp // the form never rises closer than this to the status bar

    /** How far the form lifts so Sign in ends [gap] above a keyboard of [keyboard] px. */
    fun fullLift(signInBottom: Float, windowHeight: Float, keyboard: Float, gap: Float = signInGap): Float =
        max(0f, signInBottom + gap - (windowHeight - keyboard))
    fun fullLift(rest: RestFrame, keyboard: Float): Float = fullLift(rest.signInBottom, rest.windowHeight, keyboard)

    /** The whole form plus Sign in fits above the keyboard below the status bar. */
    fun fits(serverTop: Float, signInBottom: Float, windowHeight: Float, topInset: Float, keyboard: Float): Boolean =
        serverTop - fullLift(signInBottom, windowHeight, keyboard) >= topInset + topMargin
    fun fits(rest: RestFrame, keyboard: Float): Boolean =
        fits(rest.serverTop, rest.signInBottom, rest.windowHeight, rest.topInset, keyboard)

    /**
     * With the notice, the gap above Sign in is what the room between the status
     * bar and the keyboard leaves after the notice and Sign in, kept between [minSignInGap] and [signInGap]. Only below
     * the minimum does the notice's top slide under the status bar.
     */
    fun noticeGap(rest: RestFrame, keyboard: Float): Float {
        val room = rest.windowHeight - keyboard - rest.topInset
        val block = rest.signInBottom - rest.noticeTop
        return (room - block).coerceIn(minSignInGap, signInGap)
    }

    /**
     * Doesn't fit: lift only so the focused field sits [topMargin] below the status bar (the user can drag for more).
     * With the notice: Sign in [noticeGap] above the keyboard, so the notice and Sign in show together.
     */
    fun overflowLift(rest: RestFrame, keyboard: Float): Float =
        if (!rest.noticeTop.isNaN()) fullLift(rest.signInBottom, rest.windowHeight, keyboard, noticeGap(rest, keyboard))
        else (rest.focusedTop - (rest.topInset + topMargin)).coerceIn(0f, fullLift(rest, keyboard))

    /** Space between the status bar and the lifted form: 64 dp lockup, 48 dp when tight (200 % font), 0 = brand fades. */
    fun lockupHeight(room: Float): Float = when {
        room >= 120 * dp -> 64 * dp
        room >= 88 * dp -> 48 * dp
        else -> 0f
    }
}

/** A graphicsLayer transform with origin (0, 0); mutable so a frame reuses it. */
internal class LayerOut { var x = 0f; var y = 0f; var scale = 1f; var alpha = 1f
    fun set(x: Float, y: Float, scale: Float, alpha: Float) { this.x = x; this.y = y; this.scale = scale; this.alpha = alpha }
}
internal class LockupOut { val logo = LayerOut(); val word = LayerOut(); val tagline = LayerOut() }

/**
 * Direction A at progress [p], written into [out]: the logo (rest box [logoX]/[logoY]/[logoW]/[logoH], window px)
 * shrinks to [lockupH] and docks 20 dp from the left (progress 0–0.8), the wordmark (rest line box at [wordX]/[wordY],
 * [wordH] tall, laid out at [wordFontPx]) shrinks to [lockupFontPx] scaled by lockupH/64 dp, 12 dp right of the logo on
 * the same centre line (0.1–1), the tagline moves with the wordmark and fades (0–0.45). The lockup is centred between
 * [topInset] and [formTop]. Without room ([lockupH] 0) the brand rises a little and fades out instead.
 */
internal fun lockupInto(
    out: LockupOut, p: Float,
    logoX: Float, logoY: Float, logoW: Float, logoH: Float,
    wordX: Float, wordY: Float, wordH: Float, wordFontPx: Float, lockupFontPx: Float,
    lockupH: Float, topInset: Float, formTop: Float, dp: Float,
) {
    if (lockupH <= 0f || logoH <= 0f) {
        val s = lerp(1f, .7f, p)
        val o = 1f - seg(p, 0f, .4f)
        // Scale about the logo's centre, expressed with a (0, 0) origin.
        out.logo.set((1 - s) * logoW / 2, (1 - s) * logoH / 2 - 48 * dp * p, s, 1f - seg(p, 0f, .5f))
        out.word.set(0f, -32 * dp * p, 1f, o)
        out.tagline.set(0f, -32 * dp * p, 1f, o)
        return
    }
    val cy = topInset + (formTop - topInset) / 2
    val pl = seg(p, 0f, .8f)
    val pw = seg(p, .1f, 1f)
    out.logo.set(lerp(0f, 20 * dp - logoX, pl), lerp(0f, cy - lockupH / 2 - logoY, pl), lerp(1f, lockupH / logoH, pl), 1f)
    val wordS1 = (lockupFontPx * lockupH / (64 * dp)) / wordFontPx
    val wx = 20 * dp + lockupH * (40.8f / 52.4f) + 12 * dp
    val wy = cy - wordH * wordS1 / 2
    val dx = lerp(0f, wx - wordX, pw)
    val dy = lerp(0f, wy - wordY, pw)
    out.word.set(dx, dy, lerp(1f, wordS1, pw), 1f)
    out.tagline.set(dx, dy, 1f, 1f - seg(p, 0f, .45f))
}

// Test conveniences (allocate; not used per frame).
internal data class LayerT(val x: Float = 0f, val y: Float = 0f, val scale: Float = 1f, val alpha: Float = 1f)
internal data class Brand(
    val logoX: Float, val logoY: Float, val logoW: Float, val logoH: Float,
    val wordX: Float, val wordY: Float, val wordH: Float, val wordFontPx: Float, val lockupFontPx: Float,
)
internal class Lockup(val logo: LayerT, val word: LayerT, val tagline: LayerT)

internal fun lockupAt(p: Float, b: Brand, lockupH: Float, topInset: Float, formTop: Float, dp: Float): Lockup {
    val out = LockupOut()
    lockupInto(out, p, b.logoX, b.logoY, b.logoW, b.logoH, b.wordX, b.wordY, b.wordH, b.wordFontPx, b.lockupFontPx,
        lockupH, topInset, formTop, dp)
    fun LayerOut.t() = LayerT(x, y, scale, alpha)
    return Lockup(out.logo.t(), out.word.t(), out.tagline.t())
}
