package dev.coda.android.cache

import dev.coda.core.testing.memoryTrackCache
import dev.coda.core.cache.TrackCache
import dev.coda.core.cache.TrackKey
import dev.coda.core.connectivity.NetworkState
import dev.coda.core.playback.QueueEntry
import dev.coda.core.playback.StreamFormat
import dev.coda.core.testing.FakeFreeSpace
import dev.coda.core.testing.FakeSubsonicServer
import dev.coda.core.testing.SyntheticLibrary
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

/** Read-ahead window on a real loopback server and cache. */
class QueueCacheWorkerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val wifi = NetworkState(available = true, unmetered = true)
    private val mobile = NetworkState(available = true, unmetered = false, cellular = true)
    private val GiB = 1L shl 30

    private inner class Fixture(server: FakeSubsonicServer, count: Int, initial: NetworkState, ahead: Int = 2) {
        val ids = (1..count).map { "song-$it" }
        val queue = ids.map { QueueEntry(it, formatName = "FLAC", bitRate = 900, durationMs = 2_000) }
        val network = AtomicReference(initial)
        val folder = temporary.newFolder()
        val space = FakeFreeSpace(folder, 64 * GiB)
        val prefetcher = Prefetcher(OkHttpClient())
        val cache = memoryTrackCache(folder, space)
        val worker = QueueCacheWorker(prefetcher, { network.get() }, { ahead }) { source ->
            server.authenticatedUrl("stream") + "&id=${source.songId}&format=" + if (source.format == StreamFormat.RAW) "raw" else "opus"
        }
        init {
            Logger.getLogger("okhttp3.mockwebserver.MockWebServer").level = Level.OFF
            server.streamAliases = ids.associateWith { "track-1" }
        }
        val downloaded = mutableListOf<String>()
        suspend fun cycle(current: Int?) {
            cache.pin(worker.pins(queue, current))
            worker.cycle(cache, queue, current, downloaded = { downloaded += it }) { true }
        }
    }

    @Test fun theWindowFetchesOriginalsOnWifiAndKeepsThemAfterLeavingTheWindow() = runBlocking {
        FakeSubsonicServer().use { server ->
            val f = Fixture(server, 6, wifi)
            f.cycle(0)
            assertEquals(f.ids.take(3), f.ids.filter { f.cache.contains(it) })
            assertTrue(f.ids.take(3).all { f.cache.kind(it) == StreamFormat.RAW })
            f.cycle(3)
            assertEquals("Read-ahead files are no longer deleted when they leave the window", f.ids, f.ids.filter { f.cache.contains(it) })
            assertEquals(6, server.streamRequests.size)
            f.cycle(3)
            assertEquals("Nothing is fetched twice", 6, server.streamRequests.size)
            assertEquals("BAT-3: each stored download is reported once", f.ids, f.downloaded)
        }
    }

    @Test fun mobileDataFetchesOpusAndANearlyFullPhoneOnWifiStillFetchesOriginals() = runBlocking {
        FakeSubsonicServer().use { server ->
            val f = Fixture(server, 6, mobile)
            f.cycle(0)
            assertTrue(f.ids.take(3).all { f.cache.kind(it) == StreamFormat.OPUS_128 })
            f.network.set(wifi)
            f.space.setFree(6 * GiB)
            f.cycle(3)
            // No lossy mode; free space never changes the format.
            assertTrue("Originals on Wi-Fi", f.ids.drop(3).all { f.cache.kind(it) == StreamFormat.RAW })
            assertTrue(server.streamRequests.take(3).all { it.format == "opus" })
        }
    }

    @Test fun onWifiOpusCopiesInTheWindowAreReplacedByOriginalsOnlyThere() = runBlocking {
        FakeSubsonicServer().use { server ->
            val f = Fixture(server, 6, mobile)
            f.cycle(0)
            f.cycle(3)
            assertTrue(f.ids.all { f.cache.kind(it) == StreamFormat.OPUS_128 })
            server.streamRequests.clear()
            f.network.set(wifi)
            f.cycle(0)
            // The window (previous song, current and next two) is replaced; the rest of the cache is not swept.
            assertEquals(listOf("song-1", "song-2", "song-3", "song-4"), server.streamRequests.map { it.id }.sorted())
            assertTrue(server.streamRequests.all { it.format == "raw" })
            assertEquals(listOf(StreamFormat.RAW, StreamFormat.RAW, StreamFormat.RAW, StreamFormat.RAW, StreamFormat.OPUS_128, StreamFormat.OPUS_128),
                f.ids.map { f.cache.kind(it) })
            assertTrue("The Opus copies went", f.folder.listFiles()!!.count { it.name.endsWith(".opus") } == 2)
        }
    }

    @Test fun aNearlyFullPhoneOrMobileDataNeverDowngradesWhatIsCached() = runBlocking {
        FakeSubsonicServer().use { server ->
            val f = Fixture(server, 3, wifi)
            f.cycle(0)
            f.space.setFree(6 * GiB)
            server.streamRequests.clear()
            f.cycle(0)
            f.network.set(mobile)
            f.cycle(0)
            assertTrue(server.streamRequests.isEmpty())
            assertTrue(f.ids.all { f.cache.kind(it) == StreamFormat.RAW })
        }
    }

    @Test fun transientNullCurrentPreservesTheActualPreviousSong() {
        FakeSubsonicServer().use { server ->
            val f = Fixture(server, 10, wifi)
            f.worker.pins(f.queue, 8)
            f.worker.pins(f.queue, null)
            assertTrue(f.worker.pins(f.queue, 0).contains("song-9"))
        }
    }

    @Test fun legacyFilesAreAdoptedWhenTheirSongEntersTheWindow() = runBlocking {
        FakeSubsonicServer().use { server ->
            val f = Fixture(server, 3, wifi)
            val legacy = java.io.File(f.folder, "legacy").apply { mkdirs() }
            java.io.File(legacy, TrackKey("song-1", StreamFormat.RAW).fileName).writeBytes(SyntheticLibrary.albums.first().tracks.first().bytes("raw"))
            val cache = memoryTrackCache(f.folder, f.space)
            f.cache.pin(emptyList())
            cache.pin(f.worker.pins(f.queue, 0))
            f.worker.cycle(cache, f.queue, 0) { true }
            assertEquals(StreamFormat.RAW, cache.kind("song-1"))
            assertEquals(listOf("song-2", "song-3"), server.streamRequests.map { it.id })
        }
    }
}
