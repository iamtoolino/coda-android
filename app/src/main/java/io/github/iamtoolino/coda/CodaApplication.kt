package io.github.iamtoolino.coda

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.imageLoader
import io.github.iamtoolino.coda.player.CarArtwork
import io.github.iamtoolino.coda.player.PlaybackConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CodaApplication : Application(), DefaultLifecycleObserver {
    private val cacheCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var playback: PlaybackConnection
        private set

    override fun onCreate() {
        super<Application>.onCreate()
        AppGraph.initialize(this)
        playback = PlaybackConnection(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        playback.saveQueueToServer()
    }

    fun clearArtworkCaches(namespace: String) {
        if (namespace.isBlank()) return
        imageLoader.memoryCache?.clear()
        cacheCleanupScope.launch {
            imageLoader.diskCache?.clear()
            CarArtwork.clear(this@CodaApplication, namespace)
        }
    }
}
