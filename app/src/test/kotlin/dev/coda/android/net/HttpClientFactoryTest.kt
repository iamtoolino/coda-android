package dev.coda.android.net

import dev.coda.core.subsonic.RedirectGuard
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpClientFactoryTest {
    /** API, artwork and stream requests share the upgrade-only redirect guard (core RedirectGuardTest). */
    @Test fun sharedClientHasTheRedirectGuard() {
        assertTrue(HttpClientFactory.client.networkInterceptors.any { it === RedirectGuard })
        assertTrue(HttpClientFactory.client.followRedirects && HttpClientFactory.client.followSslRedirects)
    }
}
