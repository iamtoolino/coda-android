package io.github.iamtoolino.coda.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal fun networkRetryDelayMs(attempt: Int, initialDelayMs: Long = 5_000): Long =
    (initialDelayMs * (1L shl attempt.coerceIn(0, 4))).coerceAtMost(60_000)

internal fun isRetryableHttpStatus(code: Int): Boolean = code == 408 || code == 429 || code in 500..599

/** Cache/storage, authentication, missing media, and decoder errors must not spin in a retry loop. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun isRetryableNetworkFailure(error: Throwable): Boolean {
    val causes = generateSequence(error) { it.cause }.take(16).toList()
    causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.let {
        return isRetryableHttpStatus(it.responseCode)
    }
    return causes.any {
        it is SocketTimeoutException || it is UnknownHostException || it is SocketException ||
            (it is HttpDataSource.HttpDataSourceException &&
                (it.reason == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    it.reason == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                    it.cause is EOFException)) ||
            (it is PlaybackException &&
                (it.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    it.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
    }
}
