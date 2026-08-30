package io.github.iamtoolino.coda

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.imageLoader
import io.github.iamtoolino.coda.player.CarArtwork
import io.github.iamtoolino.coda.player.PlaybackConnection
import io.github.iamtoolino.coda.player.PlaybackService
import io.github.iamtoolino.coda.player.SharedPreferencesAudioCleanupLedger
import io.github.iamtoolino.coda.player.TransientAudioCleanupCoordinator
import io.github.iamtoolino.coda.ui.theme.clearArtworkColorCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath

class CodaApplication : Application() {
    private val cacheCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _artworkGeneration = MutableStateFlow(0L)
    val artworkGeneration: StateFlow<Long> = _artworkGeneration.asStateFlow()
    lateinit var playback: PlaybackConnection
        private set
    internal lateinit var transientAudioCleanup: TransientAudioCleanupCoordinator
        private set

    override fun onCreate() {
        super<Application>.onCreate()
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .diskCache {
                    DiskCache.Builder()
                        .directory(
                            context.cacheDir.resolve(ARTWORK_CACHE_DIRECTORY).toOkioPath(),
                        )
                        .maxSizeBytes(ARTWORK_DISK_CACHE_BYTES)
                        .build()
                }
                .build()
        }
        _artworkGeneration.value = artworkPreferences()
            .getLong(ARTWORK_GENERATION_KEY, 0L)
        AppGraph.initialize(this)
        transientAudioCleanup = TransientAudioCleanupCoordinator(
            SharedPreferencesAudioCleanupLedger(this),
            PlaybackService::clearTransientAudioCachesFor,
        )
        playback = PlaybackConnection(this)
    }

    internal fun requestTransientAudioCleanup(namespace: String) {
        transientAudioCleanup.request(namespace)
    }

    fun clearArtworkCaches(namespace: String) {
        if (namespace.isBlank()) return
        imageLoader.memoryCache?.clear()
        clearArtworkColorCache()
        cacheCleanupScope.launch {
            imageLoader.diskCache?.clear()
            CarArtwork.clear(this@CodaApplication, namespace)
        }
    }

    suspend fun refreshArtworkCaches(namespace: String) {
        if (namespace.isBlank() || namespace != AppGraph.cacheNamespace) return
        withContext(Dispatchers.IO) {
            imageLoader.diskCache?.clear()
            CarArtwork.clear(this@CodaApplication, namespace)
            if (namespace != AppGraph.cacheNamespace) return@withContext
            val nextGeneration = _artworkGeneration.value + 1
            val generationPersisted = artworkPreferences().edit()
                .putLong(ARTWORK_GENERATION_KEY, nextGeneration)
                .commit()
            check(generationPersisted) { "Could not persist artwork cache generation" }
            clearArtworkColorCache()
            imageLoader.memoryCache?.clear()
            _artworkGeneration.value = nextGeneration
        }
    }

    private fun artworkPreferences() = getSharedPreferences(
        ARTWORK_PREFERENCES,
        MODE_PRIVATE,
    )

    private companion object {
        const val ARTWORK_PREFERENCES = "artwork_cache"
        const val ARTWORK_GENERATION_KEY = "generation"
        const val ARTWORK_CACHE_DIRECTORY = "phone-artwork"
        const val ARTWORK_DISK_CACHE_BYTES = 10L * 1024L * 1024L * 1024L
    }
}
