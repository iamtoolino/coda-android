package dev.coda.core.sync

import dev.coda.core.subsonic.*
import dev.coda.core.playback.AlbumSequenceContext
import dev.coda.core.playback.CompletedPlayback
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AlbumResumeTest {
    @Test fun swiftDerivedEligibilityAndReadback() {
        val json = Json { ignoreUnknownKeys = true }
        val golden = json.parseToJsonElement(javaClass.getResource("/album-resume/swift-goldens.json")!!.readText()).jsonObject
        golden.getValue("eligibility").jsonArray.forEach { row ->
            val r = row.jsonObject
            val song = Song("song", "Song", type = r.getValue("type").jsonPrimitive.contentOrNull,
                mediaType = r.getValue("mediaType").jsonPrimitive.contentOrNull,
                albumId = r.getValue("albumId").jsonPrimitive.contentOrNull)
            assertEquals(r.toString(), r.getValue("eligible").jsonPrimitive.boolean, isEligibleAlbumResumeSong(song))
        }
        assertNull(albumResumeRecordingAlbumId(CompletedPlayback("song", null)))
        golden.getValue("recording").jsonArray.forEach { row ->
            val r = row.jsonObject
            val context = AlbumSequenceContext(r.getValue("albumId").jsonPrimitive.contentOrNull,
                r.getValue("previous").jsonPrimitive.boolean, r.getValue("next").jsonPrimitive.boolean,
                r.getValue("explicit").jsonPrimitive.boolean)
            assertEquals(r.getValue("result").jsonPrimitive.contentOrNull,
                albumResumeRecordingAlbumId(CompletedPlayback("song", context)))
        }
        val bookmarks = json.decodeFromJsonElement<List<Bookmark>>(golden.getValue("bookmarks"))
        val actual = albumResumeItems(bookmarks)
        val expected = golden.getValue("items").jsonArray
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (row, item) ->
            val r = row.jsonObject
            assertEquals(r.getValue("albumId").jsonPrimitive.content, item.album.id)
            assertEquals(r.getValue("anchorSongId").jsonPrimitive.content, item.anchorSongId)
            assertEquals(r.getValue("resumeSongId").jsonPrimitive.content, item.resumeSongId)
            assertEquals(r.getValue("changed").jsonPrimitive.content, item.changed)
        }
        assertEquals(actual.take(3), albumResumeItems(bookmarks, 3))
        assertTrue(albumResumeItems(bookmarks, -1).isEmpty())
        assertEquals(actual, albumResumeItems(bookmarks.reversed()))
    }
}
