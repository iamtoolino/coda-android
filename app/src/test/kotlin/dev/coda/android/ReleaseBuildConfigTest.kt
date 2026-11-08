package dev.coda.android

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ReleaseBuildConfigTest {
    @Test fun releaseHasNoAutoLoginFields() {
        val source = File(requireNotNull(System.getProperty("releaseBuildConfig"))).readText()
        assertTrue(source.contains("class BuildConfig"))
        assertFalse(source.contains("QA_"))
        assertFalse(source.contains("coda.qa."))
    }
}
