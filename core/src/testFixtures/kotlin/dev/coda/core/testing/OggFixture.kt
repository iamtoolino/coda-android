package dev.coda.core.testing

/** Small valid Ogg streams (one packet per page, correct CRC-32), to build two "transcodes" of the same song. */
object OggFixture {
    private val table = IntArray(256) { i ->
        var crc = i shl 24
        repeat(8) { crc = if (crc and Int.MIN_VALUE != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        crc
    }

    /** One page per body; the first is the beginning of the stream and the last its end. */
    fun stream(serial: Int, bodies: List<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        bodies.forEachIndexed { index, body ->
            val laces = ByteArray(body.size / 255 + 1) { 255.toByte() }.also { it[it.size - 1] = (body.size % 255).toByte() }
            val flags = (if (index == 0) 2 else 0) or (if (index == bodies.lastIndex) 4 else 0)
            val page = ByteArray(27 + laces.size + body.size)
            "OggS".forEachIndexed { i, c -> page[i] = c.code.toByte() }
            page[5] = flags.toByte()
            put(page, 6, index.toLong() * 960); put(page, 14, serial.toLong()); put(page, 18, index.toLong())
            page[26] = laces.size.toByte()
            laces.copyInto(page, 27); body.copyInto(page, 27 + laces.size)
            var crc = 0
            for (b in page) crc = (crc shl 8) xor table[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
            put(page, 22, crc.toLong() and 0xFFFFFFFFL)
            out.write(page)
        }
        return out.toByteArray()
    }

    /** Two headers (the first is an Opus identification header, as the cache recognises Opus by it), then audio pages of [audio] pseudo-random bytes each; the same [seed] gives the same audio. */
    fun transcode(serial: Int, seed: Int, pages: Int = 3, audio: Int = 20_000): ByteArray {
        val random = java.util.Random(seed.toLong())
        return stream(serial, listOf("OpusHead".toByteArray() + ByteArray(11) { 1 }, ByteArray(30) { 2 }) + List(pages) { ByteArray(audio).also(random::nextBytes) })
    }

    private fun put(bytes: ByteArray, at: Int, value: Long) { for (i in 0 until (if (at == 6) 8 else 4)) bytes[at + i] = (value ushr (8 * i)).toByte() }
}
