package dev.coda.android.diag

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The battery log's pure parts (debug variant; release compiles the no-op stub). */
class BatteryLogFormatTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun namesCoverEveryId() {
        assertEquals(BatteryIds.COUNTERS, BatteryLogFormat.COUNTER_NAMES.size)
        assertEquals(BatteryIds.LOCKS, BatteryLogFormat.LOCK_NAMES.size)
        assertEquals(BatteryIds.EV_ENGINE_SOURCE + 1, BatteryLogFormat.EVENT_NAMES.size)
        assertEquals(BatteryIds.PLAYBACK_WAITING + 1, BatteryLogFormat.PLAYBACK_NAMES.size)
        assertEquals(BatteryLogFormat.COUNTER_NAMES.size, BatteryLogFormat.COUNTER_NAMES.toSet().size)
    }

    @Test fun statLineWithSpacesAndParenthesesInTheName() {
        val line = "1234 (Profile (Saver) x) S 1 2 3 4 5 6 7 8 9 10 250 75 0 0 20 0 1 0 100 0 0"
        assertEquals(325L, BatteryLogFormat.statTicks(line))
        assertEquals("Profile (Saver) x", BatteryLogFormat.statName(line))
        assertNull(BatteryLogFormat.statTicks("garbage"))
    }

    @Test fun threadLabelsComeFromTheFixedSetNotFromTheName() {
        assertEquals("DefaultDispatch", BatteryLogFormat.threadLabel("DefaultDispatch"))
        assertEquals("DefaultDispatch", BatteryLogFormat.threadLabel("DefaultDispatcher-worker-3"))
        assertEquals("Binder", BatteryLogFormat.threadLabel("Binder:12345_3"))
        assertEquals("OkHttp", BatteryLogFormat.threadLabel("OkHttp TaskRunner"))
        assertEquals("net", BatteryLogFormat.threadLabel("OkHttp https://"))
        assertEquals("main", BatteryLogFormat.threadLabel("dev.coda.androi", main = true))
        assertEquals("ExoPlayer:Playback", BatteryLogFormat.threadLabel("ExoPlayer:Playba"))
        assertEquals("CodaWaveform", BatteryLogFormat.threadLabel("CodaWaveform"))
        assertEquals("net", BatteryLogFormat.threadLabel("x://host.example"))
        assertEquals("other", BatteryLogFormat.threadLabel("music.example"))
        assertEquals("a_b_c", BatteryLogFormat.word("a b/c"))
        // Idempotent: a label that is already a label stays one (old logs are re-checked with the same call).
        BatteryLogFormat.THREAD_LABELS.forEach { assertEquals(it, BatteryLogFormat.threadLabel(it)) }
    }

    @Test fun hostileThreadNamesNeverLeakAnyText() {
        // What ART leaves of "OkHttp https://music.example.org/..." (the last 15 characters of a long dotted name), and worse.
        val hostile = listOf(
            "sic.example/...", "ic.example.org", "example.org/...", "music.example", "203.0.113.20:4533", "198.51.100.7", "::1", "[fe80::1]:443",
            "fe80::1ff:fe23:4567:890a", "2001:db8::8a2e:370:7334", "alice@host.example", "bob", "admin:s3cr3t@h", "token=abc123def", "?u=alice&t=9f8e",
            "/rest/stream?id=42", "https://u:p@h.example/", "OkHttp https://music.example/...", "OkHttp music.example.org", "OkHttp 203.0.113.20",
            "Coda alice", "ExoPlayer:alice", "DefaultDispatcher-alice", "Binder:alice", "pool-alice", "Thread-secret", "song title - artist.flac", "",
            "a".repeat(300), "OkHttp ".repeat(50), "\u0000\n\t", "\u00e9\u4e2d\u6587",
        )
        for (name in hostile) {
            val label = BatteryLogFormat.threadLabel(name)
            assertTrue("'$name' -> '$label'", label in BatteryLogFormat.THREAD_LABELS)
            // And through a /proc stat line and a logged segment: only fixed labels come out.
            val line = BatteryLogFormat.segmentLine("", BatteryLogFormat.Segment("screen_off.idle.battery", 1, 1, 1, longArrayOf(0, 0, 0),
                mapOf(name to 5L), LongArray(BatteryIds.COUNTERS), mapOf(name to 1L)))
            val labels = line.substringAfter(" | thr").substringBefore(" | cnt").trim().split(' ').map { it.substringBefore('=') }
            assertTrue(line, labels.all { it in BatteryLogFormat.THREAD_LABELS })
            assertTrue(line, line.substringAfter(" | http").trim().substringBefore('=') == "other")
        }
        val random = java.util.Random(42)
        repeat(2_000) {
            val name = String(CharArray(random.nextInt(40)) { (random.nextInt(0x7f - 0x20) + 0x20).toChar() })
            assertTrue(name, BatteryLogFormat.threadLabel(name) in BatteryLogFormat.THREAD_LABELS)
        }
    }

    @Test fun anOldLogLineIsRescrubbedOnExport() {
        val old = "2026-10-30T10:00:00.000+0100 SEG screen_on.playing.battery wall=10 awake=10 cpu=5 lock.playback=0 lock.wifi=0 lock.dl1_job=0 | " +
            "thr sic.example_...=7 DefaultDispatch=3 203.0.113.20_4533=1 | cnt http_requests=2 | http stream=2 alice_secret=1"
        val clean = BatteryLogFormat.scrubLine(old)
        assertFalse(clean, "example" in clean || "203" in clean || "alice" in clean)
        val parsed = BatteryLogFormat.parseSegment(clean)!!
        assertEquals(mapOf("other" to 8L, "DefaultDispatch" to 3L), parsed.threads)
        assertEquals(mapOf("stream" to 2L, "other" to 1L), parsed.http)
        assertEquals("EV mark 1", BatteryLogFormat.scrubLine("EV mark 1"))
        val summary = BatteryLogFormat.summary(listOf(old), listOf("H"))
        assertFalse(summary, "example" in summary || "alice" in summary)
    }

    @Test fun exceptionLabelsAreClassNamesOfKnownPackagesOnly() {
        assertEquals("IOException", BatteryLogFormat.exceptionLabel(java.io.IOException::class.java))
        assertEquals("-", BatteryLogFormat.exceptionLabel(null))
        assertEquals("other", BatteryLogFormat.exceptionLabel(org.junit.Test::class.java))
    }

    @Test fun samplingRunsOnlyWhileThereIsSomethingToMeasure() {
        assertFalse(BatteryLogFormat.shouldSample(0, BatteryIds.PLAYBACK_IDLE, false))
        assertTrue(BatteryLogFormat.shouldSample(1, BatteryIds.PLAYBACK_IDLE, false))      // the app is in use
        assertTrue(BatteryLogFormat.shouldSample(0, BatteryIds.PLAYBACK_PAUSED, false))    // paused but loaded
        assertTrue(BatteryLogFormat.shouldSample(0, BatteryIds.PLAYBACK_PLAYING, false))
        assertTrue(BatteryLogFormat.shouldSample(0, BatteryIds.PLAYBACK_WAITING, false))
        assertTrue(BatteryLogFormat.shouldSample(0, BatteryIds.PLAYBACK_IDLE, true))       // a download job's lock
    }

    @Test fun aFailingWriterNeverThrowsAndSwitchesTheLogOff() {
        val blocked = temp.newFile("not-a-directory") // the log directory is a file: every append fails
        val log = RotatingLog(blocked)
        var off = 0
        val guard = FailureGuard(limit = 3) { off++ }
        repeat(2) { assertNull(guard.run { log.append("x") }) }
        assertFalse(guard.disabled)
        assertNull(guard.run { log.append("x") })
        assertTrue(guard.disabled); assertEquals(1, off)
        var ran = false
        assertNull(guard.run { ran = true })
        assertFalse("nothing runs once off", ran)
        // A success in between resets the count.
        val ok = FailureGuard(limit = 2)
        assertNull(ok.run { error("once") }); assertEquals(1, ok.run { 1 }); assertNull(ok.run { error("again") })
        assertFalse(ok.disabled)
    }

    @Test fun threadsReadsAFakeProcTree() {
        val task = temp.newFolder("task")
        fun thread(tid: Int, name: String, u: Int, s: Int) = File(task, "$tid").apply { mkdirs() }.resolve("stat")
            .writeText("$tid ($name) S 1 2 3 4 5 6 7 8 9 10 $u $s 0 0 20 0 1 0\n")
        thread(10, "dev.coda.androi", 100, 50); thread(11, "ExoPlayer:Playba", 30, 3); thread(12, "OkHttp https://", 1, 1); thread(13, "sic.example/...", 4, 0)
        File(task, "self").mkdirs()
        val threads = BatteryLogFormat.threads(task, 10)
        assertEquals("main" to 150L, threads[10]); assertEquals("ExoPlayer:Playback" to 33L, threads[11]); assertEquals("net" to 2L, threads[12]); assertEquals("net" to 4L, threads[13])
        assertEquals(4, threads.size)
    }

    @Test fun segmentRoundTripsAndSummarises() {
        val counters = LongArray(BatteryIds.COUNTERS).apply { this[BatteryIds.POSITION_TICK] = 36_000; this[BatteryIds.HTTP_REQUESTS] = 240 }
        val playing = BatteryLogFormat.Segment(BatteryLogFormat.stateKey(false, BatteryIds.PLAYBACK_PLAYING, false), 3_600_000, 3_500_000, 720_000,
            longArrayOf(3_600_000, 0, 0), mapOf("main" to 400_000L, "ExoPlayer:Playback" to 300_000L), counters, mapOf("savePlayQueue" to 240L))
        val line = "2026-10-30T10:00:00.000+0100 " + BatteryLogFormat.segmentLine("", playing).trimStart()
        val parsed = BatteryLogFormat.parseSegment(line)!!
        assertEquals(playing.state, parsed.state); assertEquals(720_000L, parsed.cpuMs); assertEquals(3_500_000L, parsed.awakeMs)
        assertArrayEquals(playing.locks, parsed.locks); assertArrayEquals(counters, parsed.counters)
        assertEquals(playing.threads, parsed.threads); assertEquals(playing.http, parsed.http)
        val summary = BatteryLogFormat.summary(listOf("2026-10-30T09:59:00.000+0100 EV service_create 0", line), listOf("Header"))
        assertTrue(summary, summary.contains("== screen_off.playing.battery: 1.00 h"))
        assertTrue(summary, summary.contains("12.0 min/h (20.0 % of one core)"))
        assertTrue(summary, summary.contains("position_tick 36000"))
        assertTrue(summary, summary.contains("savePlayQueue 240"))
        assertTrue(summary, summary.contains("Locks held: playback 100.0 %"))
        assertTrue(summary, summary.contains("Events: service_create 1"))
    }

    @Test fun rotatingLogStaysUnderItsCapAndKeepsOrder() {
        val log = RotatingLog(temp.newFolder("log"), maxBytes = 100, files = 3)
        repeat(40) { log.append("line %02d xxxxxxxx".format(it)) }
        assertTrue(log.sizeBytes() <= 300)
        val lines = log.lines()
        assertEquals("line 39 xxxxxxxx", lines.last())
        assertEquals(lines.sorted(), lines)
        log.clear(); assertTrue(log.lines().isEmpty())
    }

    @Test fun endpointKeepsOnlyAKnownSubsonicMethod() {
        assertEquals("other", BatteryLog.endpoint(okhttp3.HttpUrl.Builder().scheme("https").host("h.invalid").addPathSegments("rest/aliceSecret").build()))
    }

    @Test fun endpointKeepsOnlyTheSubsonicName() {
        assertEquals("stream", BatteryLog.endpoint(okhttp3.HttpUrl.Builder().scheme("https").host("h.invalid").addPathSegments("music/rest/stream.view").build()))
        assertEquals("getCoverArt", BatteryLog.endpoint(okhttp3.HttpUrl.Builder().scheme("https").host("h.invalid").addPathSegments("rest/getCoverArt").build()))
        assertEquals("other", BatteryLog.endpoint(okhttp3.HttpUrl.Builder().scheme("https").host("h.invalid").addPathSegments("media/Some Title.flac").build()))
        assertEquals("other", BatteryLog.endpoint(okhttp3.HttpUrl.Builder().scheme("https").host("h.invalid").addPathSegments("rest/x y").build()))
    }
}
