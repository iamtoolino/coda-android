package dev.coda.android.ui.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Geometry at density 1 (px = dp), with the mockup's 360 x 800 frame and a 308 dp keyboard. */
class LoginMotionTest {
    private val m = LoginMetrics(1f)
    // 800 dp: status bar 24, Server top 436, Sign in bottom 712 (mockup "A in every situation").
    private val rest800 = RestFrame(800f, 24f, 436f, 712f, 580f)
    private val eps = 0.01f

    @Test fun signInEnds16dpAboveTheKeyboard() {
        assertEquals(236f, m.fullLift(rest800, 308f), eps)
        assertTrue(m.fits(rest800, 308f))
        assertEquals(0f, m.fullLift(rest800, 0f), eps) // closed: nothing lifts
    }

    @Test fun lockupSizeSteps() {
        val room = 436f - 236f - 24f
        assertEquals(64f, m.lockupHeight(room), eps)
        assertEquals(48f, m.lockupHeight(100f), eps)
        assertEquals(0f, m.lockupHeight(87f), eps)
    }

    @Test fun shortPhoneFitsButHasNoRoomForTheLockup() {
        // 640 dp, 32 dp bottom gap: Server 308, Sign in bottom 584.
        val rest = RestFrame(640f, 24f, 308f, 584f, 452f)
        assertTrue(m.fits(rest, 308f))
        assertEquals(0f, m.lockupHeight(308f - m.fullLift(rest, 308f) - 24f), eps)
    }

    @Test fun overflowLiftsTheFocusedFieldOnly() {
        // 200 % font on 640 dp: Server would go under the status bar.
        val rest = RestFrame(640f, 24f, 250f, 600f, 300f)
        assertFalse(m.fits(rest, 308f))
        assertEquals(300f - 40f, m.overflowLift(rest, 308f), eps)
        assertEquals(0f, m.overflowLift(rest.copy(focusedTop = 30f), 308f), eps)
        assertEquals(m.fullLift(rest, 308f), m.overflowLift(rest.copy(focusedTop = 900f), 308f), eps)
    }

    // With the notice (overflow, 640 dp, 308 dp keyboard: keyboard top 332, room below the 24 dp status bar 308).
    private val noticeRest = RestFrame(640f, 24f, 250f, 600f, Float.POSITIVE_INFINITY, noticeTop = 320f)

    @Test fun noticeFitsWithTheFullGap() {
        // Notice + Sign in 280 of 308: 28 left, the gap stays 16 and the notice ends 12 below the status bar.
        assertEquals(16f, m.noticeGap(noticeRest, 308f), eps)
        assertEquals(m.fullLift(noticeRest, 308f), m.overflowLift(noticeRest, 308f), eps)
        assertEquals(36f, 320f - m.overflowLift(noticeRest, 308f), eps)
    }

    @Test fun insideTheBandTheGapShrinksAndTheNoticeStaysBelowTheStatusBar() {
        // Block 296: 12 left, so Sign in ends 12 above the keyboard and the notice's top sits on the status bar.
        val rest = noticeRest.copy(noticeTop = 304f)
        assertEquals(12f, m.noticeGap(rest, 308f), eps)
        val lift = m.overflowLift(rest, 308f)
        assertEquals(12f, 332f - (600f - lift), eps)
        assertEquals(24f, 304f - lift, eps)
        // The band's lower edge: exactly 8 left.
        assertEquals(8f, m.noticeGap(noticeRest.copy(noticeTop = 300f), 308f), eps)
        assertEquals(24f, 300f - m.overflowLift(noticeRest.copy(noticeTop = 300f), 308f), eps)
    }

    @Test fun belowTheMinimumTheGapStaysAt8AndTheNoticeSlidesUnder() {
        // Block 305: only 3 left; Sign in keeps 8 and the notice's top goes 5 under the status bar.
        val rest = noticeRest.copy(noticeTop = 295f)
        assertEquals(8f, m.noticeGap(rest, 308f), eps)
        val lift = m.overflowLift(rest, 308f)
        assertEquals(8f, 332f - (600f - lift), eps)
        assertEquals(19f, 295f - lift, eps)
    }

    @Test fun noticeGapScalesWithDensityAndWithoutTheNoticeNothingChanges() {
        val m2 = LoginMetrics(2f)
        val rest = RestFrame(1280f, 48f, 500f, 1200f, Float.POSITIVE_INFINITY, noticeTop = 608f) // room 616, block 592
        assertEquals(24f, m2.noticeGap(rest, 616f), eps)
        assertEquals(32f, m2.noticeGap(rest.copy(noticeTop = 650f), 616f), eps)
        assertEquals(16f, m2.noticeGap(rest.copy(noticeTop = 590f), 616f), eps)
        // No notice: the focused-field rule, with the 16 dp gap as its cap.
        assertEquals(m.fullLift(noticeRest, 308f), m.overflowLift(noticeRest.copy(noticeTop = Float.NaN), 308f), eps)
    }

    @Test fun imeProgressAndResizing() {
        assertEquals(0.5f, ImeFrame(154f, 0f, 308f).progress, eps)
        assertEquals(0.5f, ImeFrame(154f, 308f, 0f).progress, eps)
        assertEquals(1f, ImeFrame(308f, 308f, 308f).progress, eps)
        assertEquals(0f, ImeFrame(0f, 0f, 0f).progress, eps)
        assertTrue(ImeFrame(290f, 308f, 270f).resizing)
        assertFalse(ImeFrame(290f, 0f, 308f).resizing)
    }

    @Test fun lockupEndFrameMatchesTheMockup() {
        val logoH = 184f; val logoW = logoH * 40.8f / 52.4f
        val b = Brand((360 - logoW) / 2, 72f, logoW, logoH, 20f, 286f, 48f, 46f, 36f)
        val formTop = 436f - 236f
        val l = lockupAt(1f, b, 64f, 24f, formTop, 1f)
        val cy = 24f + (formTop - 24f) / 2
        // Logo: 64 dp tall, left 20, centred on cy.
        assertEquals(64f / logoH, l.logo.scale, eps)
        assertEquals(20f, b.logoX + l.logo.x, eps)
        assertEquals(cy, b.logoY + l.logo.y + 32f, eps)
        // Wordmark: 36 sp, 12 dp right of the logo, same centre line; the tagline is gone.
        assertEquals(36f / 46f, l.word.scale, eps)
        assertEquals(20f + 64f * 40.8f / 52.4f + 12f, b.wordX + l.word.x, eps)
        assertEquals(cy, b.wordY + l.word.y + 48f * l.word.scale / 2, eps)
        assertEquals(0f, l.tagline.alpha, eps)
        // Rest frame is untouched.
        val r = lockupAt(0f, b, 64f, 24f, formTop, 1f)
        assertEquals(LayerT(), r.logo); assertEquals(LayerT(), r.word); assertEquals(1f, r.tagline.alpha, eps)
    }

    @Test fun motionTableRanges() {
        val b = Brand(100f, 72f, 143f, 184f, 20f, 286f, 48f, 46f, 36f)
        // Logo finishes at 0.8, wordmark starts at 0.1, tagline gone by 0.45.
        assertEquals(lockupAt(1f, b, 64f, 24f, 200f, 1f).logo, lockupAt(.8f, b, 64f, 24f, 200f, 1f).logo)
        assertEquals(LayerT(), lockupAt(.1f, b, 64f, 24f, 200f, 1f).word)
        assertEquals(0f, lockupAt(.45f, b, 64f, 24f, 200f, 1f).tagline.alpha, eps)
        // No room: everything fades.
        val f = lockupAt(1f, b, 0f, 24f, 50f, 1f)
        assertEquals(0f, f.logo.alpha, eps); assertEquals(0f, f.word.alpha, eps); assertEquals(0f, f.tagline.alpha, eps)
    }

    /** A resize while open never drives the overflow scroll, so a scroll (or a drag) is kept. */
    @Test fun overflowScrollIsDrivenOnlyWhileOpeningOrClosing() {
        fun drive(now: Float, src: Float, tgt: Float) = overflowDriveProgress(now, src, tgt, jumping = false, jumpValue = 0f)
        // Opening 0 -> 308: driven by progress.
        assertEquals(0f, drive(0f, 0f, 308f), eps)
        assertEquals(.5f, drive(154f, 0f, 308f), eps)
        // Settled open: not driven.
        assertTrue(drive(308f, 308f, 308f).isNaN())
        // Resize 308 -> 270 (the password keyboard drops its strip): not driven at any frame, nor when it settles.
        for (now in listOf(308f, 290f, 275f, 270f)) assertTrue("resize frame $now", drive(now, 308f, 270f).isNaN())
        assertTrue(drive(270f, 270f, 270f).isNaN())
        // Resize growing back 270 -> 308: not driven either.
        assertTrue(drive(290f, 270f, 308f).isNaN())
        // Closing 270 -> 0: driven, from 1 down.
        assertEquals(1f, drive(270f, 270f, 0f), eps)
        assertEquals(.25f, drive(67.5f, 270f, 0f), eps)
        // Our own ease of a jumping keyboard drives with its own value.
        assertEquals(.4f, overflowDriveProgress(308f, 308f, 308f, jumping = true, jumpValue = .4f), eps)
    }

    /** A keyboard that reaches a new end without an inset animation is eased by the page; a running animation never is. */
    @Test fun jumpDetection() {
        assertTrue(imeJumped(animating = false, progress = 1f, shownBefore = 0f))   // shown in one frame
        assertTrue(imeJumped(animating = false, progress = 0f, shownBefore = 1f))   // hidden in one frame
        assertFalse(imeJumped(animating = true, progress = 1f, shownBefore = 0f))   // a (2-frame) system animation is followed
        assertFalse(imeJumped(animating = false, progress = 1f, shownBefore = .99f)) // the end of a real animation
        assertFalse(imeJumped(animating = false, progress = 1f, shownBefore = 1f))  // a settled resize keeps progress 1
    }

    /** The fit decision for the current keyboard height: a taller keyboard after a resize can turn a fitting form into overflow. */
    @Test fun fitIsDecidedForTheCurrentKeyboardHeight() {
        assertTrue(m.fits(436f, 712f, 800f, 24f, 308f))
        assertFalse(m.fits(436f, 712f, 800f, 24f, 520f)) // a much taller keyboard (e.g. an emoji or clipboard panel)
        assertEquals(236f, m.fullLift(712f, 800f, 308f), eps)
    }
}
