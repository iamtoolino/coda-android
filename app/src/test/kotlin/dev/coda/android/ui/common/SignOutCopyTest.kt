package dev.coda.android.ui.common

import dev.coda.core.cache.Lifetime
import org.junit.Assert.assertEquals
import org.junit.Test

/** The sign-out dialog adds its note only for kept music that would otherwise stay ("As long as possible"). */
class SignOutCopyTest {
    @Test fun unchangedWithoutKeptMusic() {
        assertEquals(emptyList<String>(), SignOutCopy.extraLines(keptOffline = false, kept = Lifetime.MAX, serverReachable = true))
        // The unreachable note does not depend on kept music.
        assertEquals(listOf(SignOutCopy.OFFLINE), SignOutCopy.extraLines(keptOffline = false, kept = Lifetime.MAX, serverReachable = false))
    }

    @Test fun unchangedWhenKeptMusicHasADate() {
        assertEquals(emptyList<String>(), SignOutCopy.extraLines(keptOffline = true, kept = Lifetime.MONTH, serverReachable = true))
        assertEquals(listOf(SignOutCopy.OFFLINE), SignOutCopy.extraLines(keptOffline = true, kept = Lifetime.MONTH, serverReachable = false))
    }

    @Test fun keptMusicAsLongAsPossibleGetsTheNote() {
        assertEquals(listOf(SignOutCopy.KEPT), SignOutCopy.extraLines(keptOffline = true, kept = Lifetime.MAX, serverReachable = true))
    }

    @Test fun unreachableServerAddsTheSignInNote() {
        assertEquals(listOf(SignOutCopy.KEPT, SignOutCopy.OFFLINE), SignOutCopy.extraLines(keptOffline = true, kept = Lifetime.MAX, serverReachable = false))
    }
}
