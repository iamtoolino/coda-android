package io.github.iamtoolino.coda.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerAccessTest {
    @Test
    fun `recommendation broker requires the exact trusted Google package`() {
        assertTrue(
            isRecommendationBroker(
                packageName = "com.google.android.googlequicksearchbox",
                isTrusted = true,
            ),
        )
        assertFalse(
            isRecommendationBroker(
                packageName = "com.google.android.googlequicksearchbox",
                isTrusted = false,
            ),
        )
        assertFalse(
            isRecommendationBroker(
                packageName = "example.untrusted.controller",
                isTrusted = true,
            ),
        )
    }

    @Test
    fun `own app receives full library access`() {
        assertEquals(
            ControllerAccess.FULL_LIBRARY,
            controllerAccess(
                isOwnApp = true,
                isAutoCompanion = false,
                isRecommendationBroker = false,
                isMediaNotification = false,
                isTrusted = true,
            ),
        )
    }

    @Test
    fun `Android Auto receives full library access`() {
        assertEquals(
            ControllerAccess.FULL_LIBRARY,
            controllerAccess(
                isOwnApp = false,
                isAutoCompanion = true,
                isRecommendationBroker = false,
                isMediaNotification = false,
                isTrusted = true,
            ),
        )
    }

    @Test
    fun `trusted Android Auto recommendation broker receives library and transport access`() {
        assertEquals(
            ControllerAccess.LIBRARY_TRANSPORT,
            controllerAccess(
                isOwnApp = false,
                isAutoCompanion = false,
                isRecommendationBroker = true,
                isMediaNotification = false,
                isTrusted = true,
            ),
        )
    }

    @Test
    fun `notification and trusted controllers receive transport access`() {
        assertEquals(
            ControllerAccess.TRANSPORT,
            controllerAccess(
                isOwnApp = false,
                isAutoCompanion = false,
                isRecommendationBroker = false,
                isMediaNotification = true,
                isTrusted = false,
            ),
        )
        assertEquals(
            ControllerAccess.TRANSPORT,
            controllerAccess(
                isOwnApp = false,
                isAutoCompanion = false,
                isRecommendationBroker = false,
                isMediaNotification = false,
                isTrusted = true,
            ),
        )
    }

    @Test
    fun `untrusted controller is rejected`() {
        assertEquals(
            ControllerAccess.REJECTED,
            controllerAccess(
                isOwnApp = false,
                isAutoCompanion = false,
                isRecommendationBroker = false,
                isMediaNotification = false,
                isTrusted = false,
            ),
        )
    }
}
