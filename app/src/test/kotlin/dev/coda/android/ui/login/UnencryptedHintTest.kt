package dev.coda.android.ui.login

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnencryptedHintTest {
    @Test fun httpAddressesShowTheHint() {
        assertTrue(isUnencryptedServer("http://music.example:4533"))
        assertTrue(isUnencryptedServer("  HTTP://100.64.0.1:4533"))
    }

    @Test fun httpsAndIncompleteAddressesDoNot() {
        assertFalse(isUnencryptedServer("https://music.example"))
        assertFalse(isUnencryptedServer(""))
        assertFalse(isUnencryptedServer("music.example"))
    }
}
