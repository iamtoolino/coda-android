package dev.coda.android.diag

import java.io.File

/**
 * Pure parts of the battery log (JVM-tested): what may be written, /proc parsing, the rotating files and the
 * export summary. Privacy: lines carry ids, counts, durations and words from fixed lists only (thread and endpoint labels are
 * chosen from allowlists, never copied); never titles, names, URLs or hosts.
 */
internal object BatteryLogFormat {
    val COUNTER_NAMES = listOf(
        "position_tick", "session_state", "notification_update", "session_extras", "extras_loop", "probe_loop",
        "waveform_loop", "cache_cycle", "prefetch_bytes", "prefetch_files", "http_requests", "http_bytes_in",
        "http_connects", "http_failures", "http_stream", "http_range", "stream_opens", "stream_skip_bytes",
        "queue_saves", "queue_loads", "artwork_loads", "ui_progress_tick", "waveform_decodes", "cache_checks", "dl1_job_runs",
        "exo_load_errors", "queue_copy_writes",
        "skipped_queue_save", "skipped_queue_load", "skipped_scrobble", "skipped_now_playing", "skipped_ping", "skipped_album_resume",
        "provider_local_covers", "provider_cover_fetches",
    )
    val LOCK_NAMES = listOf("playback", "wifi", "dl1_job")
    val PLAYBACK_NAMES = listOf("idle", "playing", "paused", "buffering", "waiting")
    val EVENT_NAMES = listOf("service_create", "service_destroy", "foreground", "job_start", "job_stop", "job_finish", "mark", "stream_reopen", "engine_source")

    private val unsafe = Regex("[^A-Za-z0-9_.:#-]")

    /** A fixed word for the log: only [A-Za-z0-9_.:#-], at most 32 characters. */
    fun word(value: String): String = value.replace(unsafe, "_").take(32).ifEmpty { "_" }

    /**
     * Every thread label that can reach the log or an export. The label is chosen from this fixed set, never derived from
     * the thread's text (OkHttp names its threads after the request URL and ART keeps the *last* 15 characters of a long
     * dotted name, so a host fragment can be all that is left).
     */
    val THREAD_LABELS: Set<String> by lazy { (FAMILIES.map { it.second } + listOf("main", "exited", "net", "other")).toSet() }

    /** Prefix of the kernel's 15 character name -> label, first match wins. */
    private val FAMILIES = listOf(
        "DefaultDispatch" to "DefaultDispatch", "DefaultExecutor" to "DefaultExecutor",
        "OkHttp" to "OkHttp", "Okio" to "Okio",
        "ExoPlayer:Play" to "ExoPlayer:Playback", "ExoPlayer:Load" to "ExoPlayer:Loader",
        "ExoPlayer:Media" to "ExoPlayer:Media", "ExoPlayer" to "ExoPlayer",
        "CodaBatteryLog" to "CodaBatteryLog", "CodaWaveform" to "CodaWaveform", "Coda" to "Coda",
        "MediaCodec" to "MediaCodec", "CodecLooper" to "MediaCodec", "HwBinder" to "Binder", "Binder" to "Binder", "binder" to "Binder",
        "Jit thread pool" to "Jit", "Profile Saver" to "ProfileSaver", "HeapTaskDaemon" to "HeapTaskDaemon",
        "FinalizerDaemon" to "FinalizerDaemon", "FinalizerWatchd" to "FinalizerWatchdog", "ReferenceQueueD" to "ReferenceQueue",
        "Signal Catcher" to "SignalCatcher", "RenderThread" to "RenderThread", "hwuiTask" to "hwuiTask",
        "Timer-" to "Timer", "pool-" to "pool", "AsyncTask" to "AsyncTask", "Thread-" to "Thread",
        "Time-limited" to "TestRunner", "Instr:" to "TestRunner", "ADB-JDWP" to "Debugger", "perfetto_hprof" to "Debugger",
    )
    private val labelOfFamily = FAMILIES.associate { it.second to it.second }

    /**
     * A thread label from the fixed [THREAD_LABELS] only. A name with a slash is an OkHttp call thread whose URL tail is
     * all that is left (labelled "net"); anything not in the family table is "other". Idempotent, so an old log's labels
     * are re-checked with the same call.
     */
    fun threadLabel(name: String, main: Boolean = false): String {
        if (main) return "main"
        if (name == "main" || name == "exited" || name == "net" || name == "other") return name
        if ('/' in name) return "net"
        FAMILIES.firstOrNull { name.startsWith(it.first) }?.let { return it.second }
        return labelOfFamily[name] ?: "other"
    }

    /** The Subsonic methods Coda may call; any other path (a media file, a proxy path, a typo) is "other". */
    private val ENDPOINTS = setOf(
        "ping", "getLicense", "getMusicFolders", "getIndexes", "getMusicDirectory", "getGenres", "getArtists", "getArtist",
        "getAlbum", "getSong", "getVideos", "getArtistInfo", "getArtistInfo2", "getAlbumInfo", "getAlbumInfo2", "getSimilarSongs",
        "getSimilarSongs2", "getTopSongs", "getAlbumList", "getAlbumList2", "getRandomSongs", "getSongsByGenre", "getNowPlaying",
        "getStarred", "getStarred2", "search", "search2", "search3", "getPlaylists", "getPlaylist", "createPlaylist", "updatePlaylist",
        "deletePlaylist", "stream", "download", "hls", "getCaptions", "getCoverArt", "getLyrics", "getLyricsBySongId", "getAvatar",
        "star", "unstar", "setRating", "getShares", "scrobble", "getPlayQueue", "savePlayQueue", "getBookmarks", "createBookmark",
        "deleteBookmark", "getScanStatus", "startScan", "getOpenSubsonicExtensions", "tokenInfo", "getUser", "getUsers",
    )

    /** The Subsonic method name for the log, or "other". */
    fun endpointLabel(name: String): String = if (name in ENDPOINTS) name else "other"

    /** The exception class for the log: its simple name for a platform, OkHttp, Media3 or Coda class, else "other". */
    fun exceptionLabel(type: Class<*>?): String {
        if (type == null) return "-"
        val known = listOf("java.", "javax.", "android.", "androidx.", "kotlin.", "kotlinx.", "okhttp3.", "okio.", "dev.coda.")
        return if (known.any { type.name.startsWith(it) }) word(type.simpleName) else "other"
    }

    /** Re-checks the thread and HTTP labels of a logged segment line (an export never trusts what an older build wrote). */
    fun scrubLine(line: String): String {
        val at = line.indexOf(" SEG ")
        if (at < 0) return line
        val parts = line.split(" | ")
        if (parts.size != 4) return line
        fun rewrite(part: String, tag: String, label: (String) -> String): String {
            val merged = LinkedHashMap<String, Long>()
            part.removePrefix(tag).trim().split(' ').filter { '=' in it }.forEach {
                val key = label(it.substringBefore('='))
                merged[key] = (merged[key] ?: 0) + (it.substringAfter('=').toLongOrNull() ?: 0)
            }
            return tag + merged.entries.joinToString("") { " ${it.key}=${it.value}" }
        }
        val threads = rewrite(parts[1], "thr") { threadLabel(it) }
        val http = rewrite(parts[3], "http") { endpointLabel(it) }
        return parts[0] + " | " + threads + " | " + parts[2] + " | " + http
    }

    /** True while the app has something to measure: a visible activity, a loaded player or a held lock. */
    fun shouldSample(visibleActivities: Int, playback: Int, lockHeld: Boolean): Boolean =
        visibleActivities > 0 || playback != BatteryIds.PLAYBACK_IDLE || lockHeld

    /** utime + stime in clock ticks from a /proc stat line, or null. The name may contain spaces and parentheses. */
    fun statTicks(line: String): Long? {
        val rest = line.substringAfterLast(')', "").trim().split(' ')
        // rest[0] is field 3 (state); utime is field 14, stime field 15.
        if (rest.size < 13) return null
        val utime = rest[11].toLongOrNull() ?: return null
        val stime = rest[12].toLongOrNull() ?: return null
        return utime + stime
    }

    fun statName(line: String): String = line.substringAfter('(', "").substringBeforeLast(')', "")

    /** Per-thread CPU ticks of this process: tid -> (label, ticks). Threads that exit between reads drop out. */
    fun threads(taskDir: File, pid: Int): Map<Int, Pair<String, Long>> {
        val result = HashMap<Int, Pair<String, Long>>()
        taskDir.listFiles()?.forEach { dir ->
            val tid = dir.name.toIntOrNull() ?: return@forEach
            val line = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@forEach
            val ticks = statTicks(line) ?: return@forEach
            result[tid] = threadLabel(statName(line), main = tid == pid) to ticks
        }
        return result
    }

    fun stateKey(screenOn: Boolean, playback: Int, charging: Boolean): String =
        (if (screenOn) "screen_on" else "screen_off") + "." + PLAYBACK_NAMES.getOrElse(playback) { "idle" } + "." +
            (if (charging) "charging" else "battery")

    /** One segment: a stretch of time in one state, closed by a state change or the periodic snapshot. */
    data class Segment(
        val state: String, val wallMs: Long, val awakeMs: Long, val cpuMs: Long,
        val locks: LongArray, val threads: Map<String, Long>, val counters: LongArray, val http: Map<String, Long>,
    )

    fun segmentLine(prefix: String, s: Segment): String = buildString {
        append(prefix).append(" SEG ").append(s.state)
        append(" wall=").append(s.wallMs).append(" awake=").append(s.awakeMs).append(" cpu=").append(s.cpuMs)
        s.locks.forEachIndexed { i, ms -> append(" lock.").append(LOCK_NAMES[i]).append('=').append(ms) }
        append(" | thr")
        s.threads.entries.filter { it.value > 0 }.sortedByDescending { it.value }.forEach { append(' ').append(threadLabel(it.key)).append('=').append(it.value) }
        append(" | cnt")
        s.counters.forEachIndexed { i, n -> if (n != 0L) append(' ').append(COUNTER_NAMES[i]).append('=').append(n) }
        append(" | http")
        s.http.entries.sortedBy { it.key }.forEach { append(' ').append(endpointLabel(it.key)).append('=').append(it.value) }
    }

    private fun labelled(values: Map<String, Long>, label: (String) -> String): Map<String, Long> {
        val merged = HashMap<String, Long>()
        values.forEach { (k, v) -> label(k).let { merged[it] = (merged[it] ?: 0) + v } }
        return merged
    }

    fun parseSegment(line: String): Segment? {
        val at = line.indexOf(" SEG ")
        if (at < 0) return null
        val parts = line.substring(at + 5).split(" | ")
        if (parts.size != 4) return null
        val head = parts[0].split(' ')
        val fields = head.drop(1).mapNotNull { kv -> kv.split('=').takeIf { it.size == 2 }?.let { it[0] to (it[1].toLongOrNull() ?: 0) } }.toMap()
        fun map(part: String, tag: String) = part.removePrefix(tag).trim().split(' ').filter { '=' in it }
            .associate { it.substringBefore('=') to (it.substringAfter('=').toLongOrNull() ?: 0) }
        val counters = map(parts[2], "cnt")
        return Segment(head[0], fields["wall"] ?: 0, fields["awake"] ?: 0, fields["cpu"] ?: 0,
            LongArray(LOCK_NAMES.size) { fields["lock." + LOCK_NAMES[it]] ?: 0 }, labelled(map(parts[1], "thr")) { threadLabel(it) },
            LongArray(COUNTER_NAMES.size) { counters[COUNTER_NAMES[it]] ?: 0 }, labelled(map(parts[3], "http")) { endpointLabel(it) })
    }

    /** The plain-text summary at the top of an export: per state, time, CPU, locks, top threads and counters per hour. */
    fun summary(lines: List<String>, header: List<String>): String = buildString {
        header.forEach { appendLine(it) }
        val segments = lines.mapNotNull(::parseSegment)
        val first = lines.firstOrNull()?.substringBefore(' ')
        val last = lines.lastOrNull()?.substringBefore(' ')
        appendLine("Log: ${lines.size} lines, ${segments.size} segments, from $first to $last")
        val events = lines.mapNotNull { l -> l.split(' ').takeIf { it.size >= 3 && it[1] == "EV" }?.get(2) }.groupingBy { it }.eachCount()
        if (events.isNotEmpty()) appendLine("Events: " + events.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ${it.value}" })
        val groups = segments.groupBy { it.state }.toList().sortedByDescending { (_, list) -> list.sumOf { it.wallMs } }
        for ((state, list) in listOf("all" to segments) + groups) {
            val wall = list.sumOf { it.wallMs }
            if (wall <= 0) continue
            val hours = wall / 3_600_000.0
            fun perHour(v: Long) = if (hours > 0) v / hours else 0.0
            val cpu = list.sumOf { it.cpuMs }
            appendLine()
            appendLine("== $state: ${"%.2f".format(hours)} h, awake ${pct(list.sumOf { it.awakeMs }, wall)}, " +
                "CPU ${cpu / 1000} s = ${"%.1f".format(perHour(cpu) / 60_000)} min/h (${pct(cpu, wall)} of one core)")
            val locks = LOCK_NAMES.indices.map { i -> LOCK_NAMES[i] to list.sumOf { it.locks[i] } }.filter { it.second > 0 }
            if (locks.isNotEmpty()) appendLine("Locks held: " + locks.joinToString(", ") { "${it.first} ${pct(it.second, wall)}" })
            val threads = HashMap<String, Long>()
            list.forEach { s -> s.threads.forEach { (k, v) -> threads[k] = (threads[k] ?: 0) + v } }
            appendLine("Top threads (CPU s, share of process CPU):")
            threads.entries.sortedByDescending { it.value }.take(12).forEach {
                appendLine("  ${it.key.padEnd(18)} ${"%8.1f".format(it.value / 1000.0)}  ${pct(it.value, cpu)}")
            }
            val counters = COUNTER_NAMES.indices.map { i -> COUNTER_NAMES[i] to list.sumOf { it.counters[i] } }.filter { it.second != 0L }
            if (counters.isNotEmpty()) appendLine("Counters per hour: " + counters.joinToString(", ") { "${it.first} ${"%.0f".format(perHour(it.second))}" })
            val http = HashMap<String, Long>()
            list.forEach { s -> s.http.forEach { (k, v) -> http[k] = (http[k] ?: 0) + v } }
            if (http.isNotEmpty()) appendLine("HTTP endpoints per hour: " + http.entries.sortedByDescending { it.value }
                .joinToString(", ") { "${it.key} ${"%.0f".format(perHour(it.value))}" })
        }
    }

    private fun pct(part: Long, whole: Long) = if (whole <= 0) "-" else "%.1f %%".format(part * 100.0 / whole)
}

/**
 * Diagnostics must never take the app down: runs a callback, swallows its failure and, after [limit] failures in a row
 * (a full disk, a read-only store), turns the recording off for good.
 */
internal class FailureGuard(private val limit: Int = 5, private val onDisabled: () -> Unit = {}) {
    @Volatile var disabled = false; private set
    private var failures = 0
    fun <T> run(block: () -> T): T? {
        if (disabled) return null
        return try { block().also { failures = 0 } } catch (e: Throwable) {
            if (++failures >= limit) { disabled = true; runCatching(onDisabled) }
            null
        }
    }
}

/** Size-capped rotating text files: `battery-0.log` is current, `battery-N.log` older; the oldest drops out. */
internal class RotatingLog(private val dir: File, private val maxBytes: Long = 256 * 1024, private val files: Int = 8) {
    private fun file(i: Int) = File(dir, "battery-$i.log")
    @Synchronized fun append(line: String) {
        dir.mkdirs()
        val current = file(0)
        if (current.length() + line.length + 1 > maxBytes) rotate()
        current.appendText(line + "\n")
    }
    private fun rotate() {
        file(files - 1).delete()
        for (i in files - 2 downTo 0) file(i).takeIf { it.exists() }?.renameTo(file(i + 1))
    }
    /** All lines, oldest first. */
    @Synchronized fun lines(): List<String> = (files - 1 downTo 0).map(::file).filter { it.isFile }.flatMap { it.readLines() }
    @Synchronized fun sizeBytes(): Long = (0 until files).sumOf { file(it).length() }
    @Synchronized fun clear() { (0 until files).forEach { file(it).delete() } }
}
