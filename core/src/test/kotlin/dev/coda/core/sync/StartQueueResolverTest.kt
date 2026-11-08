package dev.coda.core.sync

import dev.coda.core.playback.QueueEntry
import dev.coda.core.subsonic.Album
import dev.coda.core.subsonic.AlbumListType
import dev.coda.core.subsonic.PlayQueue
import dev.coda.core.subsonic.Song
import dev.coda.core.subsonic.SubsonicError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The empty-query chain, one step missing at a time, the 9-vs-10 rated threshold and the top-up. Synthetic data only. */
class StartQueueResolverTest {
    private val now = java.time.Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
    private fun daysAgo(days: Long) = java.time.Instant.ofEpochMilli(now - days * 86_400_000).toString()

    private class Fake(
        var own: OwnQueue? = null,
        var server: (() -> PlayQueue?) = { null },
        var newest: List<Album> = emptyList(),
        var topRated: List<Album> = emptyList(),
        var random: List<Album> = emptyList(),
    ) : StartQueueResolver.Sources {
        val calls = mutableListOf<String>()
        override suspend fun ownQueue() = own.also { calls += "own" }
        override suspend fun serverQueue(): PlayQueue? { calls += "server"; return server() }
        override suspend fun albumList(type: AlbumListType, size: Int, offset: Int): List<Album> {
            calls += "${type.apiValue}@$offset"
            val list = when (type) { AlbumListType.NEWEST -> newest; AlbumListType.TOP_RATED -> topRated; AlbumListType.RANDOM -> random; else -> error("unexpected $type") }
            return list.drop(offset).take(size)
        }
    }

    private fun resolve(fake: Fake, reason: StartQueueResolver.Reason = StartQueueResolver.Reason.VOICE) =
        kotlinx.coroutines.runBlocking { StartQueueResolver(fake, CLIENT) { now }.resolve(reason) }

    private val CLIENT = "Coda on Android"
    private val ownQueue = OwnQueue(listOf(QueueEntry("own-1"), QueueEntry("own-2")), 1, 42_000)
    private val handoff = PlayQueue(current = "mac-2", position = 9_000, changedBy = "Coda on Mac", changed = "v1",
        entry = listOf(Song("mac-1", "Mac One"), Song("mac-2", "Mac Two")))
    private fun unrated(n: Int) = Album("new-$n", "New $n", created = daysAgo(n.toLong()))
    private fun rated(n: Int, stars: Int, ageDays: Long = 30) = Album("rated-$n-$stars", "Rated $n", userRating = stars, created = daysAgo(ageDays))
    private fun randoms(n: Int) = (1..n).map { Album("random-$it", "Random $it") }

    // Step 1: our own queue wins and nothing else is asked.
    @Test fun ownQueueComesFirst() {
        val fake = Fake(own = ownQueue, server = { handoff }, newest = (1..6).map(::unrated))
        assertEquals(StartQueueResolver.Choice.Own(ownQueue), resolve(fake))
        assertEquals(listOf("own"), fake.calls)
    }

    // Step 1 missing: the handoff queue plays (the voice request is the explicit tap).
    @Test fun withoutOwnQueueVoicePlaysTheHandoffQueue() {
        val fake = Fake(server = { handoff }, newest = (1..6).map(::unrated))
        assertEquals(StartQueueResolver.Choice.Handoff(handoff), resolve(fake))
        assertEquals(listOf("own", "server"), fake.calls)
    }

    // A server queue this phone wrote itself (no local copy yet, e.g. right after the update) is step 1, not a handoff.
    @Test fun aServerQueueWrittenByThisPhoneCountsAsOwn() {
        val mine = handoff.copy(changedBy = "coda on android")
        val fake = Fake(server = { mine }, newest = (1..6).map(::unrated))
        assertEquals(StartQueueResolver.Choice.OwnOnServer(mine), resolve(fake))
        // The local copy still comes first; another device's queue is still the handoff.
        assertEquals(StartQueueResolver.Choice.Own(ownQueue), resolve(Fake(own = ownQueue, server = { mine })))
        assertEquals(StartQueueResolver.Choice.Handoff(handoff), resolve(Fake(server = { handoff })))
        assertEquals(listOf("own", "server"), fake.calls)
    }

    @Test fun resumptionNeverReadsTheServerQueueEvenWhenItIsOurs() {
        val fake = Fake(server = { handoff.copy(changedBy = CLIENT) }, newest = (1..6).map(::unrated))
        assertTrue(resolve(fake, StartQueueResolver.Reason.RESUMPTION) is StartQueueResolver.Choice.Albums)
        assertTrue("server queue never read", "server" !in fake.calls)
    }

    // Resumption never plays the handoff queue unseen: with no own queue it starts at step 3.
    @Test fun resumptionSkipsTheHandoffQueue() {
        val fake = Fake(server = { handoff }, newest = (1..6).map(::unrated))
        val choice = resolve(fake, StartQueueResolver.Reason.RESUMPTION) as StartQueueResolver.Choice.Albums
        assertEquals(listOf("new-1", "new-2", "new-3", "new-4"), choice.albums.map { it.id })
        assertTrue("server queue never read", "server" !in fake.calls)
    }

    @Test fun resumptionPlaysTheOwnQueue() {
        assertEquals(StartQueueResolver.Choice.Own(ownQueue), resolve(Fake(own = ownQueue, server = { handoff }), StartQueueResolver.Reason.RESUMPTION))
    }

    // Steps 1 and 2 missing (no queue, an empty one, or an unreadable one): the 4 newest unrated albums, in order.
    @Test fun withoutQueuesTheFourNewestUnratedAlbumsPlay() {
        val newest = listOf(unrated(1), rated(1, 5), unrated(2), rated(2, 2), unrated(3), unrated(4), unrated(5))
        for (server in listOf<() -> PlayQueue?>({ null }, { PlayQueue(changedBy = "Coda on Mac") }, { throw java.io.IOException("offline") })) {
            val fake = Fake(server = server, newest = newest, topRated = listOf(rated(9, 5)), random = randoms(4))
            val choice = resolve(fake) as StartQueueResolver.Choice.Albums
            assertEquals(listOf("new-1", "new-2", "new-3", "new-4"), choice.albums.map { it.id })
            assertEquals(StartQueueResolver.Choice.Albums(choice.albums, 4, 0, 0), choice)
            assertEquals("no top-up or random needed", listOf("own", "server", "newest@0"), fake.calls)
        }
    }

    @Test fun aRejectedLoginIsNotAMissingStep() {
        try { resolve(Fake(server = { throw SubsonicError.Unauthorized() }, newest = (1..4).map(::unrated))); fail("expected Unauthorized") }
        catch (_: SubsonicError.Unauthorized) { }
    }

    // Step 4 threshold: 9 rated albums are not enough for the top-up; random albums fill the places.
    @Test fun nineRatedAlbumsGiveNoTopUp() {
        val topRated = (1..5).map { rated(it, 5) } + (6..9).map { rated(it, 3) } + listOf(Album("plain", "Plain"))
        val fake = Fake(newest = listOf(unrated(1), unrated(2), rated(1, 5)), topRated = topRated, random = randoms(6))
        val choice = resolve(fake) as StartQueueResolver.Choice.Albums
        assertEquals(listOf("new-1", "new-2", "random-1", "random-2"), choice.albums.map { it.id })
        assertEquals(StartQueueResolver.Choice.Albums(choice.albums, 2, 0, 2), choice)
    }

    // ... 10 rated albums are: the 4★+ albums added in the last year top up (best first, then newest), 3★ and old ones don't.
    @Test fun tenRatedAlbumsTopUpWithRecentFourStarAlbums() {
        val topRated = listOf(rated(1, 4, ageDays = 10), rated(2, 5, ageDays = 400), rated(3, 5, ageDays = 200), rated(4, 4, ageDays = 5)) +
            (5..10).map { rated(it, 3) } + listOf(Album("plain", "Plain"))
        val fake = Fake(newest = listOf(unrated(1), rated(1, 4, ageDays = 10)), topRated = topRated, random = randoms(6))
        val choice = resolve(fake) as StartQueueResolver.Choice.Albums
        assertEquals(listOf("new-1", "rated-3-5", "rated-4-4", "rated-1-4"), choice.albums.map { it.id })
        assertEquals(StartQueueResolver.Choice.Albums(choice.albums, 1, 3, 0), choice)
        assertTrue("random not needed", fake.calls.none { it.startsWith("random") })
    }

    // Top-up short of 4: random albums take the rest, never repeating a picked album.
    @Test fun randomAlbumsFillWhatTheTopUpLeaves() {
        val topRated = listOf(rated(1, 5, ageDays = 3)) + (2..10).map { rated(it, 2) }
        val fake = Fake(newest = listOf(unrated(1)), topRated = topRated,
            random = listOf(Album("new-1", "New 1"), Album("rated-1-5", "Rated 1")) + randoms(3))
        val choice = resolve(fake) as StartQueueResolver.Choice.Albums
        assertEquals(listOf("new-1", "rated-1-5", "random-1", "random-2"), choice.albums.map { it.id })
        assertEquals(StartQueueResolver.Choice.Albums(choice.albums, 1, 1, 2), choice)
    }

    // Steps 3 and 4 empty: random albums, so the request never plays nothing; an empty library plays nothing.
    @Test fun onlyRandomAlbumsOrNothing() {
        val choice = resolve(Fake(newest = listOf(rated(1, 5)), topRated = listOf(rated(1, 5)), random = randoms(9))) as StartQueueResolver.Choice.Albums
        assertEquals(listOf("random-1", "random-2", "random-3", "random-4"), choice.albums.map { it.id })
        assertNull(resolve(Fake()))
    }

    // Unrated albums beyond the first page are found, but the scan is bounded.
    @Test fun newestScanPagesAndStops() {
        val newest = (1..150).map { rated(it, 3) } + listOf(unrated(1), unrated(2), unrated(3), unrated(4), unrated(5))
        val fake = Fake(newest = newest)
        val choice = resolve(fake) as StartQueueResolver.Choice.Albums
        assertEquals(listOf("new-1", "new-2", "new-3", "new-4"), choice.albums.map { it.id })
        assertEquals(listOf("own", "server", "newest@0", "newest@100"), fake.calls)
        val endless = Fake(newest = (1..2_000).map { rated(it, 3) }, random = randoms(4))
        resolve(endless)
        assertEquals(StartQueueResolver.MAX_SCAN / StartQueueResolver.PAGE, endless.calls.count { it.startsWith("newest") })
    }

    // The scan cap ends the top-up with what was read; it never empties the request: random albums still fill, and
    // recent 4★ albums inside the first 500 are still used.
    @Test fun theScanCapNeverEmptiesTheRequest() {
        val old = (1..600).map { rated(it, 5, ageDays = 900) }
        val none = resolve(Fake(newest = emptyList(), topRated = old, random = randoms(6))) as StartQueueResolver.Choice.Albums
        assertEquals(listOf("random-1", "random-2", "random-3", "random-4"), none.albums.map { it.id })
        val some = resolve(Fake(topRated = listOf(rated(700, 4, ageDays = 3)) + old, random = randoms(6))) as StartQueueResolver.Choice.Albums
        assertEquals(listOf("rated-700-4", "random-1", "random-2", "random-3"), some.albums.map { it.id })
        // A failing top-rated list falls through to random too.
        val failing = object : StartQueueResolver.Sources {
            override suspend fun ownQueue(): OwnQueue? = null
            override suspend fun serverQueue(): PlayQueue? = null
            override suspend fun albumList(type: AlbumListType, size: Int, offset: Int): List<Album> =
                if (type == AlbumListType.TOP_RATED) throw java.io.IOException("boom") else if (type == AlbumListType.RANDOM) randoms(4) else emptyList()
        }
        val choice = kotlinx.coroutines.runBlocking { StartQueueResolver(failing, CLIENT) { now }.resolve(StartQueueResolver.Reason.VOICE) } as StartQueueResolver.Choice.Albums
        assertEquals(4, choice.random)
    }

    // A failing newest list is a missing step: the top-up and random albums still play; a rejected login still fails.
    @Test fun aFailingNewestListFallsThroughToRandom() {
        fun failing(error: Exception) = object : StartQueueResolver.Sources {
            override suspend fun ownQueue(): OwnQueue? = null
            override suspend fun serverQueue(): PlayQueue? = null
            override suspend fun albumList(type: AlbumListType, size: Int, offset: Int): List<Album> =
                if (type == AlbumListType.NEWEST) throw error else if (type == AlbumListType.RANDOM) randoms(4) else emptyList()
        }
        val choice = kotlinx.coroutines.runBlocking { StartQueueResolver(failing(SubsonicError.Server(0, "boom")), CLIENT) { now }.resolve(StartQueueResolver.Reason.VOICE) }
        assertEquals(StartQueueResolver.Choice.Albums(randoms(4), 0, 0, 4), choice)
        try { kotlinx.coroutines.runBlocking { StartQueueResolver(failing(SubsonicError.Unauthorized()), CLIENT) { now }.resolve(StartQueueResolver.Reason.VOICE) }; fail("expected Unauthorized") }
        catch (_: SubsonicError.Unauthorized) { }
    }

    // The random list is only a top-up once albums are chosen: its failure keeps them; a rejected login still fails,
    // and with nothing chosen the failure still propagates (the car falls back to the phone).
    @Test fun aFailingRandomTopUpKeepsTheChosenAlbums() {
        fun failing(error: Exception, newest: List<Album>) = object : StartQueueResolver.Sources {
            override suspend fun ownQueue(): OwnQueue? = null
            override suspend fun serverQueue(): PlayQueue? = null
            override suspend fun albumList(type: AlbumListType, size: Int, offset: Int): List<Album> = when (type) {
                AlbumListType.NEWEST -> newest.drop(offset).take(size)
                AlbumListType.RANDOM -> throw error
                else -> emptyList()
            }
        }
        fun run(sources: StartQueueResolver.Sources) =
            kotlinx.coroutines.runBlocking { StartQueueResolver(sources, CLIENT) { now }.resolve(StartQueueResolver.Reason.VOICE) }
        val two = listOf(unrated(1), unrated(2))
        assertEquals(StartQueueResolver.Choice.Albums(two, 2, 0, 0), run(failing(SubsonicError.Server(0, "boom"), two)))
        try { run(failing(SubsonicError.Unauthorized(), two)); fail("expected Unauthorized") } catch (_: SubsonicError.Unauthorized) { }
        try { run(failing(SubsonicError.Server(0, "boom"), emptyList())); fail("expected the server error") } catch (_: SubsonicError.Server) { }
    }

    // Requests that name nothing play the start chain; titles stay searches.
    @Test fun genericVoiceRequests() {
        for (query in listOf(null, "", "  ", "music", "Music", "some music.", "songs", "something", "anything"))
            assertTrue("'$query'", StartQueueResolver.isGeneric(query, null))
        assertTrue(StartQueueResolver.isGeneric("Radiohead", StartQueueResolver.FOCUS_ANY))
        for (query in listOf("music for airports", "signal one", "musical"))
            assertTrue("'$query'", !StartQueueResolver.isGeneric(query, null))
        assertTrue(!StartQueueResolver.isGeneric("signal one", "vnd.android.cursor.item/audio"))
    }

    @Test fun createdDatesInServerSpellings() {
        assertEquals(java.time.Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), StartQueueResolver.createdMs(Album("a", "A", created = "2026-01-01T00:00:00Z")))
        assertEquals(java.time.Instant.parse("2026-01-01T00:00:00.5Z").toEpochMilli(), StartQueueResolver.createdMs(Album("a", "A", created = "2026-01-01T01:00:00.500+01:00")))
        assertEquals(java.time.Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), StartQueueResolver.createdMs(Album("a", "A", created = "2026-01-01")))
        assertNull(StartQueueResolver.createdMs(Album("a", "A", created = "soon")))
        assertNull(StartQueueResolver.createdMs(Album("a", "A")))
    }
}
