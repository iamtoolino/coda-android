@file:Suppress("NOTHING_TO_INLINE", "UNUSED_PARAMETER")

package dev.coda.android.diag

import android.content.Context
import androidx.compose.runtime.Composable

/**
 * Release builds without diagnostics compile this no-op instead of app/src/batteryLog (see app/build.gradle.kts).
 * Everything is inline and empty, so no call, class or string of the battery log reaches the APK.
 */
object BatteryLog {
    const val ENABLED = false
    inline fun start(context: Context) {}
    inline fun count(counter: Int, n: Long = 1) {}
    inline fun lock(holder: Int, held: Boolean) {}
    inline fun playback(state: Int) {}
    inline fun event(event: Int, value: Long = 0) {}
    inline fun reach(line: String) {}
    inline fun httpEvents(): okhttp3.EventListener.Factory? = null
    inline fun httpInterceptor(): okhttp3.Interceptor? = null
    inline fun loadError(error: Throwable) {}
}

@Composable
inline fun BatteryLogSettings() {}
