package dev.coda.android

import dev.coda.android.net.Redactor
import org.junit.Assert.*
import org.junit.Test

class RedactorTest {
    @Test fun removesAuthorityAndAuthenticationParameters() {
        val result = Redactor.redact("GET https://user:secret@private.example:4533/rest/ping?u=person&t=token&s=salt&p=secret&f=json")
        listOf("private.example", "person", "token", "salt", "secret", "u=", "t=", "s=", "p=").forEach {
            assertFalse(result.contains(it))
        }
        assertTrue(result.contains("<server>"))
        assertTrue(result.contains("f=json"))
    }
    @Test fun removesConfiguredHostFromDnsConnectAndTlsErrors() {
        val messages = listOf(
            "Unable to resolve host \"private.example\": No address associated with hostname",
            "Failed to connect to PRIVATE.EXAMPLE/192.0.2.10:4533",
            "Hostname private.example not verified: certificate failed",
            "Failed to connect to /192.0.2.10:4533",
        )
        messages.forEach { message ->
            val result = Redactor.redact(message, "https://private.example:4533/music", setOf("192.0.2.10"))
            assertFalse(result.contains("private.example", ignoreCase = true))
            assertFalse(result.contains("192.0.2.10"))
            assertTrue(result.contains("<server>"))
        }
    }

    @Test fun removesConfiguredIpLiteralsAndResolvedIpv6Addresses() {
        listOf(
            "https://192.0.2.10" to "Failed to connect to /192.0.2.10:443",
            "https://[2001:db8::10]" to "Hostname [2001:db8::10] not verified",
        ).forEach { (server, message) ->
            val result = Redactor.redact(message, server)
            assertTrue(result.contains("<server>"))
            assertFalse(result.contains("192.0.2.10"))
            assertFalse(result.contains("2001:db8::10"))
        }
        assertEquals("connect [<server>]:443", Redactor.redact(
            "connect [2001:db8::20]:443", "https://private.example", setOf("2001:db8::20")))
    }
}
