package dev.coda.android.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidConnectivityTest {
    /** Online is INTERNET without a detected captive portal; VALIDATED is not required (LAN-only Wi-Fi). */
    @Test fun onlineIsInternetWithoutACaptivePortal() {
        assertTrue("internet (validated or not)", AndroidConnectivity.usable(internet = true, captivePortal = false))
        assertFalse("captive portal", AndroidConnectivity.usable(internet = true, captivePortal = true))
        assertFalse("no internet capability", AndroidConnectivity.usable(internet = false, captivePortal = false))
        assertFalse("no internet capability, portal", AndroidConnectivity.usable(internet = false, captivePortal = true))
    }
}
