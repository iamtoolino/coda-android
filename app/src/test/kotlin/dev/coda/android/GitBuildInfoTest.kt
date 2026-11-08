package dev.coda.android

import dev.coda.buildinfo.GitBuildInfo
import org.junit.Assert.*
import org.junit.Test

class GitBuildInfoTest {
    @Test fun generatedBuildConfigMatchesRepositoryWhenGitIsAvailable() {
        val process = try { ProcessBuilder("git", "rev-parse", "--short=12", "HEAD").start() }
            catch (_: java.io.IOException) { return }
        val commit = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) assertEquals(commit, BuildConfig.GIT_COMMIT)
    }

    @Test fun unavailableGitUsesUnknownAndExplicitVersionFallback() {
        val info = GitBuildInfo { throw java.io.IOException() }
        assertEquals(listOf("unknown", "unknown", "unknown", "unknown"), listOf(info.commit, info.describe, info.tag, info.count))
        assertEquals(3, info.debugVersionCode(3))
    }
    @Test fun emptyOrMalformedCountCannotMakeInvalidVersionCode() {
        for (count in listOf("", "garbage", "0", "-1", "999999999999")) {
            assertEquals(3, GitBuildInfo { count }.debugVersionCode(3))
        }
    }
    @Test fun exactCommandsAndMissingTagPreserveOtherMetadata() {
        val commands = mutableListOf<List<String>>()
        val info = GitBuildInfo { args ->
            commands += args.toList()
            when (args.toList()) {
                listOf("rev-parse", "--short=12", "HEAD") -> "123456789abc\n"
                listOf("describe", "--tags", "--always", "--dirty") -> "v0.1.2-5-g1234567-dirty\n"
                listOf("rev-list", "--count", "HEAD") -> "225\n"
                else -> ""
            }
        }
        assertEquals("123456789abc", info.commit)
        assertEquals("v0.1.2-5-g1234567-dirty", info.describe)
        assertEquals("unknown", info.tag)
        assertEquals(225, info.debugVersionCode(3))
        assertTrue(commands.contains(listOf("describe", "--tags", "--exact-match", "HEAD")))
    }
}
