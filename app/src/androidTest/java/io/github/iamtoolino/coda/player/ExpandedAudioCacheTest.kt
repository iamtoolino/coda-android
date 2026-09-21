package io.github.iamtoolino.coda.player

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class ExpandedAudioCacheTest {
    @Test fun preparedQueueReadsPastNormalWindowAfterCacheReopenWithoutSources() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "expanded-cache-fixture-${System.nanoTime()}").apply { mkdirs() }
        val bytes = ByteArray(8192) { (it % 127).toByte() }
        val sources = (0 until 8).map { index ->
            File(root, "source-$index").apply { writeBytes(bytes) }
        }
        val specs = sources.mapIndexed { index, file ->
            DataSpec.Builder().setUri(file.toURI().toString()).setKey("fixture-$index").build()
        }
        val database = StandaloneDatabaseProvider(context)
        var cache = SimpleCache(File(root, "cache"), NoOpCacheEvictor(), database)
        try {
            val factory = CacheDataSource.Factory().setCache(cache)
                .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context))
            for (index in audioCacheWindowIndices(8, 0, cacheWholeQueue = true)) {
                CacheWriter(factory.createDataSource(), specs[index], null, null).cache()
            }
            sources.forEach { it.delete() }
            cache.release()
            cache = SimpleCache(File(root, "cache"), NoOpCacheEvictor(), database)
            // No upstream at all: every byte must come from the reopened cache.
            for (index in 0 until 8) {
                val source = CacheDataSource.Factory().setCache(cache).createDataSource()
                try {
                    assertEquals(bytes.size.toLong(), source.open(specs[index]))
                    val restored = ByteArray(bytes.size)
                    var position = 0
                    while (position < restored.size) {
                        val count = source.read(restored, position, restored.size - position)
                        check(count > 0)
                        position += count
                    }
                    assertArrayEquals(bytes, restored)
                } finally { source.close() }
            }
            val keep = audioCacheWindowIndices(8, 4).map { "fixture-$it" }.toSet()
            cache.keys.filterNot(keep::contains).forEach(cache::removeResource)
            assertEquals(keep, cache.keys)
        } finally {
            cache.release()
            database.close()
            root.deleteRecursively()
        }
    }
}
