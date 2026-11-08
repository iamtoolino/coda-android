package dev.coda.android.net

import dev.coda.core.connectivity.Reachability
import dev.coda.core.connectivity.ServerGate

/** Signing out, or signing in as another user or server: the old server's answers say nothing about the next one. */
internal object AccountSwitch {
    /** [lastAccount] was signed in and the session now is signed out or another account. */
    fun leftAccount(lastAccount: String?, account: String?, signedOut: Boolean) =
        lastAccount != null && (signedOut || (account != null && account != lastAccount))

    /** The reachability and the background calls' backoff (else the new account's calls stay skipped up to 10 min). */
    fun forgetServer(reachability: Reachability, gate: ServerGate) {
        reachability.reset()
        gate.reset()
    }
}
