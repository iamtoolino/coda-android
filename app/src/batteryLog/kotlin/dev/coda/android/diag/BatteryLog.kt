package dev.coda.android.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.io.File
import okio.buffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Battery diagnostics, debug and preview builds only (release compiles app/src/batteryLogOff).
 *
 * Counters are lock-free increments from any thread. Everything else runs on one background thread: events, lock
 * changes and markers are logged with a timestamp, and every state change (screen, playback, charging) and every
 * [SNAPSHOT_MS] of awake time closes a *segment* with its CPU time per thread (/proc/self/task), lock held times and
 * counter deltas. The snapshot timer uses uptime, so it never wakes a sleeping phone by itself, and it only runs while
 * there is something to measure (a visible activity, a loaded player or a held lock): an idle process does no periodic
 * work. The files rotate (8 x 256 KB). Ids, counts and durations only: never titles, names, URLs or hosts. Every
 * callback is guarded: a failure never reaches the app, and repeated failures switch the recording off.
 */
object BatteryLog {
    const val ENABLED = true
    internal const val SNAPSHOT_MS = 5 * 60_000L

    private val counters = AtomicLongArray(BatteryIds.COUNTERS)
    private val http = ConcurrentHashMap<String, AtomicLong>()
    @Volatile private var recorder: Recorder? = null

    fun start(context: Context) {
        if (recorder != null) return
        synchronized(this) { if (recorder == null) recorder = Recorder(context.applicationContext) }
    }

    fun count(counter: Int, n: Long = 1) { counters.addAndGet(counter, n) }
    fun lock(holder: Int, held: Boolean) { recorder?.post { lockChanged(holder, held) } }
    fun playback(state: Int) { recorder?.post { playbackChanged(state) } }
    fun event(event: Int, value: Long = 0) {
        recorder?.post { write("EV " + BatteryLogFormat.EVENT_NAMES.getOrElse(event) { "event" } + " " + value) }
    }
    /** One Reachability failure line (counted or why not; never a host), to logcat and the battery log. */
    fun reach(line: String) {
        android.util.Log.i("CodaReach", line)
        recorder?.post { write("EV $line") }
    }
    fun httpEvents(): okhttp3.EventListener.Factory = HttpCounter
    /** Network interceptor: counts response bytes as the body is read, so a stream that fails half way still counts. */
    fun httpInterceptor(): okhttp3.Interceptor = HttpBytes
    /** A load error (Exo retries it): counted, and logged with the exception's class name only (never its message). */
    fun loadError(error: Throwable) {
        count(BatteryIds.EXO_LOAD_ERRORS)
        val name = BatteryLogFormat.exceptionLabel(error.javaClass) + "/" + BatteryLogFormat.exceptionLabel(error.cause?.javaClass)
        recorder?.post { write("EV load_error $name") }
    }

    internal fun counterValues(): LongArray = LongArray(BatteryIds.COUNTERS) { counters.get(it) }
    internal fun httpValues(): Map<String, Long> = http.mapValues { it.value.get() }
    internal fun httpEndpoint(name: String) { http.getOrPut(name) { AtomicLong() }.incrementAndGet() }
    internal val active: Recorder? get() = recorder

    private object HttpCounter : okhttp3.EventListener.Factory {
        override fun create(call: okhttp3.Call): okhttp3.EventListener = object : okhttp3.EventListener() {
            private val failed = java.util.concurrent.atomic.AtomicBoolean()
            override fun callStart(call: okhttp3.Call) {
                count(BatteryIds.HTTP_REQUESTS)
                val endpoint = endpoint(call.request().url)
                httpEndpoint(endpoint)
                if (endpoint == "stream") count(BatteryIds.HTTP_STREAM)
                if (call.request().header("Range") != null) count(BatteryIds.HTTP_RANGE)
            }
            override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
                count(BatteryIds.HTTP_CONNECTS)
            }
            // One failure per call: a body that breaks reports responseFailed and then callFailed. A call the app itself
            // cancelled (the player drops a load) is not a failure.
            override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) { failure(call) }
            override fun responseFailed(call: okhttp3.Call, ioe: java.io.IOException) { failure(call) }
            private fun failure(call: okhttp3.Call) { if (!call.isCanceled() && failed.compareAndSet(false, true)) count(BatteryIds.HTTP_FAILURES) }
        }
    }

    private object HttpBytes : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            val response = chain.proceed(chain.request())
            val body = response.body ?: return response
            return response.newBuilder().body(CountingBody(body)).build()
        }
    }

    /** Counts the bytes as they are read (the wire bytes), also when the read later fails or the caller stops early. */
    private class CountingBody(private val body: okhttp3.ResponseBody) : okhttp3.ResponseBody() {
        private val source = object : okio.ForwardingSource(body.source()) {
            override fun read(sink: okio.Buffer, byteCount: Long): Long =
                super.read(sink, byteCount).also { if (it > 0) count(BatteryIds.HTTP_BYTES_IN, it) }
        }.buffer()
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun source(): okio.BufferedSource = source
    }

    /** The Subsonic endpoint name (`rest/<name>[.view]`) if it is a known method, else "other"; never a host, path or query. */
    internal fun endpoint(url: okhttp3.HttpUrl): String {
        val segments = url.pathSegments
        if (segments.size < 2 || segments[segments.size - 2] != "rest") return "other"
        return BatteryLogFormat.endpointLabel(segments.last().removeSuffix(".view"))
    }
}

internal class Recorder(private val context: Context) {
    private val thread = HandlerThread("CodaBatteryLog", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler = Handler(thread.looper)
    val log = RotatingLog(File(context.noBackupFilesDir, "battery-log"))
    private val pid = Process.myPid()
    private val tickMs = 1000.0 / android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK).coerceAtLeast(1)
    private val stamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", java.util.Locale.US)

    private var screenOn = true
    private var charging = false
    private var playback = BatteryIds.PLAYBACK_IDLE
    private val lockSince = LongArray(BatteryIds.LOCKS) { -1 }
    private val lockHeld = LongArray(BatteryIds.LOCKS)
    private var segmentWall = 0L
    private var segmentUptime = 0L
    private var segmentProcessTicks = 0L
    private var segmentThreads: Map<Int, Pair<String, Long>> = emptyMap()
    private var segmentCounters = LongArray(BatteryIds.COUNTERS)
    private var segmentHttp: Map<String, Long> = emptyMap()

    private var visibleActivities = 0
    private var sampling = false
    /** Whether the periodic snapshot is armed (read on the log thread, e.g. through [sync]). */
    val isSampling: Boolean get() = sampling
    private val guard = FailureGuard { shutDown() }
    private var receiver: BroadcastReceiver? = null
    private var activityCallbacks: android.app.Application.ActivityLifecycleCallbacks? = null

    private val snapshot = object : Runnable {
        override fun run() {
            guard.run { closeSegment() }
            if (sampling && !guard.disabled) handler.postDelayed(this, BatteryLog.SNAPSHOT_MS)
        }
    }

    init {
        post {
            screenOn = context.getSystemService(PowerManager::class.java).isInteractive
            charging = context.getSystemService(BatteryManager::class.java).isCharging
            write("EV process_start 0")
            write("INFO build=${BatteryLogFormat.word(dev.coda.android.BuildConfig.VERSION_NAME)} " +
                "sdk=${android.os.Build.VERSION.SDK_INT} snapshot_min=${BatteryLog.SNAPSHOT_MS / 60_000}")
            write("STATE " + key())
            beginSegment()
            updateSampling()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                guard.run {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_ON -> changeState { screenOn = true }
                        Intent.ACTION_SCREEN_OFF -> changeState { screenOn = false }
                        Intent.ACTION_POWER_CONNECTED -> changeState { charging = true }
                        Intent.ACTION_POWER_DISCONNECTED -> changeState { charging = false }
                        PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED ->
                            write("EV doze " + (if (c.getSystemService(PowerManager::class.java).isDeviceIdleMode) 1 else 0))
                    }
                }
            }
        }.also { context.registerReceiver(it, filter, null, handler, Context.RECEIVER_NOT_EXPORTED) }
        (context as? android.app.Application)?.let { app ->
            activityCallbacks = object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: android.app.Activity) { post { visibleActivities++; updateSampling() } }
                override fun onActivityStopped(activity: android.app.Activity) { post { visibleActivities = maxOf(0, visibleActivities - 1); updateSampling() } }
                override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
                override fun onActivityResumed(activity: android.app.Activity) {}
                override fun onActivityPaused(activity: android.app.Activity) {}
                override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
                override fun onActivityDestroyed(activity: android.app.Activity) {}
            }.also { app.registerActivityLifecycleCallbacks(it) }
        }
    }

    /** Runs [block] on the log thread. A failure is swallowed (and counted by the guard); once off, nothing runs. */
    fun post(block: Recorder.() -> Unit) { if (!guard.disabled) handler.post { guard.run { block() } } }

    /** Persistent failures (a full disk): stop timers and callbacks, drop the thread. The app never notices. */
    private fun shutDown() {
        handler.removeCallbacksAndMessages(null)
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        (context as? android.app.Application)?.let { app -> activityCallbacks?.let { app.unregisterActivityLifecycleCallbacks(it) } }
        thread.quitSafely()
    }

    /**
     * The periodic snapshot runs only while there is something to measure; an idle process (no activity, nothing loaded in
     * the player, no lock held) does no periodic work. Going idle closes the running segment (so its data is on disk);
     * resuming closes the idle stretch as its own segment, then restarts the snapshot clock.
     */
    private fun updateSampling() {
        val want = BatteryLogFormat.shouldSample(visibleActivities, playback, lockSince.any { it >= 0 })
        if (want == sampling) return
        sampling = want
        handler.removeCallbacks(snapshot)
        closeSegment()
        if (want) handler.postDelayed(snapshot, BatteryLog.SNAPSHOT_MS)
    }

    /** Runs [block] on the log thread and waits (export, tests); never call from the log thread. */
    fun <T> sync(block: Recorder.() -> T): T {
        check(!guard.disabled) { "battery log is off" }
        val result = java.util.concurrent.CompletableFuture<T>()
        handler.post { try { result.complete(block()) } catch (e: Throwable) { result.completeExceptionally(e) } }
        return result.get(10, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun key() = BatteryLogFormat.stateKey(screenOn, playback, charging)

    fun write(text: String) { log.append(stamp.format(java.util.Date()) + " " + text) }

    private fun changeState(change: () -> Unit) {
        val before = key()
        closeSegment()
        change()
        val after = key()
        if (after != before) write("STATE $after")
    }

    fun playbackChanged(state: Int) { if (state != playback) { changeState { playback = state }; updateSampling() } }

    fun lockChanged(holder: Int, held: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val name = BatteryLogFormat.LOCK_NAMES.getOrElse(holder) { "lock" }
        if (held && lockSince[holder] < 0) {
            lockSince[holder] = now; write("WL $name acquire")
        } else if (!held && lockSince[holder] >= 0) {
            val ms = now - lockSince[holder]
            lockHeld[holder] += now - maxOf(lockSince[holder], segmentWall)
            lockSince[holder] = -1
            write("WL $name release held=$ms")
        }
        updateSampling()
    }

    private fun processTicks(): Long =
        runCatching { BatteryLogFormat.statTicks(File("/proc/self/stat").readText()) }.getOrNull() ?: 0L

    private fun beginSegment() {
        segmentWall = SystemClock.elapsedRealtime(); segmentUptime = SystemClock.uptimeMillis()
        segmentProcessTicks = processTicks()
        segmentThreads = BatteryLogFormat.threads(File("/proc/self/task"), pid)
        segmentCounters = BatteryLog.counterValues(); segmentHttp = BatteryLog.httpValues()
        lockHeld.fill(0)
    }

    /** Writes the running segment (if it lasted at all) and starts the next one in the current state. */
    fun closeSegment() {
        val now = SystemClock.elapsedRealtime()
        val wall = now - segmentWall
        if (wall < 20) return // too short to carry anything: the segment simply runs on, nothing is lost
        val threads = BatteryLogFormat.threads(File("/proc/self/task"), pid)
        val byLabel = HashMap<String, Long>()
        var threadTicks = 0L
        threads.forEach { (tid, value) ->
            val (label, ticks) = value
            val delta = ticks - (segmentThreads[tid]?.second ?: 0)
            if (delta > 0) { byLabel[label] = (byLabel[label] ?: 0) + delta; threadTicks += delta }
        }
        val processDelta = processTicks() - segmentProcessTicks
        if (processDelta > threadTicks) byLabel["exited"] = processDelta - threadTicks
        val counters = BatteryLog.counterValues().let { c -> LongArray(c.size) { c[it] - segmentCounters[it] } }
        val http = BatteryLog.httpValues().mapValues { (k, v) -> v - (segmentHttp[k] ?: 0) }.filterValues { it > 0 }
        val locks = LongArray(BatteryIds.LOCKS) { i -> lockHeld[i] + if (lockSince[i] >= 0) now - maxOf(lockSince[i], segmentWall) else 0 }
        val segment = BatteryLogFormat.Segment(key(), wall, SystemClock.uptimeMillis() - segmentUptime,
            (processDelta * tickMs).toLong(), locks, byLabel.mapValues { (it.value * tickMs).toLong() }, counters, http)
        write(BatteryLogFormat.segmentLine("", segment).trimStart())
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { battery ->
            write("BAT level=${battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)} temp=${battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)}")
        }
        beginSegment()
    }

    fun clear() { log.clear(); write("EV log_cleared 0"); write("STATE " + key()); beginSegment() }
}
