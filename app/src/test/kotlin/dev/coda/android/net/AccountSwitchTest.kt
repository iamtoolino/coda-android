package dev.coda.android.net

import dev.coda.core.connectivity.Reachability
import dev.coda.core.connectivity.ServerGate
import dev.coda.core.subsonic.SubsonicError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * At an account switch or sign-out the old server's backoff ends with its reachability
 * (CodaApp's session collector), so the new account's background calls are not skipped for up to 10 min.
 */
class AccountSwitchTest {
    @Test fun aBackoffFromTheOldAccountDoesNotCarryOver() = runTest {
        val r = Reachability(backgroundScope, { testScheduler.currentTime }, probe = { throw SubsonicError.Network(java.io.IOException("down")) })
        val gate = ServerGate(r, { testScheduler.currentTime })
        gate.failed(); gate.failed()
        assertFalse("old account's backoff running", gate.isOpen())
        assertTrue(AccountSwitch.leftAccount("old", "new", signedOut = false))
        AccountSwitch.forgetServer(r, gate)
        assertTrue("the new account starts without the old backoff", gate.isOpen())
        assertEquals(0L, gate.backoffMs)
    }

    @Test fun onlyALeftAccountCounts() {
        assertTrue(AccountSwitch.leftAccount("old", null, signedOut = true))
        assertFalse("same account again", AccountSwitch.leftAccount("old", "old", signedOut = false))
        assertFalse("first sign-in", AccountSwitch.leftAccount(null, "new", signedOut = false))
        assertFalse("not yet known (needs login)", AccountSwitch.leftAccount("old", null, signedOut = false))
    }
}
