package dev.coda.android.net

import okhttp3.Interceptor

/**
 * The server answered a request (API, cover, stream) with a 2xx. Every such answer feeds the shared
 * reachability state ([onSuccess]), which replaces the "a fresh answer stands in for the ping"; an HTML page is not
 * a server answer (Navidrome never answers its API, covers or streams with one; a captive portal does). A Subsonic
 * API answer counts only once it decodes ([onSubsonicOutcome]); the interceptor counts covers and streams.
 * Each outcome carries the reachability epoch its request started in ([issueInterceptor], [issue]); one from
 * before a session reset (another account or server, signed out) changes nothing.
 */
object ServerContact {
    /** The reachability epoch now ([dev.coda.core.connectivity.Reachability.epochNow]); 0 until [connect]. */
    @Volatile private var epoch: () -> Int = { 0 }
    /** Any server answer ends the shared backoff ([dev.coda.core.connectivity.ServerGate.reset]); And counts as a success. */
    @Volatile var onSuccess: ((issued: Int) -> Unit)? = null
    /** A Subsonic API request's outcome ([dev.coda.core.subsonic.SubsonicClient]'s hook): true, the server answered. */
    @Volatile var onSubsonicOutcome: ((answered: Boolean, issued: Int) -> Unit)? = null

    private class Issued(val epoch: Int)
    /** An application interceptor: stamps each call with the epoch it starts in (redirects and retries keep it). */
    val issueInterceptor = Interceptor { chain ->
        chain.proceed(chain.request().newBuilder().tag(Issued::class.java, Issued(epoch())).build())
    }
    val interceptor = Interceptor { chain ->
        chain.proceed(chain.request()).also {
            // Subsonic API answers count after decoding (onSubsonicOutcome), not on their headers.
            if (it.isSuccessful && !isApi(chain.request()) && !isHtml(it.header("Content-Type")))
                onSuccess?.invoke(chain.request().tag(Issued::class.java)?.epoch ?: epoch())
        }
    }
    /** The SubsonicClient's `issue` hook: an API request starts now and reports with this epoch. */
    fun issue(): (Boolean) -> Unit {
        val issued = epoch()
        return { answered -> onSubsonicOutcome?.invoke(answered, issued) }
    }
    /** The app's wiring (CodaApp): [gateReset] ends the backoff. */
    fun connect(reachability: dev.coda.core.connectivity.Reachability, gateReset: () -> Unit) {
        epoch = { reachability.epochNow }
        onSuccess = { issued -> if (reachability.succeeded(issued)) gateReset() }
        onSubsonicOutcome = { answered, issued ->
            if (!answered) reachability.failed(issued) else if (reachability.succeeded(issued)) gateReset()
        }
    }
    private fun isApi(request: okhttp3.Request) = request.tag(dev.coda.core.subsonic.SubsonicClient.ApiRequest::class.java) != null
    fun isHtml(contentType: String?) = contentType?.trim()?.startsWith("text/html", ignoreCase = true) == true
}
