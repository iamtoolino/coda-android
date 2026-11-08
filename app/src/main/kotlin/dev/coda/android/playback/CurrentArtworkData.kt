package dev.coda.android.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Cover bytes for the current queue entry and its neighbours, held in memory so the session can publish them
 * as `MediaMetadata.artworkData` of the current item. SystemUI's media controls and the Android Auto
 * now-playing screen read the framework session's metadata without ever connecting through `onConnect`, so
 * they never get a URI grant on the non-exported artwork provider; a bitmap needs no provider at all.
 * Main-thread only: [track] and [bytes] are called from the player, [load] runs wherever the caller dispatches it.
 */
class CurrentArtworkData(
    private val scope: CoroutineScope,
    private val load: suspend (String) -> ByteArray?,
    private val onLoaded: () -> Unit,
) {
    private val loaded = HashMap<String, ByteArray>()
    private val loading = HashMap<String, Job>()

    fun bytes(coverArtId: String?): ByteArray? = coverArtId?.let { loaded[it] }

    /** Keep exactly [wanted]: drop (and cancel) everything else, start loading what is missing. */
    fun track(wanted: Collection<String>) {
        loaded.keys.retainAll(wanted.toSet())
        loading.keys.filter { it !in wanted }.forEach { loading.remove(it)?.cancel() }
        wanted.filter { it !in loaded && it !in loading }.forEach { id ->
            // An immediate dispatcher may finish the job before launch returns: only a live job counts as in flight.
            val job = scope.launch {
                val data = try { load(id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                loading.remove(id)
                if (data != null && data.size in 1..MAX_BYTES) { loaded[id] = data; onLoaded() }
            }
            if (job.isActive) loading[id] = job
        }
    }

    companion object {
        /** Binder transactions carry this with the player state; session covers are requested at 1024 px and shrunk locally when still larger (SessionCover). */
        const val MAX_BYTES = 512 * 1024
    }
}
