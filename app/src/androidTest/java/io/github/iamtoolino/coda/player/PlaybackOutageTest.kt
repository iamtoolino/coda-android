package io.github.iamtoolino.coda.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamtoolino.coda.CodaApplication
import io.github.iamtoolino.coda.AppGraph
import io.github.iamtoolino.coda.NavidromeSession
import io.github.iamtoolino.coda.data.NavidromeClient
import io.github.iamtoolino.coda.data.Song
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Synthetic loopback outage: no real account, library, radio toggle, or remote server. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackOutageTest {
    @Test fun cacheReplenishesAfterServerReturnsWithoutAnotherTrackOrNetworkEvent() = fixture { f ->
        f.seedFourTracks()
        f.server.online = false
        f.main { f.player.seekTo(1, 0) }
        f.waitUntil("next uncached track was attempted") { f.server.requests(4) > 0 }
        f.waitUntil("cached playing track ready") { f.mainValue { f.player.playbackState == Player.STATE_READY } }
        f.waitUntil("failed cache writer has stopped") { f.cacheWriterStopped() }
        Thread.sleep(2_000) // No timeline/network events while reception returns between tracks.
        assertTrue("track 4 is still missing before server recovery", !f.fullyCached(4))
        f.server.online = true
        f.waitUntil("cache retries while paused on the same track", 15_000) { f.fullyCached(4) }
        assertEquals(1, f.mainValue { f.player.currentMediaItemIndex })
    }

    @Test fun wholeQueueReplenishesAndClearingCancelsPendingCacheRetry() = fixture { f ->
        f.seedFourTracks()
        f.enableWholeQueue()
        f.waitUntil("whole queue first missing track attempted") { f.server.requests(4) > 0 }
        f.waitUntil("failed writer stopped") { f.cacheWriterStopped() }
        Thread.sleep(2_000)
        f.server.online = true
        f.waitUntil("whole queue replenishes beyond normal window", 15_000) { f.fullyCached(11) }
        // A new failure is still cancelled when the queue is emptied.
        f.server.online = false
        f.main { f.player.setMediaItem(f.newUncachedItem()) }
        f.waitUntil("replacement download attempted") { f.server.requests(99) > 0 }
        f.waitUntil("replacement writer stopped") { f.cacheWriterStopped() }
        f.main { f.player.clearMediaItems() }
        // Let requests already dispatched by the player finish cancellation before counting retries.
        Thread.sleep(500)
        val attempts = f.server.requests(99)
        f.server.online = true
        Thread.sleep(11_000)
        assertEquals(attempts, f.server.requests(99))
    }

    @Test fun networkSourceErrorRecoversAndExplicitPausePreventsRecovery() = fixture { f ->
        f.seedFourTracks()
        f.server.online = false
        for (index in 1..3) {
            f.main { f.player.seekTo(index, 0); f.player.play() }
            f.waitUntil("cached track $index keeps playing during failed replenishment") {
                f.mainValue { f.player.isPlaying && f.player.playerError == null }
            }
        }
        f.main { f.player.seekTo(4, 0); f.player.play() }
        f.waitUntil("uncached track reaches source error", 20_000) { f.mainValue { f.player.playerError != null } }
        assertTrue(f.mainValue { f.player.playerError!!.errorCode in setOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        ) })
        f.server.online = true
        f.waitUntil("playback recovers without tapping Play", 20_000) { f.mainValue { f.player.isPlaying } }
        assertEquals(4, f.mainValue { f.player.currentMediaItemIndex })

        // A later source failure must not turn an explicit Pause into an automatic Play.
        f.server.online = false
        f.main { f.player.seekTo(11, 0); f.player.prepare(); f.player.play() }
        f.waitUntil("second source error", 20_000) { f.mainValue { f.player.playerError != null } }
        f.main { f.player.pause() }
        f.server.online = true
        Thread.sleep(6_000)
        assertTrue(f.mainValue { !f.player.playWhenReady && !f.player.isPlaying })
    }

    @Test fun permanentHttpErrorDoesNotAutomaticallyRetryPlayback() = fixture { f ->
        f.seedFourTracks()
        f.server.offlineStatus = 404
        f.main { f.player.seekTo(11, 0); f.player.play() }
        f.waitUntil("missing media reaches source error", 20_000) { f.mainValue { f.player.playerError != null } }
        f.server.online = true
        Thread.sleep(6_000)
        assertTrue(f.mainValue { f.player.playerError != null && !f.player.isPlaying })
    }

    private fun fixture(test: (Fixture) -> Unit) {
        assumeTrue("Only the explicitly selected emulator may run service outage fixtures",
            Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator"))
        Fixture().use(test)
    }

    private class Fixture : AutoCloseable {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val server = AudioServer()
        private val application = context.applicationContext as CodaApplication
        private val playbackField = CodaApplication::class.java.getDeclaredField("playback").apply { isAccessible = true }
        private val graphSession = AppGraph::class.java.getDeclaredField("session").apply { isAccessible = true }
        private val oldSession = graphSession.get(AppGraph)
        private val preferences = context.getSharedPreferences("playback_snapshot", Context.MODE_PRIVATE)
        private val oldSnapshot = preferences.getString("current", null)
        private val audioDirectory = File(context.cacheDir, "transient-audio")
        private val backup = File(context.cacheDir, "outage-original-${System.nanoTime()}")
        private val mobile = isMobileNetwork(context)
        private val variant = if (mobile) "opus" else "raw"
        private val account = NavidromeSession(NavidromeClient(server.url, "fixture", "fixture"),
            "outage-${System.nanoTime()}", Long.MAX_VALUE - System.nanoTime())
        private lateinit var controller: MediaController
        lateinit var player: ExoPlayer
        private lateinit var cache: SimpleCache
        private lateinit var factory: CacheDataSource.Factory

        init {
            main { application.playback.release() }
            context.stopService(Intent(context, PlaybackService::class.java))
            waitUntil("previous service released") { PlaybackService.activeInstance == null }
            if (audioDirectory.exists()) check(audioDirectory.renameTo(backup))
            preferences.edit().remove("current").commit()
            graphSession.set(AppGraph, account)
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            main { future = MediaController.Builder(context,
                SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
            controller = future.get(10, TimeUnit.SECONDS)
            val service = requireNotNull(PlaybackService.activeInstance)
            fun field(name: String): Any = PlaybackService::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.get(service)!!
            player = field("player") as ExoPlayer
            cache = field("cache") as SimpleCache
            factory = field("cacheFactory") as CacheDataSource.Factory
            main { player.volume = 0f }
        }

        fun enableWholeQueue() = main {
            controller.sendCustomCommand(androidx.media3.session.SessionCommand(CACHE_REMAINING_QUEUE, android.os.Bundle.EMPTY), android.os.Bundle.EMPTY)
        }
        fun newUncachedItem() = Song(id = "99", title = "Replacement fixture", duration = 60)
            .toPlayableMediaItem(context, mobile, account)

        fun seedFourTracks() {
            val items = (0 until 12).map { Song(id = "$it", title = "Fixture $it", duration = 60)
                .toPlayableMediaItem(context, mobile, account) }
            for (item in items.take(4)) {
                CacheWriter(factory.createDataSource(), DataSpec.Builder()
                    .setUri(item.localConfiguration!!.uri).setKey(item.localConfiguration!!.customCacheKey)
                    .build(), null, null).cache()
            }
            server.online = false
            main { player.setMediaItems(items); player.prepare() }
            waitUntil("initial cached track ready") { mainValue { player.playbackState == Player.STATE_READY } }
        }

        fun cacheWriterStopped(): Boolean = PlaybackService::class.java.getDeclaredField("activeCacheWriter")
            .apply { isAccessible = true }.get(PlaybackService.activeInstance) == null

        fun fullyCached(index: Int): Boolean {
            val key = streamCacheKey(account.cacheNamespace, "$index", variant)
            val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            return length > 0 && cache.isCached(key, 0, length)
        }
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun <T> mainValue(action: () -> T): T {
            var result: T? = null
            main { result = action() }
            @Suppress("UNCHECKED_CAST") return result as T
        }
        fun waitUntil(message: String, timeout: Long = 10_000, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
            while (System.nanoTime() < deadline) {
                if (condition()) return
                Thread.sleep(50)
            }
            assertTrue(message, condition())
        }
        override fun close() {
            if (::controller.isInitialized) main {
                player.pause()
                player.stop()
                val service = PlaybackService.activeInstance
                val session = service?.let { PlaybackService::class.java.getDeclaredField("session")
                    .apply { isAccessible = true }.get(it) as androidx.media3.session.MediaLibraryService.MediaLibrarySession }
                if (session != null) {
                    service.removeSession(session)
                    session.release()
                }
                controller.release()
            }
            context.stopService(Intent(context, PlaybackService::class.java))
            waitUntil("fixture service released") { PlaybackService.activeInstance == null }
            preferences.edit().apply {
                if (oldSnapshot == null) remove("current") else putString("current", oldSnapshot)
            }.commit()
            graphSession.set(AppGraph, oldSession)
            audioDirectory.deleteRecursively()
            if (backup.exists()) check(backup.renameTo(audioDirectory))
            server.close()
            main { playbackField.set(application, PlaybackConnection(context)) }
        }
    }

    private class AudioServer : AutoCloseable {
        private val socket = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool()
        private val attempts = ConcurrentHashMap<Int, AtomicInteger>()
        @Volatile var online = true
        @Volatile var offlineStatus = 503
        val url = "http://127.0.0.1:${socket.localPort}"
        private val bytes = ByteBuffer.allocate(44 + 8_000 * 2 * 60).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(capacity() - 44)
        }.array()
        init { workers.execute {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                workers.execute { client.use(::serve) }
            }
        } }
        fun requests(index: Int) = attempts[index]?.get() ?: 0
        private fun serve(client: Socket) {
            client.soTimeout = 5_000
            val reader = client.getInputStream().bufferedReader()
            val request = reader.readLine() ?: return
            val headers = buildList {
                while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; add(line) }
            }
            val output = client.getOutputStream()
            val id = Regex("[?&]id=(\\d+)").find(request)?.groupValues?.get(1)?.toIntOrNull()
            if (id == null || !request.contains("/stream")) {
                val body = "{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\"}}".toByteArray()
                output.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                output.write(body); return
            }
            attempts.getOrPut(id) { AtomicInteger() }.incrementAndGet()
            if (!online) {
                output.write("HTTP/1.1 $offlineStatus Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            val range = headers.firstOrNull { it.startsWith("Range:", true) }
                ?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
            val start = range?.groupValues?.get(1)?.toInt() ?: 0
            val end = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
            val status = if (range == null) "200 OK" else "206 Partial Content"
            val contentRange = if (range == null) "" else "Content-Range: bytes $start-$end/${bytes.size}\r\n"
            output.write("HTTP/1.1 $status\r\nContent-Type: audio/wav\r\nContent-Length: ${end-start+1}\r\n${contentRange}Connection: close\r\n\r\n".toByteArray())
            output.write(bytes, start, end-start+1)
        }
        override fun close() { socket.close(); workers.shutdownNow() }
    }
}
