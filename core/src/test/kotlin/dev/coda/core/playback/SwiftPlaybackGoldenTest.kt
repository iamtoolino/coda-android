package dev.coda.core.playback

import org.junit.Assert.*
import org.junit.Test

/** Fixtures are produced by executing the unmodified Swift policies and PlayerController. */
class SwiftPlaybackGoldenTest {
    private val rows = javaClass.getResourceAsStream("/playback/swift-golden.tsv")!!
        .bufferedReader().useLines { lines ->
            lines.filterNot { it.startsWith("#") }.map { it.split('\t') }.toList()
        }

    private fun album(token: String): String? = when (token) {
        "-" -> null
        "_" -> ""
        "~" -> " \n"
        else -> token
    }

    private fun number(token: String): Double? = when (token) {
        "-" -> null
        "nan" -> Double.NaN
        "inf" -> Double.POSITIVE_INFINITY
        else -> token.toDouble()
    }

    private fun entry(id: String, album: String, tagged: Boolean = true) = QueueEntry(
        songId = id, albumId = album(album), title = "Song", artist = "Artist", album = "Same title",
        durationMs = 120_000,
        replayGain = if (tagged) ReplayGainMetadata(trackGain = -3.0, albumGain = -8.0) else null,
    )

    @Test fun albumContextMatchesSwiftExamplesAndBoundaries() {
        for (r in rows.filter { it[0] == "context" }) {
            val queue = if (r[1].isEmpty()) emptyList() else r[1].split(',').mapIndexed { i, token ->
                entry("s$i", token).copy(discNumber = i + 1, trackNumber = i * 3 + 1)
            }
            val context = AlbumSequenceResolver.context(queue, r[2].toInt())
            assertEquals(r.toString(), r[6].toBoolean(), context != null)
            assertEquals(r.toString(), album(r[3]), context?.albumId)
            assertEquals(r.toString(), r[4].toBoolean(), context?.continuesPrevious ?: false)
            assertEquals(r.toString(), r[5].toBoolean(), context?.continuesNext ?: false)
        }
    }

    @Test fun replayGainSelectionAndPeaksMatchSwift() {
        for (r in rows.filter { it[0] == "gain" }) {
            val context = AlbumSequenceContext("A", false, r[2].toBoolean())
            val metadata = ReplayGainMetadata(number(r[3]), number(r[4]), number(r[5]), number(r[6]))
            val decision = ReplayGainPolicy.decision(r[1].toBoolean(), context, metadata)
            assertEquals(r.toString(), r[7], decision.source.name)
            assertEquals(r.toString(), r[8].toDouble(), decision.gainDb, 1e-10)
            assertEquals(r.toString(), r[9].toBoolean(), decision.isPeakLimited)
        }
        assertEquals(-9.0, ReplayGainPolicy.gainDb(true, null, null), 0.0)
        assertEquals(0.0, ReplayGainPolicy.gainDb(false, null, null), 0.0)
    }

    @Test fun groupingAndContinuationHintsMatchSwift() {
        val groupRows = rows.filter { it[0] == "groups" }
        assertEquals(10, groupRows.size)
        for (r in groupRows) {
            val ids = if (r[1].isEmpty()) emptyList() else r[1].split(',').map(::album)
            val actual = AlbumSequenceResolver.groups(ids).joinToString(",") {
                "${it.range.first}:${it.range.count()}:${it.albumId ?: "-"}"
            }
            assertEquals(r.toString(), r[2].takeUnless { it == "-" } ?: "", actual)
            val entries = ids.mapIndexed { i, id -> QueueEntry("duplicate", albumId = id) }
            val presentation = dev.coda.core.library.TrackListPresentation.queueGroups(entries)
            // Intentionally changes visible groups, while playback continuity keeps Swift parity.
            assertEquals(AlbumSequenceResolver.groups(ids).flatMap { group ->
                if (group.range.count() >= 3) listOf(group.range) else group.range.map { it..it }
            }, presentation.map {
                it.startIndex until it.startIndex + it.entries.size
            })
            assertEquals(entries.map { it.id }, presentation.flatMap { it.entries }.map { it.id })
        }
        for (r in rows.filter { it[0] == "continuation" }) {
            val context = AlbumSequenceResolver.context(album(r[1]), null, null, "A")
            assertEquals(r[2].toBoolean(), context.isAlbumSequence)
            assertEquals(r[3].toBoolean(), context.isExplicitContinuation)
        }
    }

    @Test fun multiSelectionReorderingValidationAndNoOpsMatchSwift() {
        val ids = (0..4).map { java.util.UUID(0, it.toLong()) }
        val reorderRows = rows.filter { it[0] == "reorder" }
        assertEquals(42, reorderRows.size)
        for (r in reorderRows) {
            val moving = if (r[1].isEmpty()) emptyList() else r[1].split(',').map { ids[it.toInt()] }
            val before = r[2].toInt().takeIf { it >= 0 }?.let { ids[it] }
            val actual = reorderedQueueIds(ids.take(4), moving, before)
                ?.joinToString(",") { ids.indexOf(it).toString() } ?: "-"
            assertEquals(r.toString(), r[3], actual)
        }
    }

    @Test fun albumIdentityWhitespaceNormalizationMatchesFoundation() {
        val normalizationRows = rows.filter { it[0] == "normalize" }
        assertTrue(normalizationRows.isNotEmpty())
        for (r in normalizationRows) {
            val input = String(Character.toChars(r[1].toInt()))
            val actual = AlbumSequenceResolver.normalizedAlbumId(input)
                ?.codePoints()?.toArray()?.joinToString(",") ?: "-"
            assertEquals(r.toString(), r[2], actual)
        }
    }

    @Test fun queueAndOccurrenceTraceMatchesRealSwiftController() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        val expected = rows.filter { it[0] == "controller" }.associateBy { it[1] }
        val checked = mutableSetOf<String>()
        fun trace(label: String) {
            val actual = listOf(
                "controller", label, player.currentEntry?.songId ?: "-", (player.currentIndex ?: -1).toString(),
                player.isPlaying.toString(), player.status, player.currentReplayGainDb.toString(),
                (player.currentAlbumSequence?.isAlbumSequence ?: false).toString(),
                player.completedPlayback?.songId ?: "-",
                (player.completedPlayback?.albumSequence?.isAlbumSequence ?: false).toString(),
                engine.loadCalls.size.toString(), engine.updatedNextItems.size.toString(),
            )
            val swift = expected.getValue(label)
            assertEquals(label, swift.dropLast(1), actual.dropLast(1))
            // F2 deliberately omits Swift's redundant updateNext(nil) at insert-before-current.
            // Assert the resulting call counts explicitly; every product-state column stays golden.
            val expectedNextUpdates = when (label) {
                "insert-before-current" -> 5
                "move-selection" -> 6
                "off", "manual-play", "previous-at-3", "previous-after-3", "fail", "clear" -> 7
                else -> swift.last().toInt()
            }
            assertEquals("$label updateNext calls", expectedNextUpdates, engine.updatedNextItems.size)
            checked += label
        }
        val a = entry("a", "A"); val b = entry("b", "B"); val c = entry("c", "B")
        val d = entry("d", "D", tagged = false)
        player.setReplayGainEnabled(true)
        player.replaceQueue(listOf(a, b)); trace("load")
        player.enqueue(listOf(c)); trace("append")
        engine.advance(); trace("advance")
        player.remove(c.id); trace("remove-next")
        player.seek(1_000); player.pause(); trace("pause-seek")
        engine.finish(); trace("finish")
        player.replaceQueue(listOf(a), startsPlaying = false); trace("restore")
        player.enqueue(listOf(entry("a2", "A"))); trace("edit-before-play")
        player.play(); player.pause(); player.remove(player.queue[1].id); trace("edit-after-play")
        player.replaceQueue(listOf(a, b, c, d), startAt = 1, startsPlaying = false)
        player.remove(listOf(b.id, c.id)); trace("replace-with-successor")
        player.remove(d.id); trace("replace-with-predecessor")
        player.insert(listOf(b, c), before = a.id); trace("insert-before-current")
        assertTrue(player.move(listOf(a.id, c.id), before = b.id)); trace("move-selection")
        player.setReplayGainEnabled(false); trace("off")
        player.play(1); trace("manual-play")
        engine.position = 3_000
        engine.emit(EngineEvent.State(engine.current!!.occurrence, true, false))
        player.previous(); trace("previous-at-3")
        player.play(1)
        engine.position = 3_001
        engine.emit(EngineEvent.State(engine.current!!.occurrence, true, false))
        player.previous(); trace("previous-after-3")
        engine.failCurrent(PlaybackFailure.Decode); trace("fail")
        player.clear(); trace("clear")
        assertEquals(expected.keys, checked)
    }
}
