package dev.coda.core.subsonic

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * A redirect Coda will not follow; the message names no URL (it may carry the authentication query). A
 * ProtocolException, so OkHttp does not retry the call.
 */
class RedirectRefused : java.net.ProtocolException("The server redirected to an address Coda does not follow.")

/**
 * Upgrade-only redirects for every authenticated request (API, artwork, streams). Subsonic authentication travels in
 * the query or the form body, which OkHttp's cross-host `Authorization` stripping does not protect, so each hop's
 * redirect target is checked before OkHttp follows it: same host only, no user info, never from https to http. A same-host
 * http→https upgrade is followed with normal certificate validation. A refused redirect is closed and fails the call.
 */
object RedirectGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isRedirect) return response
        val hop = response.request.url
        val target = response.header("Location")?.let { hop.resolve(it) }
        val origin = chain.call().request().url
        if (target == null || target.username.isNotEmpty() || target.password.isNotEmpty() ||
            !allowed(origin.host, hop.isHttps, target.host, target.isHttps)
        ) {
            response.close()
            throw RedirectRefused()
        }
        return response
    }

    /** Same host as the original request, and no step from https down to http. */
    fun allowed(originHost: String, hopIsHttps: Boolean, targetHost: String, targetIsHttps: Boolean): Boolean =
        targetHost.equals(originHost, ignoreCase = true) && (targetIsHttps || !hopIsHttps)

    /** [client] with the guard and redirects on (the guard decides which ones). */
    fun install(client: OkHttpClient): OkHttpClient = client.newBuilder().apply {
        if (networkInterceptors().none { it === RedirectGuard }) addNetworkInterceptor(RedirectGuard)
    }.followRedirects(true).followSslRedirects(true).build()
}
