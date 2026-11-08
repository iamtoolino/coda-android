package dev.coda.android.net

import okhttp3.OkHttpClient

/** One pool for API, artwork and playback. No request/response logging interceptors. */
object HttpClientFactory {
    // Upgrade-only redirects for API, artwork and streams (their URLs carry the authentication query).
    val client: OkHttpClient by lazy { dev.coda.core.subsonic.RedirectGuard.install(OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        // Request/connection/byte counters in debug and preview builds (null, nothing installed, in release).
        // The epoch each call starts in, for the interceptor below (a reply from before a session reset counts not).
        .addInterceptor(ServerContact.issueInterceptor)
        .addNetworkInterceptor(ServerContact.interceptor)
        // Request starts and body ends drive the screen-off idle-connection close (IdleConnectionCloser).
        .addNetworkInterceptor(ConnectionActivity.interceptor)
        .apply {
            dev.coda.android.diag.BatteryLog.httpEvents()?.let { eventListenerFactory(it) }
            dev.coda.android.diag.BatteryLog.httpInterceptor()?.let { addNetworkInterceptor(it) }
        }.build()) }
}
