package dev.coda.android.playback

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/** Mp4EditScanner on replayed Mp4Extractor reads. Mp4EditScannerDeviceTest runs Media3's real extractor. */
@UnstableApi
class Mp4EditScannerTest {
    /** Replays the reads Mp4Extractor makes: every box header, and one payload read per box it parses. */
    private fun scan(bytes: ByteArray, scanner: Mp4EditScanner = Mp4EditScanner(), stopAt: Int = Int.MAX_VALUE): Mp4EditScanner {
        val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "edts")
        val leaves = setOf("ftyp", "mvhd", "tkhd", "mdhd", "hdlr", "stsd", "stts", "stsc", "stsz", "stco", "elst", "udta")
        fun walk(start: Int, end: Int) {
            var position = start
            while (position + 8 <= end && position < stopAt) {
                var size = ByteBuffer.wrap(bytes, position, 4).int.toLong()
                var header = 8
                val type = String(bytes, position + 4, 4, Charsets.ISO_8859_1)
                scanner.onRead(position.toLong(), bytes.copyOfRange(position, position + 8), 0, 8)
                if (size == 1L) {
                    scanner.onRead(position + 8L, bytes.copyOfRange(position + 8, position + 16), 0, 8)
                    size = ByteBuffer.wrap(bytes, position + 8, 8).long; header = 16
                }
                val next = position + size.toInt()
                if (type in containers) walk(position + header, next)
                else if (type in leaves) scanner.onRead(position + header.toLong(), bytes.copyOfRange(position + header, next), 0, next - position - header)
                position = next
            }
        }
        walk(0, bytes.size)
        return scanner
    }

    private fun fixture(name: String) = File("src/androidTest/assets/gapless/$name").readBytes()

    @Test fun ffmpegEditListsGiveTheExactLengthWhateverTheMovieTimescale() {
        // FFmpeg 8+: movie timescale = sample rate, the edit is exact.
        assertEquals((1024L to 91_877L), scan(fixture("m4a-1.m4a")).edit().at(44_100))
        // FFmpeg < 8: edit 2083 ms (91,860 frames) and Media3 padding 17; the shortened final stts sample is exact.
        assertEquals((1024L to 91_877L), scan(fixture("m4ams-1.m4a")).edit().at(44_100))
        assertEquals((1024L to 88_211L), scan(fixture("m4ams-2.m4a")).edit().at(44_100))
    }

    @Test fun itunSmpbWithoutAnEditListIsLeftToMedia3() {
        assertNull(scan(fixture("m4aapple-1.m4a")).edit().at(44_100))
    }

    /** Sol review P2: a restart before the moov was complete must not keep the partial scan's tracks. */
    @Test fun aRestartBeforeTheMoovEndsStartsTheScanOver() {
        val bytes = fixture("m4ams-1.m4a")
        val scanner = scan(bytes, stopAt = String(bytes, Charsets.ISO_8859_1).indexOf("udta"))
        scanner.restart() // Mp4EditScanner.wrap(extractor).seek(0, 0)
        assertEquals((1024L to 91_877L), scan(bytes, scanner).edit().at(44_100))
        scanner.restart() // After publication: nothing to forget.
        assertEquals((1024L to 91_877L), scanner.edit().at(44_100))
    }

    /** Sol review P2: a trak with a 64-bit size ends after its full header, not at the size sentinel 1. */
    @Test fun anExtendedSizeTrakKeepsItsBoxes() {
        val bytes = fixture("m4ams-1.m4a")
        val text = String(bytes, Charsets.ISO_8859_1)
        val trak = text.indexOf("trak") - 4
        val moov = text.indexOf("moov") - 4
        val size = ByteBuffer.wrap(bytes, trak, 4).int
        val out = ByteBuffer.allocate(bytes.size + 8)
        out.put(bytes, 0, trak).putInt(1).put("trak".toByteArray()).putLong(size + 8L).put(bytes, trak + 8, bytes.size - trak - 8)
        val extended = out.array()
        ByteBuffer.wrap(extended).putInt(moov, ByteBuffer.wrap(bytes).getInt(moov) + 8)
        assertTrue("faststart fixture: moov precedes mdat", moov < text.indexOf("mdat"))
        assertEquals((1024L to 91_877L), scan(extended).edit().at(44_100))
    }

    @Test fun theSttsEndWinsOnlyWithinOneMovieTick() {
        // QA server "Gapless AAC" (Debian FFmpeg 7.1): 441,001 frames, edit 10,000 ms.
        assertEquals(441_001L, Mp4EditScanner.audibleLength(1000, 44_100, 10_000, 1024, 1024 + 441_001L, true))
        assertEquals(352_807L, Mp4EditScanner.audibleLength(1000, 44_100, 8_000, 1024, 1024 + 352_807L, true))
        // Apple AAC remuxed with an exact edit: the full last sample's padding is real, the edit decides.
        assertEquals(91_877L, Mp4EditScanner.audibleLength(44_100, 44_100, 91_877, 2112, 94_208, false))
        // A rounded edit with a full last sample: the stts end is far beyond the edit, so the edit decides.
        assertEquals(91_860L, Mp4EditScanner.audibleLength(1000, 44_100, 2083, 1024, 94_208, false))
        // Sol review P2: real padding below one tick in a full final sample (stts end 92,160, edit end 92,124.9).
        assertEquals(92_124L, Mp4EditScanner.audibleLength(1000, 44_100, 2089, 1024, 93_184, false))
        // QuickTime's 600 movie timescale: one tick is 73.5 frames.
        assertEquals(91_877L, Mp4EditScanner.audibleLength(600, 44_100, 1250, 2112, 2112 + 91_877L, true))
        assertNull(Mp4EditScanner.audibleLength(1000, 44_100, Long.MAX_VALUE, 1024, 0, true))
    }

    @Test fun aHandlerOutsideTheAudioTrackIsIgnored() {
        val scanner = scan(fixture("m4ams-1.m4a"))
        // A later moov/meta hdlr ("mdir") must not turn the sound track into a non-sound track.
        val position = fixture("m4ams-1.m4a").size.toLong()
        scanner.onRead(position, byteArrayOf(0, 0, 0, 33, 'h'.code.toByte(), 'd'.code.toByte(), 'l'.code.toByte(), 'r'.code.toByte()), 0, 8)
        scanner.onRead(position + 8, ByteArray(25).also { "mdir".toByteArray().copyInto(it, 8) }, 0, 25)
        assertEquals((1024L to 91_877L), scanner.edit().at(44_100))
    }

    @Test fun aTruncatedPayloadIsDroppedNotThrownIntoTheLoader() {
        val scanner = Mp4EditScanner()
        fun header(size: Int, type: String) = ByteBuffer.allocate(8).putInt(size).put(type.toByteArray()).array()
        scanner.onRead(0, header(100, "trak"), 0, 8)
        scanner.onRead(8, header(16, "stts"), 0, 8)
        scanner.onRead(16, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 9), 0, 8) // Nine entries announced, none present.
        assertNull(scanner.edit().at(44_100))
    }

    /**
     * Sol review r2 P2: HE-AAC decodes to twice the container's 22.05 kHz. An edit list in a 44.1 kHz track timescale
     * with an odd length must convert straight to the output rate, not via core samples (91,877 -> 45,938 -> 91,876).
     */
    @Test fun anOddHeAacEditLengthConvertsStraightToTheOutputRate() {
        val edit = Mp4EditFrames(mediaTime = 4224, timescale = 44_100, sttsTotal = 98_304, editDuration = 91_877,
            movieTimescale = 44_100, sttsEnd = false)
        assertEquals(2112L, edit.delayAt(22_050)) // Media3's core-rate delay, which the sink matches against.
        assertEquals(4224L to 91_877L, edit.at(44_100))
    }

    /**
     * Sol review r4 P2: a movie timescale finer than the track's (44.1 vs 22.05 kHz). The edit duration stays in
     * movie units: 91,877 output frames, not 45,938 track units x 2 = 91,876. The m4aheedit fixture, edited in memory.
     */
    @Test fun aMovieTimescaleFinerThanTheTrackKeepsTheExactEdit() {
        val bytes = fixture("m4aheedit-1.m4a").copyOf()
        val text = String(bytes, Charsets.ISO_8859_1)
        val buffer = ByteBuffer.wrap(bytes)
        val mdhd = text.indexOf("mdhd"); val stts = text.indexOf("stts"); val elst = text.indexOf("elst")
        assertEquals(44_100, buffer.getInt(mdhd + 16)); buffer.putInt(mdhd + 16, 22_050)
        assertEquals(2048, buffer.getInt(stts + 16)); buffer.putInt(stts + 16, 1024)
        assertEquals(4224, buffer.getInt(elst + 16)); buffer.putInt(elst + 16, 2112)
        val edit = scan(bytes).edit()!!
        assertEquals(2112L, edit.delayAt(22_050))
        assertEquals(4224L to 91_877L, edit.at(44_100))
    }

    /** Sonnet review: an odd media time. The sink matches delayAt(container rate) against Media3's delay, which
     *  BoxParser computes with the same function (scaleLargeTimestamp(media time - first timestamp, rate, timescale)). */
    @Test fun anOddMediaTimeMatchesMedia3sDelayRounding() {
        val edit = Mp4EditFrames(mediaTime = 4225, timescale = 44_100, sttsTotal = 98_304, editDuration = 91_877,
            movieTimescale = 44_100, sttsEnd = false)
        assertEquals(androidx.media3.common.util.Util.scaleLargeTimestamp(4225, 22_050, 44_100), edit.delayAt(22_050))
        assertEquals(2112L, edit.delayAt(22_050))
        assertEquals(4225L, edit.delayAt(44_100))
    }

    private fun Mp4EditFrames?.at(rate: Int) = this?.let { it.delayAt(rate) to it.audibleAt(rate) }
}
