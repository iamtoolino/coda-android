package dev.coda.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The client name is the configured device name, then the model, then "Android" (architecture §4). */
class DeviceClientNameTest {
    @Test fun twoNamedPhonesOfOneModelHaveTwoClientNames() {
        val a = DeviceClientName.of(deviceName = "Anna's phone", model = "Pixel 9")
        val b = DeviceClientName.of(deviceName = "Ben's phone", model = "Pixel 9")
        assertNotEquals(a, b)
        assertEquals("Coda on Anna's phone", a)
    }

    @Test fun withoutADeviceNameTheModelThenAndroid() {
        assertEquals("Coda on Pixel 9", DeviceClientName.of(deviceName = "  ", model = "Pixel 9"))
        assertEquals("Coda on Pixel 9", DeviceClientName.of(deviceName = null, model = "Pixel 9"))
        assertEquals("Coda on Android", DeviceClientName.of(deviceName = null, model = null))
    }
}
