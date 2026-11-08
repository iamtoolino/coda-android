package dev.coda.core.accent

import org.junit.Assert.*
import org.junit.Test

/** The Photo-matched page colour (photo bottom edge → near-black, low chroma) and the Light tint; AR-3 photo years. */
class ArtistBackdropTest {
    private fun argb(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun hex(c: ArtworkColor) = "#%02X%02X%02X".format((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
    private fun assertNearBlack(c: ArtworkColor) {
        val (l, chroma) = ArtistBackdrop.lightnessChroma(c)
        assertEquals("OKLab L of ${hex(c)}", .17, l, .004)
        assertTrue("chroma $chroma of ${hex(c)} ≤ .03", chroma <= .03 + 1e-3)
        assertTrue("near-black ${hex(c)}", maxOf(c.red, c.green, c.blue) < .2)
    }

    /** A [w]×[h] image: [top] colour above row [split], [bottom] colour from it on. */
    private fun twoBand(w: Int, h: Int, split: Int, top: Int, bottom: Int) = IntArray(w * h) { if (it / w < split) top else bottom }

    @Test fun saturatedEdgesBecomeNearBlackWithTheirHue() {
        for (edge in listOf(argb(0xC0, 0x20, 0x10), argb(0x20, 0x90, 0x30), argb(0x18, 0x21, 0x2E), argb(0xF0, 0xF0, 0xF0), argb(0, 0, 0))) {
            val color = ArtistBackdrop.photoPage(IntArray(16 * 16) { edge }, 16, 16, 16, 16, 0f)
            assertNearBlack(color)
        }
        // Red stays warm, blue stays cool: the hue is kept, only lightness and chroma move.
        val red = ArtistBackdrop.photoMatched(ArtworkColor(.75, .12, .06))
        assertTrue(red.red > red.blue)
        val blue = ArtistBackdrop.photoMatched(ArtworkColor(.09, .13, .3))
        assertTrue(blue.blue > blue.red)
        // A neutral edge gives a neutral page.
        val grey = ArtistBackdrop.photoMatched(ArtworkColor(.5, .5, .5))
        assertEquals(0.0, ArtistBackdrop.lightnessChroma(grey).second, .002)
    }

    @Test fun onlyTheVisibleBottomEdgeCounts() {
        // Light top (sky), dark-red bottom band: the page follows the bottom, not the average.
        val pixels = twoBand(10, 60, 50, argb(0xE0, 0xF0, 0xFF), argb(0x60, 0x10, 0x08))
        val edge = ArtistBackdrop.edgeColor(pixels, 10, 60)!!
        assertEquals(0x60 / 255.0, edge.red, .01)
        assertEquals(0x10 / 255.0, edge.green, .01)
        // A square photo in a wide banner is cropped; with the top bias the window ends above the image bottom.
        val rows = ArtistBackdrop.visibleRows(100, 100, 400, 300, -.33f)
        assertEquals(75, rows.last - rows.first + 1)
        assertTrue("window ends above the bottom: $rows", rows.last < 99)
        val cropped = twoBand(100, 100, 90, argb(0x30, 0x50, 0x90), argb(0xFF, 0xFF, 0x00))
        val page = ArtistBackdrop.photoPage(cropped, 100, 100, 400, 300, -.33f)
        assertNearBlack(page)
        assertTrue("the hidden yellow bottom is ignored: ${hex(page)}", page.blue > page.red)
        // Centre bias, a tall image in a wide view: the middle rows.
        assertEquals(20 until 30, ArtistBackdrop.visibleRows(40, 50, 80, 20, 0f))
        assertEquals(0 until 50, ArtistBackdrop.visibleRows(50, 50, 50, 50, 0f))
    }

    @Test fun brightFiguresDoNotWashOutTheGround() {
        // 45 % white-ish figures over a storm-blue ground (the mockup's N1): the page stays cool.
        val w = 100
        val pixels = IntArray(w * 30) { if (it % w < 45) argb(0xE9, 0xE6, 0xE1) else argb(0x14, 0x1C, 0x27) }
        val page = ArtistBackdrop.photoPage(pixels, w, 30, w, 30, 0f)
        assertNearBlack(page)
        assertTrue("cool page ${hex(page)}", page.blue > page.red)
    }

    @Test fun fallbackIsGraphite() {
        assertEquals(ArtistBackdrop.Graphite, ArtistBackdrop.photoMatched(null))
        // Fully transparent or empty images have no edge colour.
        assertNull(ArtistBackdrop.edgeColor(IntArray(4 * 4) { argb(255, 0, 0, a = 0) }, 4, 4))
        assertNull(ArtistBackdrop.edgeColor(IntArray(0), 0, 0))
        assertEquals(ArtistBackdrop.Graphite, ArtistBackdrop.photoPage(IntArray(4 * 4) { 0 }, 4, 4, 4, 4, 0f))
        assertEquals("#15171A", hex(ArtistBackdrop.Graphite))
    }

    /** The cover path on the same pixels: RGBA8 (opaque here, so premultiplied = straight) → CG box → sampler → selector. */
    private fun coverAccent(argb: IntArray, w: Int, h: Int): AccentSelection {
        val rgba = ByteArray(w * h * 4)
        argb.forEachIndexed { i, p ->
            rgba[i * 4] = (p shr 16 and 0xFF).toByte(); rgba[i * 4 + 1] = (p shr 8 and 0xFF).toByte()
            rgba[i * 4 + 2] = (p and 0xFF).toByte(); rgba[i * 4 + 3] = (p ushr 24).toByte()
        }
        return AccentSelector.select(AccentSampler.sample(CgBoxDownsampler.downsample(rgba, w, h)))
    }

    /** An uncropped photo gets exactly the cover algorithm's accent. */
    @Test fun photoAccentIsTheCoverAlgorithmOnTheVisiblePhoto() {
        val w = 64; val h = 48
        val pixels = IntArray(w * h) { i -> val x = i % w; val y = i / w
            if ((x / 8 + y / 8) % 3 == 0) argb(200, 120, 40) else argb(30 + x, 40 + y, 60) }
        assertEquals(coverAccent(pixels, w, h), ArtistBackdrop.photoAccent(pixels, w, h, w * 4, h * 4, -.33f))
    }

    /** Only the banner's visible window counts, rows and columns. */
    @Test fun photoAccentSeesOnlyTheVisibleWindow() {
        val red = argb(210, 40, 40); val blue = argb(40, 60, 210)
        // Tall photo, top-aligned short banner: the hidden blue bottom half doesn't count.
        val tall = twoBand(32, 128, 64, red, blue)
        val top = ArtistBackdrop.photoAccent(tall, 32, 128, 320, 100, -1f)
        assertEquals(AccentSelectionKind.chromatic, top.kind)
        assertTrue("red window gives a red accent: ${hex(top.color)}", top.color.red > top.color.blue)
        // Wide photo: blue sides are cropped away by the centre crop.
        val wide = IntArray(128 * 32) { if (it % 128 in 48 until 80) red else blue }
        assertEquals(48 until 80, ArtistBackdrop.visibleColumns(128, 32, 100, 100))
        val centre = ArtistBackdrop.photoAccent(wide, 128, 32, 100, 100, 0f)
        assertTrue("red centre: ${hex(centre.color)}", centre.color.red > centre.color.blue)
        assertEquals(0 until 32, ArtistBackdrop.visibleColumns(32, 128, 320, 100))
    }

    /**
     * Year colour: the accent's text role for every kind, the same as album accents (no floor, no neutral special
     * case; coordinator 2026-10-29). A near-grey photo gives silver; no usable pixels give no colour (grey years).
     */
    @Test fun photoYearsAreTheTextRole() {
        val warm = ArtistBackdrop.photoAccent(IntArray(32 * 32) { argb(190, 110, 50) }, 32, 32, 32, 32, 0f)
        assertEquals(AccentSelectionKind.chromatic, warm.kind)
        assertEquals(AccentRoles.from(warm).text, ArtistBackdrop.photoYears(warm))
        assertEquals(.77, ArtistBackdrop.lightnessChroma(ArtistBackdrop.photoYears(warm)!!).first, .005)
        val grey = ArtistBackdrop.photoAccent(IntArray(32 * 32) { if (it % 2 == 0) argb(120, 121, 124) else argb(90, 90, 92) }, 32, 32, 32, 32, 0f)
        assertTrue(grey.kind == AccentSelectionKind.neutral || grey.kind == AccentSelectionKind.tintedNeutral)
        val silver = ArtistBackdrop.photoYears(grey)!!
        assertEquals(AccentRoles.from(grey).text, silver)
        val (lightness, chroma) = ArtistBackdrop.lightnessChroma(silver)
        assertEquals(.77, lightness, .005)
        assertTrue("near-grey photo keeps its low chroma ($chroma): no floor", chroma < .02)
        assertNull(ArtistBackdrop.photoYears(ArtistBackdrop.photoAccent(IntArray(16) { 0 }, 4, 4, 4, 4, 0f)))
    }
}
