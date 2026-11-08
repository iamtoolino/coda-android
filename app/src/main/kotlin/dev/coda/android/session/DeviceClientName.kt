package dev.coda.android.session

import android.content.Context
import android.os.Build
import android.provider.Settings
import dev.coda.core.subsonic.SubsonicConfig

/**
 * The Subsonic client name and queue-handoff identity (architecture §4): "Coda on " + the device name the user set
 * (`Settings.Global.DEVICE_NAME`), else the model, else "Android". Computed once per process ([init] in the app's onCreate).
 */
object DeviceClientName {
    @Volatile private var name: String? = null

    fun init(context: Context) {
        val configured = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        name = of(configured, Build.MODEL)
    }

    fun get(): String = name ?: of(null, Build.MODEL)

    internal fun of(deviceName: String?, model: String?): String =
        SubsonicConfig.makeClientName(deviceName?.trim()?.takeIf { it.isNotEmpty() } ?: model)
}
