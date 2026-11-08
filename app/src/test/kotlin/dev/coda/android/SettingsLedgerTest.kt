package dev.coda.android

import org.junit.Assert.*
import org.junit.Test

/** The record/restore logic behind the device tests' SystemSettings gate, against a fake device with failure injection. */
class SettingsLedgerTest {
    /** A device with `settings`, airplane mode and radios. [failing] commands print an error and change nothing; [ignored] ones succeed silently but change nothing. */
    private class FakeDevice(vararg initial: Pair<String, String?>) {
        val values = LinkedHashMap<String, String?>().apply { initial.forEach { (k, v) -> this[k] = v } }
        val failing = mutableSetOf<String>()
        val ignored = mutableSetOf<String>()
        val unreadable = mutableSetOf<String>()
        val writes = mutableListOf<String>()
        fun read(command: String): String {
            if (command == "wm size") return "Physical size: 1080x2400" + (values["wm/size"]?.let { "\nOverride size: $it" } ?: "")
            val (_, _, ns, key) = command.split(" ").let { it + listOf("", "", "", "") }
            return if ("$ns/$key" in unreadable) "Exception occurred while executing 'get'" else values["$ns/$key"] ?: "null"
        }
        fun write(command: String): String {
            writes += command
            if (failing.any { command.startsWith(it) }) return "Exception occurred while executing: $command"
            if (ignored.any { command.startsWith(it) }) return ""
            val w = command.split(" ")
            when {
                w[0] == "wm" && w[1] == "size" -> if (w[2] == "reset") values.remove("wm/size") else values["wm/size"] = w[2]
                w[0] == "settings" && w[1] == "put" -> values["${w[2]}/${w[3]}"] = w[4]
                w[0] == "settings" && w[1] == "delete" -> values.remove("${w[2]}/${w[3]}")
                command.startsWith("cmd connectivity airplane-mode") -> values["global/airplane_mode_on"] = if (w[3] == "enable") "1" else "0"
                command.startsWith("svc wifi") -> values["global/wifi_on"] = if (w[2] == "enable") "1" else "0"
                command.startsWith("svc data") -> values["global/mobile_data"] = if (w[2] == "enable") "1" else "0"
            }
            return ""
        }
        fun ledger() = SettingsLedger(::read, ::write, settle = {}, attempts = 3)
    }

    private fun get(d: FakeDevice, key: String) = d.values[key]

    @Test fun displaySizeIsResetWhenThereWasNoOverrideAndPutBackOtherwise() {
        val d = FakeDevice()
        val l = d.ledger()
        l.setDisplaySize("1080x1920")
        l.setDisplaySize("1080x1800") // the first original wins
        assertEquals("1080x1800", get(d, "wm/size"))
        l.restoreAll()
        assertFalse("wm/size" in d.values)
        assertEquals("wm size reset", d.writes.last())
        val d2 = FakeDevice("wm/size" to "900x1600")
        val l2 = d2.ledger()
        l2.setDisplaySize("1080x1920")
        l2.restoreAll()
        assertEquals("900x1600", get(d2, "wm/size"))
        assertThrows(IllegalArgumentException::class.java) { l2.setDisplaySize("1080x1920; reboot") }
    }

    @Test fun putRestoresTheOldValue() {
        val d = FakeDevice("global/animator_duration_scale" to "1.0")
        val l = d.ledger()
        l.put("global", "animator_duration_scale", "0")
        assertEquals("0", get(d, "global/animator_duration_scale"))
        l.restoreAll()
        assertEquals("1.0", get(d, "global/animator_duration_scale"))
        assertTrue(l.pending.isEmpty())
    }

    @Test fun anAbsentKeyIsDeletedAgainNotWrittenWithAMadeUpValue() {
        val d = FakeDevice()
        val l = d.ledger()
        l.put("system", "font_scale", "2.0")
        l.restoreAll()
        assertFalse("system/font_scale" in d.values)
        assertTrue(d.writes.last().startsWith("settings delete system font_scale"))
    }

    @Test fun theFirstOriginalWinsWhenAKeyIsChangedTwice() {
        val d = FakeDevice("system/font_scale" to "1.3")
        val l = d.ledger()
        l.put("system", "font_scale", "2.0"); l.put("system", "font_scale", "0.85")
        l.restoreAll()
        assertEquals("1.3", get(d, "system/font_scale"))
    }

    @Test fun nothingChangedMeansNothingRestored() {
        val d = FakeDevice("global/airplane_mode_on" to "0")
        d.ledger().restoreAll()
        assertTrue(d.writes.isEmpty())
    }

    @Test fun aFailedRestoreIsKeptForRetryTheOthersAreStillRestoredAndTheTeardownFails() {
        val d = FakeDevice("global/a" to "1", "global/b" to "2", "global/c" to "3")
        val l = d.ledger()
        for (k in listOf("a", "b", "c")) l.put("global", k, "9")
        d.failing += "settings put global b"          // restoring b fails
        val e = assertThrows(IllegalStateException::class.java) { l.restoreAll() }
        assertTrue(e.message!!, e.message!!.contains("global/b"))
        assertEquals("1", get(d, "global/a")); assertEquals("3", get(d, "global/c"))   // the others were still attempted
        assertEquals("9", get(d, "global/b"))
        assertEquals(listOf("global/b"), l.pending)
        d.failing.clear()
        l.restoreAll()                                  // the retry works: the original was kept
        assertEquals("2", get(d, "global/b")); assertTrue(l.pending.isEmpty())
    }

    @Test fun aRestoreThatSilentlyDidNothingIsCaughtByTheReadBack() {
        val d = FakeDevice("global/a" to "1")
        val l = d.ledger()
        l.put("global", "a", "9")
        d.ignored += "settings put global a"
        assertThrows(IllegalStateException::class.java) { l.restoreAll() }
        assertEquals(listOf("global/a"), l.pending)
    }

    @Test fun aFailedWriteIsReported() {
        val d = FakeDevice("global/a" to "1")
        d.failing += "settings put global a"
        assertThrows(IllegalStateException::class.java) { d.ledger().put("global", "a", "9") }
    }

    @Test fun anUnreadableOriginalAbortsBeforeAnythingIsChanged() {
        val d = FakeDevice("global/airplane_mode_on" to "0")
        d.unreadable += "global/mobile_data"
        assertThrows(IllegalStateException::class.java) { d.ledger().setRadios(false) }
        assertTrue("no radio may be touched when an original is unknown: ${d.writes}", d.writes.isEmpty())
    }

    @Test fun airplaneModeAbsentDisabledAndEnabledOriginals() {
        for ((original, expected) in listOf<Pair<String?, String?>>(null to null, "0" to "0", "1" to "1")) {
            val d = FakeDevice().also { if (original != null) it.values["global/airplane_mode_on"] = original }
            val l = d.ledger()
            l.setAirplaneMode(original != "1")
            l.restoreAll()
            assertEquals("original $original", expected, get(d, "global/airplane_mode_on"))
            if (original == null) assertTrue("absent must be deleted, never enabled or disabled by value", d.writes.last().startsWith("settings delete"))
            if (original == "1") assertTrue(d.writes.last().endsWith("airplane-mode enable"))
        }
    }

    @Test fun radiosAbsentDisabledAndEnabledOriginalsRestoreEachOneSeparately() {
        val d = FakeDevice("global/wifi_on" to "0")      // wifi disabled, mobile data absent
        val l = d.ledger()
        l.setRadios(true)
        assertEquals("1", get(d, "global/wifi_on")); assertEquals("1", get(d, "global/mobile_data"))
        l.restoreAll()
        assertEquals("0", get(d, "global/wifi_on"))
        assertFalse("an absent mobile_data stays absent", "global/mobile_data" in d.values)

        val e = FakeDevice("global/wifi_on" to "1", "global/mobile_data" to "1")
        val l2 = e.ledger()
        l2.setRadios(false)
        assertEquals("0", get(e, "global/wifi_on"))
        l2.restoreAll()
        assertEquals("1", get(e, "global/wifi_on")); assertEquals("1", get(e, "global/mobile_data"))
    }

    @Test fun aRadioThatNeverComesBackFailsTheTeardownAndStaysPending() {
        val d = FakeDevice("global/wifi_on" to "1", "global/mobile_data" to "1")
        val l = d.ledger()
        l.setRadios(false)
        d.ignored += "svc wifi"
        assertThrows(IllegalStateException::class.java) { l.restoreAll() }
        assertEquals(listOf("global/wifi_on"), l.pending)    // data was restored
        assertEquals("1", get(d, "global/mobile_data"))
    }
}
