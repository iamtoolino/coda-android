package io.github.iamtoolino.coda.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerAccessTest {
    @Test
    fun `own app receives full library access`() {
        assertEquals(
            ControllerAccess.FULL_LIBRARY,
            controllerAccess(
                isOwnApp = true,
                isAutoCompanion = false,
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
                isMediaNotification = true,
                isTrusted = false,
            ),
        )
        assertEquals(
            ControllerAccess.TRANSPORT,
            controllerAccess(
                isOwnApp = false,
                isAutoCompanion = false,
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
                isMediaNotification = false,
                isTrusted = false,
            ),
        )
    }
}
