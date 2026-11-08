package dev.coda.core.library

import dev.coda.core.playback.QueueEntry
import dev.coda.core.subsonic.Song
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class TrackListPresentationTest {
    @Test fun swiftQueueAndPlaylistBoundaries() {
        val cases = listOf(emptyList(), listOf("a"), listOf("a", "a", "b", "c", "c"),
            listOf(null, null, "", " ", "a", " a "), listOf("a", "b", "a"), listOf("a", "a", "a", "a"),
            listOf(" A ", "A", "a", "a"), listOf("a", "a", null, "a", "a"), listOf("\u200ba", "a", "\u001ca", "a"))
        val golden = javaClass.getResourceAsStream("/track-list/group-sizes.txt")!!.bufferedReader().readLines()
        cases.forEachIndexed { case, ids ->
            // Duplicate song IDs and out-of-order/multidisc numbers must not change boundaries.
            val entries = ids.mapIndexed { i, id -> QueueEntry("duplicate", albumId = id, trackNumber = 9 - i, discNumber = i % 2 + 1) }
            val songs = ids.mapIndexed { i, id -> Song("duplicate", "Track", albumId = id, track = 9 - i, discNumber = i % 2 + 1) }
            assertEquals(golden[case], dev.coda.core.playback.AlbumSequenceResolver.groups(ids).joinToString(",") { it.range.count().toString() })
            val presentation = golden[case].split(",").filter { it.isNotEmpty() }.flatMap {
                val size = it.toInt(); if (size < 3) List(size) { 1 } else listOf(size)
            }
            assertEquals(presentation, TrackListPresentation.queueGroups(entries).map { it.entries.size })
            assertEquals(presentation, TrackListPresentation.playlistGroups(songs).map { it.songs.size })
            assertEquals(entries, TrackListPresentation.queueGroups(entries).flatMap { it.entries })
            assertEquals(songs, TrackListPresentation.playlistGroups(songs).flatMap { it.songs })
        }
    }

    @Test fun playlistShuffleMovesWholeRunsAndPreservesOccurrences() {
        val songs = listOf("a", "a", "b", "c", "c", "a", null, null).mapIndexed { i, album ->
            Song("$i", "Track", albumId = album)
        }
        val groups = TrackListPresentation.playlistGroups(songs).map { it.songs }
        repeat(30) { seed ->
            val shuffled = TrackListPresentation.shuffledPlaylist(songs, Random(seed))
            assertEquals(songs.toSet(), shuffled.toSet())
            assertEquals(songs.size, shuffled.size)
            groups.forEach { group ->
                val at = shuffled.indexOf(group.first())
                assertEquals(group, shuffled.subList(at, at + group.size))
            }
        }
        assertNotEquals(songs, TrackListPresentation.shuffledPlaylist(songs, Random(1)))
    }

    @Test fun movingAndDeletingReformsGroups() {
        val entries = listOf("a", "b", "a").map { QueueEntry("duplicate", albumId = it) }
        assertTrue(TrackListPresentation.queueGroups(entries).all { it.isStandalone })
        assertEquals(listOf(1, 1), TrackListPresentation.queueGroups(listOf(entries[0], entries[2])).map { it.entries.size })
        assertEquals(listOf(1, 1, 1), TrackListPresentation.queueGroups(listOf(entries[0], entries[2], entries[1])).map { it.entries.size })
    }
    @Test fun festivalSetGroupsOnlyThreeRunsAndShufflesAtLeastTwoUnits() {
        val songs = dev.coda.core.testing.FestivalSetlist.songs
        val groups = TrackListPresentation.playlistGroups(songs)
        assertEquals(listOf(1, 1, 1, 3, 1, 1, 1, 1, 3, 1, 1), groups.map { it.songs.size })
        assertTrue(TrackListPresentation.canShufflePlaylist(groups))
        assertTrue(TrackListPresentation.canShufflePlaylist(TrackListPresentation.playlistGroups(songs.take(2))))
        assertFalse(TrackListPresentation.canShufflePlaylist(TrackListPresentation.playlistGroups(songs.subList(3, 6))))
        assertFalse(TrackListPresentation.canShufflePlaylist(emptyList()))
        val queue = songs.map { QueueEntry(it.id, albumId = it.albumId) }
        assertEquals(groups.map { it.startIndex to it.songs.size },
            TrackListPresentation.queueGroups(queue).map { it.startIndex to it.entries.size })
        repeat(30) { seed ->
            val shuffled = TrackListPresentation.shuffledPlaylist(songs, Random(seed))
            assertEquals(songs.toSet(), shuffled.toSet())
            groups.filterNot { it.isStandalone }.forEach { run ->
                val first = shuffled.indexOf(run.songs.first())
                assertEquals(run.songs, shuffled.subList(first, first + run.songs.size))
            }
        }
    }

}
