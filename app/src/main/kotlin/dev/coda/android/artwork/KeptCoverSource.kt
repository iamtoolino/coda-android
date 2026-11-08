package dev.coda.android.artwork

import android.content.Context
import android.graphics.BitmapFactory
import dev.coda.android.net.HttpClientFactory
import dev.coda.android.session.Session
import dev.coda.core.cache.CoverStore
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Request

/**
 * The kept-cover store's [CoverStore.Source]: the picture cache's copy (same key as [CoverArtFetcher]), else
 * one request for the cover. Only verified images come back; errors are null (no URL is ever logged).
 */
class KeptCoverSource(context: Context, private val session: () -> Session) : CoverStore.Source {
    private val pictures = File(context.cacheDir, "artwork")

    override fun cached(id: String, size: Int): ByteArray? = runCatching {
        val config = session().client.configuration
        CoverArtKeyer.pictureFile(pictures, CoverArtKeyer.account(config.serverUrl, config.username), CoverArt(id, size))
            .takeIf { it.isFile }?.readBytes()?.takeIf(::isImage)
    }.getOrNull()

    /** Cancellable: a cancelled pass (Wi-Fi lost, the transfer job stopped, sign-out) cancels the request itself. */
    override suspend fun fetch(id: String, size: Int): ByteArray? = runCatching {
        val call = HttpClientFactory.client.newCall(Request.Builder().url(session().client.coverArtUrl(id, size)).build())
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) { continuation.resume(null) }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val bytes = runCatching { response.use { if (it.isSuccessful) it.body?.bytes()?.takeIf(::isImage) else null } }.getOrNull()
                    continuation.resume(bytes)
                }
            })
        }
    }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()

    private fun isImage(bytes: ByteArray): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }
}
