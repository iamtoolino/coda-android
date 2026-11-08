package dev.coda.core.cache

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * A structural check of an Ogg file before a resumed download is published to the cache. A transcode has no
 * length or checksum of its own, so a file assembled from two requests is only trusted when the pages tile the whole
 * file, every page's CRC-32 matches, the pages belong to one logical stream with consecutive sequence numbers, and the
 * last page carries the end-of-stream flag. A splice of two different encodes, a missing or extra tail and bytes
 * changed anywhere in the file fail one of these.
 */
object OggStream {
    private const val HEADER = 27
    private const val BOS = 0x02
    private const val EOS = 0x04
    private val crcTable = IntArray(256) { index ->
        var crc = index shl 24
        repeat(8) { crc = if (crc and Int.MIN_VALUE != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        crc
    }

    /** True when the first [length] bytes of [channel] are one complete, consistent Ogg stream. Reads do not move the channel position. */
    fun isComplete(channel: FileChannel, length: Long): Boolean = try {
        check(channel, length)
    } catch (_: IOException) { false }

    fun isComplete(bytes: ByteArray): Boolean = scan(bytes.size.toLong()) { position, count, target ->
        if (position + count > bytes.size) false else { System.arraycopy(bytes, position.toInt(), target, 0, count); true }
    }

    private fun check(channel: FileChannel, length: Long) = scan(length) { position, count, target ->
        val buffer = ByteBuffer.wrap(target, 0, count)
        var at = position
        while (buffer.hasRemaining()) { val n = channel.read(buffer, at); if (n < 0) return@scan false; at += n }
        true
    }

    private inline fun scan(length: Long, read: (Long, Int, ByteArray) -> Boolean): Boolean {
        if (length < HEADER) return false
        val page = ByteArray(HEADER + 255 + 255 * 255)
        var position = 0L
        var serial = 0
        var sequence = 0
        var first = true
        var ended = false
        while (position < length) {
            if (ended || position + HEADER > length) return false
            if (!read(position, HEADER, page)) return false
            if (page[0] != 'O'.code.toByte() || page[1] != 'g'.code.toByte() || page[2] != 'g'.code.toByte() || page[3] != 'S'.code.toByte()) return false
            if (page[4].toInt() != 0) return false
            val flags = page[5].toInt() and 0xFF
            val pageSerial = int(page, 14)
            val pageSequence = int(page, 18)
            val segments = page[26].toInt() and 0xFF
            val headerSize = HEADER + segments
            if (position + headerSize > length) return false
            if (!read(position, headerSize, page)) return false
            var body = 0
            for (i in 0 until segments) body += page[HEADER + i].toInt() and 0xFF
            val size = headerSize + body
            if (position + size > length) return false
            if (!read(position, size, page)) return false
            val stored = int(page, 22)
            for (i in 22..25) page[i] = 0
            var crc = 0
            for (i in 0 until size) crc = (crc shl 8) xor crcTable[((crc ushr 24) xor (page[i].toInt() and 0xFF)) and 0xFF]
            if (crc != stored) return false
            if (first) {
                if (flags and BOS == 0) return false
                serial = pageSerial; sequence = pageSequence; first = false
            } else {
                if (flags and BOS != 0 || pageSerial != serial || pageSequence != sequence + 1) return false
                sequence = pageSequence
            }
            if (flags and EOS != 0) ended = true
            position += size
        }
        return ended
    }

    private fun int(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
        ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
}
