package dev.coda.android.net

import dev.coda.core.playback.BackgroundWorkPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import kotlin.coroutines.CoroutineContext

/**
 * Request activity on the shared client: a request going out, or a response body being closed (the end of a
 * stream or download). [IdleConnectionCloser] restarts its delay on each.
 */
object ConnectionActivity {
    @Volatile var onActivity: (() -> Unit)? = null
    val interceptor = Interceptor { chain ->
        onActivity?.invoke()
        val response = chain.proceed(chain.request())
        val body = response.body ?: return@Interceptor response
        response.newBuilder().body(NotifyingBody(body) { onActivity?.invoke() }).build()
    }

    private class NotifyingBody(private val body: ResponseBody, private val closed: () -> Unit) : ResponseBody() {
        private val source: BufferedSource by lazy {
            object : ForwardingSource(body.source()) {
                private var done = false
                override fun close() {
                    try { super.close() } finally { if (!done) { done = true; closed() } }
                }
            }.buffer()
        }
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun source(): BufferedSource = source
    }
}

/**
 * Navidrome (a Go server) sends a TCP keep-alive probe every 15 s on each idle connection, and OkHttp
 * keeps idle connections pooled for 5 min; on mobile data every probe restarts the radio's high-power tail, so with a
 * track change every ~4 min the radio never sleeps. While [quiet] ([BackgroundWorkPolicy.quiet]: screen off and
 * playing) the idle connections are closed [delayMs] after the last [activity] (the track-change burst); the next burst
 * pays one handshake inside a radio period that is up anyway. [evictIdle] is `ConnectionPool.evictAll`, which only
 * closes idle connections: one in use (a stream being read) stays, and the end of its body restarts the delay. Screen
 * turning off restarts it too. With the screen on nothing changes (OkHttp's 5-min pool).
 */
class IdleConnectionCloser(
    scope: CoroutineScope,
    private val quiet: StateFlow<Boolean>,
    private val evictIdle: () -> Unit,
    private val delayMs: Long = BackgroundWorkPolicy.IDLE_CONNECTION_CLOSE_MS,
    // Closing a TLS socket writes close_notify: never on the main thread.
    context: CoroutineContext = Dispatchers.IO,
) {
    private val activity = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Any thread. */
    fun activity() { activity.tryEmit(Unit) }

    init {
        scope.launch(context) {
            merge(activity, quiet.filter { it }.map { }).collectLatest {
                if (!quiet.value) return@collectLatest
                delay(delayMs)
                if (quiet.value) evictIdle()
            }
        }
    }
}
