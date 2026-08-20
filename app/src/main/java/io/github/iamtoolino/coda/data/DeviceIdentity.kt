package io.github.iamtoolino.coda.data

import android.content.Context
import android.os.Build
import android.provider.Settings

internal fun queueClientName(context: Context): String {
    val configuredName = Settings.Global.getString(
        context.contentResolver,
        Settings.Global.DEVICE_NAME,
    )
    val deviceName = configuredName
        ?.normalizedDeviceName()
        ?.takeIf { it.isNotEmpty() }
        ?: Build.MODEL.normalizedDeviceName().ifEmpty { "Android" }
    return "Coda on $deviceName"
}

private fun String.normalizedDeviceName(): String =
    filterNot(Char::isISOControl)
        .trim()
        .replace(Regex("\\s+"), " ")
        .take(64)
