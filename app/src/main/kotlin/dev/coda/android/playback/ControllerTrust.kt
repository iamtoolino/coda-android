package dev.coda.android.playback

import android.content.pm.PackageManager

/**
 * Who may browse and control the session: this app's UID, Media3/platform-trusted controllers
 * (`ControllerInfo.isTrusted`: system, MEDIA_CONTENT_CONTROL, enabled notification listeners), or a named host
 * whose publisher is proven by its signing certificate. A host name alone never authenticates its publisher.
 */
object ControllerTrust {
    const val AUTO = "com.google.android.projection.gearhead"
    private val platformHosts = setOf(
        "com.google.android.projection.gearhead.emulator", // Desktop Head Unit
        "com.android.car.media", "com.google.android.carassistant", // Automotive OS
        "com.android.systemui", "android", // media controls and notification
    )
    /** Android Auto release certificate, SHA-256 (apksigner on the Play-installed Auto 18 APK on emulator-5556). */
    internal const val AUTO_CERT = "1ca8dcc0bed3cbd872d2cb791200c0292ca9975768a82d676b8b424fb65b5295"
    private val pinned = mapOf(AUTO to listOf(AUTO_CERT))

    /**
     * True when [packageName] is an allowed host and its publisher is authenticated: a pinned certificate
     * ([hasCertificate], SHA-256 bytes) or the device platform certificate ([platformSigned]).
     */
    internal fun approvedSigner(packageName: String, platformSigned: () -> Boolean, hasCertificate: (ByteArray) -> Boolean): Boolean =
        when (val certs = pinned[packageName]) {
            null -> packageName in platformHosts && platformSigned()
            else -> certs.any { hasCertificate(hex(it)) } || platformSigned()
        }

    internal fun approvedSigner(pm: PackageManager, packageName: String): Boolean = runCatching {
        approvedSigner(packageName,
            platformSigned = { pm.checkSignatures("android", packageName) == PackageManager.SIGNATURE_MATCH },
            hasCertificate = { pm.hasSigningCertificate(packageName, it, PackageManager.CERT_INPUT_SHA256) })
    }.getOrDefault(false)

    fun accepts(uid: Int, ownUid: Int, mediaTrusted: Boolean, packageVerified: Boolean, approvedSigner: Boolean): Boolean =
        uid == ownUid || mediaTrusted || (packageVerified && approvedSigner)

    internal fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
