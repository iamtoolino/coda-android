package dev.coda.android.playback

import org.junit.Assert.*
import org.junit.Test

class ControllerTrustTest {
    private val autoCert = ControllerTrust.hex(ControllerTrust.AUTO_CERT)
    private val wrongCert = ByteArray(32) { 0x5a }

    /** A device where [pkg] is installed with [installed] as its only certificate. */
    private fun signer(pkg: String, installed: ByteArray, platform: Boolean = false) =
        ControllerTrust.approvedSigner(pkg, platformSigned = { platform }, hasCertificate = { it.contentEquals(installed) })

    private fun accepts(uid: Int = 10_200, trusted: Boolean = false, verified: Boolean = true, signer: Boolean) =
        ControllerTrust.accepts(uid, 10_100, trusted, verified, signer)

    @Test fun ownAppMediaTrustedAndAuthenticatedHostsAreAccepted() {
        assertTrue(accepts(uid = 10_100, signer = false))
        assertTrue(accepts(trusted = true, signer = false))
        assertTrue(accepts(signer = signer(ControllerTrust.AUTO, autoCert)))
        assertTrue(accepts(signer = signer("com.android.systemui", wrongCert, platform = true)))
        assertTrue(accepts(signer = signer("com.android.car.media", wrongCert, platform = true)))
    }

    @Test fun sameNamePackagesWithTheWrongSignatureAreRejected() {
        assertFalse(accepts(signer = signer(ControllerTrust.AUTO, wrongCert)))
        for (pkg in listOf("com.android.car.media", "com.google.android.carassistant", "com.android.systemui",
            "com.google.android.projection.gearhead.emulator", "android"))
            assertFalse(pkg, accepts(signer = signer(pkg, wrongCert)))
        // The Auto certificate does not vouch for other names, platform-signed or not on the list.
        assertFalse(accepts(signer = signer("com.android.car.media", autoCert)))
        assertFalse(accepts(signer = signer("com.example.evil", autoCert, platform = true)))
    }

    @Test fun unverifiedPackageNamesAreRejectedEvenWithAnApprovedSigner() {
        assertFalse(accepts(verified = false, signer = signer(ControllerTrust.AUTO, autoCert)))
    }
}
