package io.github.iamtoolino.coda.artwork

import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import java.io.IOException
import kotlinx.coroutines.delay

internal class ArtworkRetryInterceptor(
    private val retryDelaysMillis: List<Long> = listOf(250L, 750L),
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val isNetworkRequest = chain.request.data.toString().let { data ->
            data.startsWith("http://") || data.startsWith("https://")
        }
        if (!isNetworkRequest) return chain.proceed()

        return retryArtworkRequest(
            retryDelaysMillis = retryDelaysMillis,
            isRetryable = { result ->
                result is ErrorResult && result.throwable.hasIOExceptionCause()
            },
            delayRequest = { delay(it) },
            request = chain::proceed,
        )
    }
}

internal suspend fun <T> retryArtworkRequest(
    retryDelaysMillis: List<Long>,
    isRetryable: (T) -> Boolean,
    delayRequest: suspend (Long) -> Unit,
    request: suspend () -> T,
): T {
    var result = request()
    retryDelaysMillis.forEach { retryDelay ->
        if (!isRetryable(result)) return result
        delayRequest(retryDelay)
        result = request()
    }
    return result
}

private fun Throwable.hasIOExceptionCause(): Boolean {
    var current: Throwable? = this
    val visited = mutableSetOf<Throwable>()
    while (current != null && visited.add(current)) {
        if (current is IOException) return true
        current = current.cause
    }
    return false
}
