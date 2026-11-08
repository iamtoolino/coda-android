package dev.coda.android.playback

import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi

/**
 * Media3 logs source exceptions before our failure mapper runs. Free-form messages, tags and
 * throwable chains can all contain authenticated URLs, TLS hostnames or media metadata. Keep
 * only severity: a URL regexp cannot safely redact arbitrary decoder/network diagnostics.
 */
@UnstableApi
internal class PrivateMedia3Logger(private val sink: Log.Logger = Log.Logger.DEFAULT) : Log.Logger {
    override fun d(tag: String, message: String, throwable: Throwable?) = sink.d(TAG, MESSAGE, null)
    override fun i(tag: String, message: String, throwable: Throwable?) = sink.i(TAG, MESSAGE, null)
    override fun w(tag: String, message: String, throwable: Throwable?) = sink.w(TAG, MESSAGE, null)
    override fun e(tag: String, message: String, throwable: Throwable?) = sink.e(TAG, MESSAGE, null)

    companion object {
        private const val TAG = "CodaMedia3"
        private const val MESSAGE = "Media3 diagnostic redacted"
        fun install() { Log.setLogger(PrivateMedia3Logger()) }
    }
}
