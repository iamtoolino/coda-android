package io.github.iamtoolino.coda.data

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AlbumResumeTest {
    private val first = Song("first", "First", album = "Album", albumId = "album")
    private val last = first.copy(id = "last", title = "Last")
    private val page = AlbumPage(Album("album", "Album", coverArt = "al-cover"), listOf(first, last))
    private fun bookmark(id: String, changed: String? = id) = Bookmark(
        entry = first.copy(id = "anchor-$id", albumId = id),
        comment = albumResumeComment("next-$id"), changed = changed,
    )

    private fun coordinator(
        scope: CoroutineScope,
        bookmarks: suspend () -> List<Bookmark> = { emptyList() },
        album: suspend (String) -> AlbumPage = { page },
        upsert: suspend (String, String) -> Unit = { _, _ -> },
        delete: suspend (String) -> Unit = {},
    ) = AlbumResumeCoordinator(
        scope, bookmarks, { if (it == first.id) first else last }, album, upsert, delete,
    )

    @Test fun `marker is portable bounded UTF8 and rejects unsupported versions`() {
        val writer = AlbumResumeWriter("Coda", "Android", "ü".repeat(300))
        val comment = requireNotNull(albumResumeComment("last", writer))
        assertTrue(comment.toByteArray().size <= 255)
        assertNull(parseAlbumResumeMarker(comment)?.writer)
        assertEquals("last", parseAlbumResumeMarker(comment)?.resumeSongId)
        assertNull(albumResumeComment("ü".repeat(200)))
        assertNull(albumResumeComment(" "))
        assertNull(parseAlbumResumeMarker("ordinary bookmark"))
        assertNull(parseAlbumResumeMarker(comment.replace(":1", ":2")))
        assertNull(parseAlbumResumeMarker(comment.replace("album-resume-bookmark", "other")))
        assertEquals("last", parseAlbumResumeMarker(
            """{"extra":true,"resumeSongId":"last","protocolVersion":1,"protocol":"album-resume-bookmark"}""",
        )?.resumeSongId)
    }

    @Test fun `bookmark zero omission and media classification decode`() {
        val value = Json.decodeFromString<Bookmark>(
            """{"entry":{"id":"song","title":"Title","albumId":"album","type":"music","mediaType":"song"}}""",
        )
        assertEquals(0L, value.position)
        assertTrue(requireNotNull(value.entry).isAlbumResumeEligible())
        for (type in listOf("podcast", "audiobook", "video", "unknown")) {
            assertFalse(first.copy(type = type).isAlbumResumeEligible())
            assertFalse(first.copy(mediaType = type).isAlbumResumeEligible())
        }
        assertFalse(first.copy(albumId = null).isAlbumResumeEligible())
    }

    @Test fun `canonical membership governs next and final even for isolated playback`() {
        assertEquals(AlbumResumeAction.Upsert("first", "last"), albumResumeAction(first, page))
        assertEquals(AlbumResumeAction.Delete("first"), albumResumeAction(last, page))
        assertEquals(AlbumResumeAction.Delete("first"), albumResumeAction(first, page.copy(songs = listOf(first))))
        assertNull(albumResumeAction(first.copy(id = "outsider"), page))
        assertNull(albumResumeAction(first.copy(albumId = "different"), page))
        assertNull(albumResumeAction(first.copy(type = "podcast"), page))
    }

    @Test fun `raw timestamp fallback deduplication and foreign exclusion match macOS`() {
        val old = bookmark("same", "a")
        val newer = old.copy(changed = "z", entry = first.copy(id = "z-anchor", albumId = "same"))
        val tied = newer.copy(entry = newer.entry!!.copy(id = "zz-anchor"))
        val buckets = albumResumeBuckets(listOf(
            old, newer, tied,
            bookmark("fallback", null).copy(created = "y"),
            bookmark("empty", "").copy(created = "zzz"),
            bookmark("foreign").copy(comment = "ordinary"),
            bookmark("unknown").copy(comment = albumResumeComment("x")!!.replace(":1", ":2")),
        ))
        assertEquals(listOf("same", "fallback", "empty"), buckets.map { it.item.album.id })
        assertEquals("zz-anchor", buckets.first().item.anchorSongId)
        assertEquals(3, buckets.first().anchorIds.size)
    }

    @Test fun `refresh coalesces and retention deletes only recognized excess`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val deleted = mutableListOf<String>()
        var reads = 0
        val coordinator = coordinator(this, bookmarks = {
            reads++
            release.await()
            (1..22).map { bookmark(it.toString().padStart(2, '0')) } +
                bookmark("foreign", "0").copy(comment = "other")
        }, delete = { deleted += it })
        val firstRefresh = coordinator.refresh()
        assertSame(firstRefresh, coordinator.refresh())
        release.complete(Unit)
        firstRefresh.join()
        assertEquals(1, reads)
        assertEquals(20, coordinator.items.value.size)
        assertEquals(listOf("anchor-02", "anchor-01"), deleted)
    }

    @Test fun `completion writes serialize and never fetch bookmarks`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val actions = mutableListOf<String>()
        val coordinator = coordinator(this, bookmarks = { error("must not fetch") }, upsert = { id, comment ->
            assertEquals("last", parseAlbumResumeMarker(comment)?.resumeSongId)
            started.complete(Unit)
            release.await()
            actions += "upsert:$id"
        }, delete = { actions += "delete:$it" })
        coordinator.completed("first")
        started.await()
        val final = coordinator.completed("last")
        assertTrue(actions.isEmpty())
        release.complete(Unit)
        final.join()
        assertEquals(listOf("upsert:first", "delete:first"), actions)
        assertTrue(coordinator.items.value.isEmpty())
    }

    @Test fun `old refresh cannot overwrite successful progress or delete excess afterward`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val deleted = mutableListOf<String>()
        val coordinator = coordinator(this, bookmarks = {
            started.complete(Unit)
            release.await()
            (1..22).map { bookmark(it.toString()) }
        }, delete = { deleted += it })
        val refresh = coordinator.refresh()
        started.await()
        coordinator.completed("first").join()
        release.complete(Unit)
        refresh.join()
        assertEquals("album", coordinator.items.value.single().album.id)
        assertTrue(deleted.isEmpty())
    }

    @Test fun `refresh begun during write cannot publish late stale snapshot`() = runBlocking {
        val writing = CompletableDeferred<Unit>()
        val writeRelease = CompletableDeferred<Unit>()
        val reading = CompletableDeferred<Unit>()
        val readRelease = CompletableDeferred<Unit>()
        val coordinator = coordinator(this, bookmarks = {
            reading.complete(Unit)
            readRelease.await()
            emptyList()
        }, upsert = { _, _ -> writing.complete(Unit); writeRelease.await() })
        val mutation = coordinator.completed("first")
        writing.await()
        val refresh = coordinator.refresh()
        reading.await()
        writeRelease.complete(Unit)
        mutation.join()
        readRelease.complete(Unit)
        refresh.join()
        assertEquals("last", coordinator.items.value.single().resumeSongId)
    }

    @Test fun `failed refresh and failed write retain good presentation`() = runBlocking {
        var fail = false
        val coordinator = coordinator(this, bookmarks = {
            if (fail) throw IOException("offline")
            listOf(bookmark("existing"))
        }, upsert = { _, _ -> throw IOException("offline") })
        coordinator.refresh().join()
        fail = true
        coordinator.refresh().join()
        coordinator.completed("first").join()
        assertEquals("existing", coordinator.items.value.single().album.id)
    }

    @Test fun `cancelled account cannot publish or write after noncooperative lookup`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val coordinator = coordinator(scope, album = {
            withContext(NonCancellable) { started.complete(Unit); release.await() }
            page
        }, upsert = { _, _ -> writes++ })
        val job = coordinator.completed("first")
        started.await()
        scope.cancel()
        release.complete(Unit)
        job.join()
        assertEquals(0, writes)
        assertTrue(coordinator.items.value.isEmpty())
    }

    @Test fun `resume validates target and plays entire album with canonical art`() = runBlocking {
        var plays = 0
        val coordinator = coordinator(this)
        val item = AlbumResumeItem(page.album, "first", "last", "")
        coordinator.continueListening(item) { songs, index ->
            plays++
            assertEquals(1, index)
            assertEquals(listOf("first", "last"), songs.map { it.id })
            assertTrue(songs.all { it.coverArt == "al-cover" })
        }.join()
        coordinator.continueListening(item.copy(resumeSongId = "gone")) { _, _ -> plays++ }.join()
        assertEquals(1, plays)
    }
}
