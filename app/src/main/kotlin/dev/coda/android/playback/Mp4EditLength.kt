package dev.coda.android.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.PositionHolder
import kotlin.math.abs

/**
 * An edit-list M4A audio track's delay (the edit's media time, in the track timescale) and its audible length: the
 * stts end in track units when that is the exact end, otherwise the edit duration in movie units. Each converts
 * directly to any rate (HE-AAC decodes to twice the container rate; a movie timescale can be finer).
 */
internal data class Mp4EditFrames(
    val mediaTime: Long, val timescale: Long,
    val sttsTotal: Long, val editDuration: Long, val movieTimescale: Long, val sttsEnd: Boolean,
) {
    fun delayAt(rate: Int): Long = Util.scaleLargeTimestamp(mediaTime, rate.toLong(), timescale)
    fun audibleAt(rate: Int): Long =
        if (sttsEnd) Util.scaleLargeTimestamp(sttsTotal - mediaTime, rate.toLong(), timescale)
        else Util.scaleLargeTimestamp(editDuration, rate.toLong(), movieTimescale)
}

/**
 * The exact audible length of an edit-list M4A (AAC) track.
 *
 * Media3 turns a one-entry edit list into encoder delay and padding, with padding = stts total − edit end. FFmpeg
 * before 8 writes the edit duration in a 1/1000 s movie timescale, rounded down, and marks the true end by
 * shortening the last stts sample instead. Media3's padding is then only that rounding residue (a few frames),
 * while the decoder still outputs the whole last 1024-frame access unit: up to 1023 frames of encoder padding
 * were played at every transition (Navidrome QA "Gapless AAC": +342 and +466 frames).
 *
 * The boxes are read as Mp4Extractor consumes them: it reads every box header (8 bytes, plus 8 for a 64-bit size)
 * and then, in one read, the payload of each box it parses, which includes mvhd, hdlr, mdhd, elst and stts.
 * A restart of the extraction before the moov was complete (Media3 retries an unseekable source from 0) starts over.
 */
@UnstableApi
internal class Mp4EditScanner {
    private class Trak(val end: Long) {
        var timescale = 0L
        var sound = false
        var edits: List<LongArray>? = null // [segment duration (movie timescale), media time (track timescale)]
        var sttsTotal = -1L
        var sttsFirstDelta = 0L
        var sttsLastDelta = 0L
    }

    private var movieTimescale = 0L
    private val traks = mutableListOf<Trak>()
    private var boxPosition = -1L
    private var boxType = 0
    private var boxSize = 0L
    private var boxHeader = 0
    private var finished = false

    /** [extractor] with its input observed until the sound track's format is published (see [edit]). */
    fun wrap(extractor: Extractor): Extractor = object : Extractor by extractor {
        override fun read(input: ExtractorInput, seekPosition: PositionHolder) = extractor.read(wrap(input), seekPosition)
        override fun seek(position: Long, timeUs: Long) {
            restart()
            extractor.seek(position, timeUs)
        }
    }

    /** Forgets a partial scan; the moov is read again from its start. A finished scan was already published. */
    internal fun restart() {
        if (finished) return
        movieTimescale = 0; traks.clear(); boxPosition = -1
    }

    private fun wrap(input: ExtractorInput): ExtractorInput = if (finished) input else object : ExtractorInput by input {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val position = input.position
            return input.read(buffer, offset, length).also { if (it > 0) onRead(position, buffer, offset, it) }
        }
        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            val position = input.position
            return input.readFully(target, offset, length, allowEndOfInput).also { if (it) onRead(position, target, offset, length) }
        }
        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            val position = input.position
            input.readFully(target, offset, length)
            onRead(position, target, offset, length)
        }
    }

    internal fun onRead(position: Long, data: ByteArray, offset: Int, length: Int) {
        if (boxPosition >= 0 && position == boxPosition + boxHeader) {
            if (boxSize == 1L && boxHeader == 8 && length == 8) {
                boxSize = long(data, offset); boxHeader = 16
                if (boxType == TRAK) traks += Trak(boxPosition + boxSize)
                return
            }
            if (length.toLong() == boxSize - boxHeader) {
                runCatching { payload(boxType, boxPosition, data, offset) } // A malformed box only loses the correction.
                boxPosition = -1
                return
            }
        }
        if (length == 8) {
            boxPosition = position; boxSize = int(data, offset).toLong() and 0xFFFFFFFFL
            boxType = int(data, offset + 4); boxHeader = 8
            if (boxType == TRAK && boxSize != 1L) traks += Trak(position + boxSize) // A 64-bit size follows otherwise.
        } else boxPosition = -1
    }

    private fun payload(type: Int, position: Long, data: ByteArray, offset: Int) {
        val version = data[offset].toInt()
        val trak = traks.lastOrNull()?.takeIf { position < it.end } // Not a later moov/meta hdlr.
        when (type) {
            MVHD -> movieTimescale = uint(data, offset + if (version == 1) 20 else 12)
            MDHD -> trak?.timescale = uint(data, offset + if (version == 1) 20 else 12)
            HDLR -> trak?.sound = int(data, offset + 8) == SOUN
            ELST -> trak?.edits = (0 until uint(data, offset + 4).toInt()).map { i ->
                if (version == 1) longArrayOf(long(data, offset + 8 + i * 20), long(data, offset + 16 + i * 20))
                else longArrayOf(uint(data, offset + 8 + i * 12), int(data, offset + 12 + i * 12).toLong())
            }
            STTS -> if (trak != null) {
                val entries = (0 until uint(data, offset + 4).toInt()).map { i -> uint(data, offset + 8 + i * 8) to uint(data, offset + 12 + i * 8) }
                trak.sttsTotal = entries.sumOf { (count, delta) -> count * delta }
                trak.sttsFirstDelta = entries.firstOrNull()?.second ?: 0
                trak.sttsLastDelta = entries.lastOrNull()?.second ?: 0
            }
        }
    }

    /** Null unless the file has exactly one sound track with a one-entry edit list and a sample table. */
    fun edit(): Mp4EditFrames? {
        finished = true // Called with the track's format, after the whole moov was read.
        val trak = traks.singleOrNull { it.sound } ?: return null
        val edit = trak.edits?.singleOrNull() ?: return null
        if (trak.timescale <= 0 || movieTimescale <= 0 || trak.sttsTotal < 0 || edit[1] < 0) return null
        val length = audibleLength(movieTimescale, trak.timescale, edit[0], edit[1], trak.sttsTotal,
            shortenedFinalSample = trak.sttsLastDelta < trak.sttsFirstDelta) ?: return null
        return Mp4EditFrames(edit[1], trak.timescale, trak.sttsTotal, edit[0], movieTimescale,
            sttsEnd = length == trak.sttsTotal - edit[1] && trak.sttsLastDelta < trak.sttsFirstDelta)
    }

    companion object {
        private fun fourcc(s: String) = s.fold(0) { acc, c -> (acc shl 8) or c.code }
        private val TRAK = fourcc("trak"); private val MVHD = fourcc("mvhd"); private val MDHD = fourcc("mdhd")
        private val HDLR = fourcc("hdlr"); private val ELST = fourcc("elst"); private val STTS = fourcc("stts")
        private val SOUN = fourcc("soun")
        private fun int(d: ByteArray, o: Int) = ((d[o].toInt() and 255) shl 24) or ((d[o + 1].toInt() and 255) shl 16) or
            ((d[o + 2].toInt() and 255) shl 8) or (d[o + 3].toInt() and 255)
        private fun uint(d: ByteArray, o: Int) = int(d, o).toLong() and 0xFFFFFFFFL
        private fun long(d: ByteArray, o: Int) = (int(d, o).toLong() shl 32) or uint(d, o + 4)

        /**
         * Audible length after the edit's start, in track timescale units. The edit duration is exact only to one
         * movie timescale tick. When the muxer shortened the final sample (its stts delta is below the nominal one)
         * and that stts end (stts total − media time) lies within the tick, it is the exact end the muxer recorded.
         * Otherwise (a full final sample, whose padding may be real even below one tick) the edit decides.
         */
        fun audibleLength(movieTimescale: Long, trackTimescale: Long, editDuration: Long, mediaTime: Long, sttsTotal: Long,
            shortenedFinalSample: Boolean): Long? =
            runCatching {
                val edit = Math.multiplyExact(editDuration, trackTimescale) // / movieTimescale
                val stts = sttsTotal - mediaTime
                if (shortenedFinalSample && abs(Math.subtractExact(Math.multiplyExact(stts, movieTimescale), edit)) < trackTimescale) stts
                else edit / movieTimescale
            }.getOrNull()?.takeIf { it > 0 }
    }
}
