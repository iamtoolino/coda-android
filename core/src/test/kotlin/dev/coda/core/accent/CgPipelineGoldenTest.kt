package dev.coda.core.accent

import java.awt.image.BufferedImage
import java.security.MessageDigest
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class CgPipelineGoldenTest {
    private val root = "/accent-golden/"
    private fun bytes(path: String) = javaClass.getResourceAsStream(root + path)!!.use { it.readBytes() }

    // Read only the outer objects of a named fixture array, retaining nested candidate records.
    private fun records(file: String, array: String): List<String> {
        val json = bytes(file).toString(Charsets.UTF_8)
        val start = json.indexOf('[', json.indexOf("\"$array\""))
        val result = mutableListOf<String>()
        var depth = 0
        var objectStart = 0
        var quoted = false
        var escaped = false
        for (i in start + 1 until json.length) {
            val c = json[i]
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{' -> { if (depth++ == 0) objectStart = i }
                '}' -> { if (--depth == 0) result += json.substring(objectStart, i + 1) }
                ']' -> if (depth == 0) return result
            }
        }
        error("Missing fixture array")
    }
    private fun String.field(key: String): String =
        Regex("\"$key\"\\s*:\\s*(?:\"([^\"]*)\"|([0-9]+))").find(this)!!.let {
            it.groups[1]?.value ?: it.groups[2]!!.value
        }
    private fun String.number(key: String) = field(key).toInt()

    @Test fun gateBMatchesEveryCoverIncludingNonSquareAndAlpha() {
        val fixtures = records("manifest.json", "fixtures").filter { "\"reduced\"" in it }
        assertEquals(24, fixtures.size)
        var totalDifferences = 0
        for (f in fixtures) {
            val png = bytes(f.field("reduced"))
            // Gate B starts with sRGB pixels. JDK PNG ignores iCCP, and its ICC
            // converter differs from ColorSync; use the native-size Swift conversion.
            val normalized = if (f.field("id") == "22-p3") {
                val prefix = "/accent/ws5-p3-srgb"
                val expectedHash = javaClass.getResourceAsStream("$prefix.source.sha256")!!
                    .bufferedReader().use { it.readText().trim() }
                val hash = MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }
                assertEquals("Regenerate with tools/accent-golden/ws5-srgb.swift", expectedHash, hash)
                javaClass.getResourceAsStream("$prefix.png")!!.use { it.readBytes() }
            } else png
            val image = ImageIO.read(normalized.inputStream())
            val w = f.number("reducedWidth")
            val h = f.number("reducedHeight")
            assertEquals(w, image.width)
            assertEquals(h, image.height)
            val input = premultiplied(image)
            val actual = CgBoxDownsampler.downsample(input, w, h)
            val expected = bytes(f.field("cg32"))
            val diffs = actual.indices.map { abs((actual[it].toInt() and 255) - (expected[it].toInt() and 255)) }
            val count = diffs.count { it != 0 }
            println("${f.field("id")}: $count/4096 different, max ${diffs.max()}")
            assertTrue(f.field("id"), diffs.max() <= 1 && count <= 4)
            totalDifferences += count
            val plan = ReducedDecodePlan.forSource(f.number("width"), f.number("height"), f.field("format"))
            assertEquals(f.field("id"), f.number("reduceFactor"), plan.factor)
            assertEquals(w, plan.width)
            assertEquals(h, plan.height)
        }
        println("Gate B: $totalDifferences/${fixtures.size * 4096} different bytes")
    }

    private fun premultiplied(image: BufferedImage): ByteArray {
        val result = ByteArray(image.width * image.height * 4)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val argb = image.getRGB(x, y)
            val alpha = argb ushr 24
            val i = (y * image.width + x) * 4
            for (c in 0..2) result[i + c] = ((((argb ushr (16 - c * 8)) and 255) * alpha + 127) / 255).toByte()
            result[i + 3] = alpha.toByte()
        }
        return result
    }

    @Test fun decodePlanMatchesAllExporterProbes() {
        val probes = records("factor-table.json", "probes")
        assertEquals(180, probes.size)
        for (p in probes) {
            val w = p.number("width")
            val h = p.number("height")
            val plan = ReducedDecodePlan.forSource(w, h, p.field("format"))
            assertEquals("${p.field("format")} $w × $h", p.number("bestFactor"), plan.factor)
            assertEquals(w / plan.factor, plan.width)
            assertEquals(h / plan.factor, plan.height)
        }
        println("ReducedDecodePlan: all ${probes.size} exporter probes matched")
    }

    @Test fun smallAndMixedAxesMatchCoreGraphics() {
        val root = "/accent/ws5-review/"
        val lines = javaClass.getResourceAsStream(root + "small.csv")!!
            .bufferedReader().use { it.readLines().drop(1) }
        assertEquals(305, lines.size)
        val inputs = javaClass.getResourceAsStream(root + "small-inputs.rgba")!!.use { it.readBytes() }
        val outputs = javaClass.getResourceAsStream(root + "small-cg32.rgba")!!.use { it.readBytes() }
        for (line in lines) {
            val (w, h, input, output) = line.split(',')
            val raw = inputs.copyOfRange(input.toInt(), input.toInt() + w.toInt() * h.toInt() * 4)
            val expected = outputs.copyOfRange(output.toInt(), output.toInt() + 4096)
            val actual = CgBoxDownsampler.downsample(raw, w.toInt(), h.toInt())
            val diffs = actual.indices.map { abs((actual[it].toInt() and 255) - (expected[it].toInt() and 255)) }
            println("Small $w × $h: ${diffs.count { it != 0 }} different, max ${diffs.max()}")
            assertArrayEquals("$w × $h", expected, actual)
        }
    }

    @Test fun decodePlanMatchesDenseCgProbes() {
        val lines = javaClass.getResourceAsStream("/accent/ws5-review/factors.csv")!!
            .bufferedReader().use { it.readLines().drop(1) }
        assertEquals(7626, lines.size)
        for (line in lines) {
            val p = line.split(',')
            val w = p[1].toInt()
            val h = p[2].toInt()
            val plan = ReducedDecodePlan.forSource(w, h, p[0])
            assertEquals("${p[0]} $w × $h", p[3].toInt(), plan.factor)
            assertEquals(w / plan.factor, plan.width)
            assertEquals(h / plan.factor, plan.height)
            assertEquals("Unique exact CG candidate", "1", p[4])
            assertEquals("Exact CG candidate", "0", p[5])
        }
        println("ReducedDecodePlan: ${lines.size} dense CG probes matched")
    }

    @Test fun rejectsInvalidInputsAndRetainsNonJpegResolution() {
        assertThrows(IllegalArgumentException::class.java) { CgBoxDownsampler.downsample(ByteArray(3), 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { CgBoxDownsampler.downsample(ByteArray(0), 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { ReducedDecodePlan.forSource(-1, 472, "JPEG") }
        assertEquals(ReducedDecodePlan(1, 472, 472), ReducedDecodePlan.forSource(472, 472, "image/webp"))
        assertEquals(8, ReducedDecodePlan.forSource(472, 472, "image/jpeg").factor)
    }
}
