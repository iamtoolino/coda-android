package dev.coda.core.sync

import dev.coda.core.subsonic.*
import dev.coda.core.playback.FakeAudioEngine
import dev.coda.core.playback.PlaybackController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueHandoffGoldenTest {
    private val golden = JSONObject(javaClass.getResource("/queue-handoff/swift.json")!!.readText())
    @Test fun swiftDispositionMatrix() {
        val cases = golden.getJSONArray("dispositions")
        for (i in 0 until cases.length()) {
            val row = cases.getJSONObject(i)
            val kind = row.getString("kind")
            val queue = if (kind == "absent") null else PlayQueue(changedBy = when (kind) {
                "own" -> "Coda on Android"; "ownCase" -> "CODA ON ANDROID"; "unknown" -> null; else -> "Coda on Mac"
            }, entry = if (kind == "empty") emptyList() else listOf(Song("a", "A")))
            assertEquals("case $i", row.getString("result").uppercase(), queueHandoffDisposition(queue,
                "Coda on Android", row.getBoolean("allows"), row.getBoolean("empty"),
                row.getBoolean("playing"), row.getBoolean("handled")).name)
        }
    }
    @Test fun swiftRestoreIndexPositionAndIdentity() = runTest {
        val cases = golden.getJSONArray("restores")
        for (i in 0 until cases.length()) {
            val row = cases.getJSONObject(i)
            val queue = PlayQueue(current = row.getString("current"), position = row.getLong("position"),
                currentIndex = row.getInt("index"), entry = listOf(Song("a", "A"), Song("b", "B"), Song("a", "A")))
            assertEquals(row.getInt("resolvedIndex"), queue.resolvedCurrentIndex)
            assertEquals(row.getString("identity"), queue.handoffIdentity)
            val player = PlaybackController(FakeAudioEngine())
            val coordinator = QueueHandoffCoordinator(player, backgroundScope, "Coda on Android",
                { queue.copy(changedBy = "Coda on Android") }, {})
            advanceTimeBy(251); runCurrent()
            assertEquals(row.getInt("resolvedIndex"), player.currentIndex)
            assertEquals(row.getLong("position"), player.positionMs)
            assertFalse(player.isPlaying)
            coordinator.close()
        }
    }
}
