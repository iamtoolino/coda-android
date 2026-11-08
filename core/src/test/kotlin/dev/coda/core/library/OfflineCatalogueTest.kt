package dev.coda.core.library

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.coda.core.cache.CachedTrack
import dev.coda.core.cache.KeptItem
import dev.coda.core.cache.KeptTrack
import dev.coda.core.cache.TrackMeta
import dev.coda.core.playback.StreamFormat
import dev.coda.core.subsonic.Album
import dev.coda.core.subsonic.AlbumListType
import dev.coda.core.subsonic.AlbumPage
import dev.coda.core.subsonic.Artist
import dev.coda.core.subsonic.ArtistProjection
import dev.coda.core.subsonic.ArtistReference
import dev.coda.core.subsonic.Playlist
import dev.coda.core.subsonic.Song
import dev.coda.store.LibraryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

/** The offline views' queries over the catalogue (synthetic library, bundled SQLite). */
class OfflineCatalogueTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var store: LibraryStore

    @After fun close() = store.close()

    private fun build(path: String): LibraryDatabase =
        LibraryDatabase.open(Room.databaseBuilder<LibraryDatabase>(path), BundledSQLiteDriver(), Dispatchers.IO)

    private val nova = Artist("ar-1", "Nova Lights", albumCount = 9, coverArt = "ar-1-img", sortName = "nova lights")
    private val zed = Artist("ar-2", "Zed Ensemble", albumCount = 4)
    private fun song(id: String, album: Album, track: Int, artist: ArtistReference = ArtistReference(album.artistId!!, album.artistName)) =
        Song(id, "Song $id", album = album.name, artist = artist.name, albumId = album.id, artistId = artist.id, track = track, discNumber = 1,
            artists = listOf(artist))

    // al-1: 3 tracks, 2 on the phone (partial). al-2: 1 of 1. al-3 (Zed): 1 of 2, track 2 sung by Nova (Appears on). al-4: none here.
    private val a1 = Album("al-1", "Bright", artist = "Nova Lights", artistId = "ar-1", songCount = 3, created = "2020-01-01T00:00:00Z",
        year = 2001, playCount = 5, userRating = 3)
    private val a2 = Album("al-2", "Arc", artist = "Nova Lights", artistId = "ar-1", songCount = 1, created = "2022-01-01T00:00:00Z",
        year = 2011, playCount = 9)
    private val a3 = Album("al-3", "Cold", artist = "Zed Ensemble", artistId = "ar-2", songCount = 2, created = "2021-01-01T00:00:00Z",
        year = 1999, userRating = 5)
    private val a4 = Album("al-4", "Dust", artist = "Zed Ensemble", artistId = "ar-2", songCount = 1)
    private val pages = listOf(
        AlbumPage(a1, listOf(song("s-1", a1, 1), song("s-2", a1, 2), song("s-3", a1, 3))),
        AlbumPage(a2, listOf(song("s-4", a2, 1))),
        AlbumPage(a3, listOf(song("s-5", a3, 1), song("s-6", a3, 2, ArtistReference("ar-1", "Nova Lights")))),
        AlbumPage(a4, listOf(song("s-7", a4, 1))),
    )
    private val onPhone = setOf("s-1", "s-3", "s-4", "s-6")

    private fun cached(song: Song) = CachedTrack(song.id, "${song.id}.orig", StreamFormat.RAW, 10, 0, meta = TrackMeta.from(song))

    /** Fills the catalogue the way the app does: album pages saved, then a collector run over the cache. */
    private fun fill(records: List<String> = onPhone.toList() + "s-7", sets: List<KeptItem> = emptyList()) = runBlocking {
        store = LibraryStore(File(tmp.root, "library.db"), ::build)
        val json = CatalogueCollector.json
        for (page in pages) store.mirrorPage("album/${page.album.id}", json.encodeToString(AlbumSnapshot.serializer(), AlbumSnapshot(page.album, page.songs)))
        store.mirrorPage("artists", json.encodeToString(ArtistsSnapshot.serializer(), ArtistsSnapshot(listOf(nova, zed))))
        store.awaitWrites()
        val all = pages.flatMap { it.songs }.associateBy { it.id }
        CatalogueCollector(store, { error("no server") }).collect(records.map { cached(all.getValue(it)) }, sets) { false }
    }

    @Test fun albumsWithATrackOnThePhoneAndTheirCounts() = runBlocking {
        fill()
        // s-7 is in the cache index but not counted as on the phone here: al-4 then has nothing and stays out.
        val shelf = OfflineCatalogue(store).shelf(onPhone)
        assertEquals(listOf("al-1", "al-2", "al-3"), shelf.albums.map { it.id })
        assertEquals(OfflineCatalogue.Count(2, 3), shelf.counts["al-1"])
        assertTrue(shelf.counts["al-1"]!!.partial)
        assertEquals("2 of 3", shelf.counts["al-1"]!!.label)
        assertFalse(shelf.counts["al-2"]!!.partial)
        assertEquals(OfflineCatalogue.Count(1, 2), shelf.counts["al-3"])
    }

    /** "Only complete albums": partial albums leave the shelf, its artists and Search; a played, never kept album stays. */
    @Test fun completeOnlyKeepsAlbumsWithEveryTrackHereKeptOrNot() = runBlocking {
        fill() // no kept sets: al-2 is complete only because its one track is in the cache (played).
        val catalogue = OfflineCatalogue(store)
        val shelf = catalogue.shelf(onPhone, playedAt = mapOf("s-4" to 20L, "s-1" to 10L))
        val complete = shelf.completeOnly()
        assertEquals(listOf("al-2"), complete.albums.map { it.id })
        assertEquals(setOf("al-2"), complete.ids)
        assertEquals(mapOf("al-2" to 20L), complete.played)
        assertEquals(listOf("al-2"), complete.ordered(AlbumListType.NEWEST).map { it.id })
        assertEquals(listOf("al-2"), complete.recentlyPlayed(10).map { it.id })
        // Counts follow: Zed's only album here was partial.
        assertEquals(listOf("ar-1"), catalogue.artists(complete).map { it.id })
        assertEquals(1, catalogue.artists(complete).single().albumCount)
        assertEquals(listOf("al-2"), catalogue.artist("ar-1", complete)!!.first.albums.map { it.id })
        // Search and "Go to artist": Zed, whose only album here is partial, is not offered; on the full shelf he is.
        assertEquals(setOf("ar-1"), complete.artistIds)
        assertEquals(emptyList<String>(), catalogue.search("Zed", complete, onPhone).artists.map { it.id })
        assertEquals(listOf("ar-2"), catalogue.search("Zed", shelf, onPhone).artists.map { it.id })
        assertEquals(listOf("ar-1"), catalogue.search("Nova", complete, onPhone).artists.map { it.id })
        // Search: the partial album is not found, the complete one is.
        assertEquals(emptyList<String>(), catalogue.search("Bright", complete, onPhone).albums.map { it.id })
        assertEquals(listOf("al-1"), catalogue.search("Bright", shelf, onPhone).albums.map { it.id })
        // Off (the shelf as read) is unchanged.
        assertEquals(listOf("al-1", "al-2", "al-3"), shelf.albums.map { it.id })
        // Every track of al-1 here: it counts as complete too.
        assertEquals(listOf("al-1", "al-2"), catalogue.shelf(onPhone + "s-2").completeOnly().albums.map { it.id })
    }

    @Test fun everySortWorksOnThePhonesAlbums() = runBlocking {
        fill()
        val shelf = OfflineCatalogue(store).shelf(onPhone)
        fun order(type: AlbumListType) = shelf.ordered(type, locale = Locale.ROOT).map { it.id }
        assertEquals(listOf("al-2", "al-1", "al-3"), order(AlbumListType.ALPHABETICAL))
        assertEquals(listOf("al-2", "al-3", "al-1"), order(AlbumListType.NEWEST))
        assertEquals(listOf("al-2", "al-1", "al-3"), order(AlbumListType.RECENT_RELEASES))
        assertEquals(listOf("al-2", "al-1"), order(AlbumListType.MOST_PLAYED))
        assertEquals(listOf("al-3", "al-1"), order(AlbumListType.TOP_RATED))
        assertEquals(setOf("al-1", "al-2", "al-3"), shelf.ordered(AlbumListType.RANDOM, kotlin.random.Random(7)).map { it.id }.toSet())
        assertEquals(emptyList<String>(), order(AlbumListType.RECENTLY_PLAYED))
    }

    @Test fun albumPageHasEveryTrackInOrderAndNothingWhenNoneIsHere() = runBlocking {
        fill()
        val catalogue = OfflineCatalogue(store)
        val (page, count) = catalogue.album("al-1", onPhone)!!
        assertEquals(listOf("s-1", "s-2", "s-3"), page.songs.map { it.id })
        assertEquals("Bright", page.album.name)
        assertEquals(OfflineCatalogue.Count(2, 3), count)
        assertNull(catalogue.album("al-4", onPhone))
        assertNull(catalogue.album("nope", onPhone))
    }

    @Test fun artistsAreTheAlbumArtistsOnThePhone() = runBlocking {
        fill()
        val artists = OfflineCatalogue(store).artists(setOf("s-1"))
        assertEquals(listOf("ar-1"), artists.map { it.id })
        // The server's object (image, sort name) with the count of albums on the phone.
        assertEquals("ar-1-img", artists.single().coverArt)
        assertEquals(1, artists.single().albumCount)
        assertEquals(setOf("ar-1", "ar-2"), OfflineCatalogue(store).artists(onPhone).map { it.id }.toSet())
    }

    @Test fun artistPageHasOwnAlbumsAndAppearsOn() = runBlocking {
        fill()
        val catalogue = OfflineCatalogue(store)
        val (page, appears) = catalogue.artist("ar-1", onPhone)!!
        assertEquals("Nova Lights", page.artist.name)
        assertEquals(listOf("al-1", "al-2"), page.albums.map { it.id }.sorted())
        assertEquals(listOf("al-3"), appears.map { it.id })
        val projection = ArtistProjection.from(page, appears)
        assertEquals(listOf("al-3"), projection.appearances.map { it.id })
        // Zed: own album al-3 only (al-4 has nothing on the phone).
        assertEquals(listOf("al-3"), catalogue.artist("ar-2", onPhone)!!.first.albums.map { it.id })
        assertNull(catalogue.artist("ar-2", setOf("s-1")))
    }

    @Test fun keptPlaylistsOnlyWithTheirCounts() = runBlocking {
        val members = listOf("s-1", "s-2", "s-6")
        val all = pages.flatMap { it.songs }.associateBy { it.id }
        val set = KeptItem("playlist/pl-1", members.map { KeptTrack.from(all.getValue(it)) }, "Road trip")
        fill(sets = listOf(set))
        val catalogue = OfflineCatalogue(store)
        val (list, counts) = catalogue.playlists(onPhone)
        assertEquals(listOf("pl-1"), list.map { it.id })
        assertEquals("Road trip", list.single().name)
        assertEquals(OfflineCatalogue.Count(2, 3), counts["pl-1"])
        val (page, count) = catalogue.playlist("pl-1", onPhone)!!
        assertEquals(members, page.songs.map { it.id })
        assertEquals("2 of 3", count.label)
        assertNull(catalogue.playlist("pl-2", onPhone))
    }

    @Test fun playlistPrefersItsSavedPage() = runBlocking {
        val all = pages.flatMap { it.songs }.associateBy { it.id }
        val set = KeptItem("playlist/pl-1", listOf(KeptTrack.from(all.getValue("s-4"))), "Old name")
        store = LibraryStore(File(tmp.root, "x.db"), ::build)
        store.mirrorPage("playlist/pl-1", CatalogueCollector.json.encodeToString(PlaylistSnapshot.serializer(),
            PlaylistSnapshot(Playlist("pl-1", "Saved name", coverArt = "pl-cover"), listOf(all.getValue("s-4")))))
        store.awaitWrites()
        CatalogueCollector(store, { error("no server") }).collect(listOf(cached(all.getValue("s-4"))), listOf(set)) { false }
        val (page, _) = OfflineCatalogue(store).playlist("pl-1", setOf("s-4"))!!
        assertEquals("Saved name", page.playlist.name)
        assertEquals("pl-cover", page.playlist.coverArt)
    }

    @Test fun aPlaylistWithoutAStoredCoverTakesItsFirstSongWithAStoredCover() = runBlocking {
        // Kept offline (no cover id of its own); s-4's album al-2 is not stored, s-1's al-1 is.
        val members = listOf("s-4", "s-1", "s-6")
        val all = pages.flatMap { it.songs }.associateBy { it.id }
        fill(sets = listOf(KeptItem("playlist/pl-1", members.map { KeptTrack.from(all.getValue(it)) }, "Road trip")))
        val stored = setOf("al-1", "al-3")
        val catalogue = OfflineCatalogue(store) { it in stored }
        assertEquals("al-1", catalogue.playlists(onPhone).first.single().coverArt)
        val (page, _) = catalogue.playlist("pl-1", onPhone)!!
        assertEquals("al-1", page.playlist.coverArt)
        // Nothing stored: the placeholder (no id).
        assertNull(OfflineCatalogue(store) { false }.playlists(onPhone).first.single().coverArt)
        assertNull(OfflineCatalogue(store) { false }.playlist("pl-1", onPhone)!!.first.playlist.coverArt)
    }

    @Test fun aSavedPlaylistKeepsItsOwnCoverOnlyWhenStored() = runBlocking {
        val all = pages.flatMap { it.songs }.associateBy { it.id }
        val set = KeptItem("playlist/pl-1", listOf(KeptTrack.from(all.getValue("s-4")), KeptTrack.from(all.getValue("s-1"))), "Old name")
        store = LibraryStore(File(tmp.root, "y.db"), ::build)
        store.mirrorPage("playlist/pl-1", CatalogueCollector.json.encodeToString(PlaylistSnapshot.serializer(),
            PlaylistSnapshot(Playlist("pl-1", "Saved name", coverArt = "pl-cover"), listOf(all.getValue("s-4"), all.getValue("s-1")))))
        store.awaitWrites()
        CatalogueCollector(store, { error("no server") }).collect(listOf(cached(all.getValue("s-4"))), listOf(set)) { false }
        assertEquals("pl-cover", OfflineCatalogue(store) { it == "pl-cover" || it == "al-1" }.playlists(setOf("s-4")).first.single().coverArt)
        assertEquals("al-1", OfflineCatalogue(store) { it == "al-1" }.playlists(setOf("s-4")).first.single().coverArt)
        assertEquals("al-1", OfflineCatalogue(store) { it == "al-1" }.playlist("pl-1", setOf("s-4"))!!.first.playlist.coverArt)
    }

    @Test fun aMinimalAlbumShowsWhatIsHereWithoutACount() = runBlocking {
        store = LibraryStore(File(tmp.root, "m.db"), ::build)
        // No album page saved and no server: the collector builds a minimal entry from the track's own metadata.
        val track = CachedTrack("s-9", "s-9.orig", StreamFormat.RAW, 10, 0, meta = TrackMeta("Lone", "Nova Lights", "Unseen", "al-9", "al-9", 100, 1, 1))
        CatalogueCollector(store, { error("no server") }).collect(listOf(track), emptyList()) { false }
        val shelf = OfflineCatalogue(store).shelf(setOf("s-9"))
        assertEquals(listOf("al-9"), shelf.albums.map { it.id })
        assertFalse(shelf.counts["al-9"]!!.partial)
        assertEquals(listOf("s-9"), OfflineCatalogue(store).album("al-9", setOf("s-9"))!!.first.songs.map { it.id })
    }

    /** A minimal song built from the cache row has its stored ReplayGain, so offline playback never takes the fallback. */
    @Test fun aMinimalSongHasTheGainStoredOnItsCacheRow() = runBlocking {
        store = LibraryStore(File(tmp.root, "rg.db"), ::build)
        val rg = dev.coda.core.playback.ReplayGainMetadata(trackGain = -4.5, albumGain = -6.0, trackPeak = 0.9, albumPeak = 0.95)
        val track = CachedTrack("s-9", "s-9.orig", StreamFormat.RAW, 10, 0,
            meta = TrackMeta("Lone", "Nova Lights", "Unseen", "al-9", "al-9", 100, 1, 1, replayGain = rg))
        CatalogueCollector(store, { error("no server") }).collect(listOf(track), emptyList()) { false }
        val song = OfflineCatalogue(store).album("al-9", setOf("s-9"))!!.first.songs.single()
        assertEquals(rg, song.replayGain)
        val policy = dev.coda.core.playback.ReplayGainPolicy
        assertEquals(-4.5, policy.gainDb(true, null, song.replayGain), 1e-9)
        assertNotEquals(policy.fallbackDb, policy.gainDb(true, null, song.replayGain), 1e-9)
    }

    /**
     * A track whose album page never came (e.g. the one song of a queue handed off from the server, its album not
     * fetched yet) has a minimal entry that knows only the tracks here. Its total is unknown, so "Only complete albums"
     * hides it; once the album's page is here its real count decides ("1 of 3": still hidden).
     */
    @Test fun completeOnlyHidesAMinimalAlbumWhoseTotalIsUnknown() = runBlocking {
        store = LibraryStore(File(tmp.root, "nf3.db"), ::build)
        val track = cached(pages[0].songs[0]) // s-1 of al-1 (3 tracks); no album page saved, no server
        CatalogueCollector(store, { error("no server") }).collect(listOf(track), emptyList()) { false }
        val catalogue = OfflineCatalogue(store)
        val shelf = catalogue.shelf(setOf("s-1"))
        assertEquals(listOf("al-1"), shelf.albums.map { it.id })
        assertFalse(shelf.counts["al-1"]!!.partial) // no "1 of ?" label: the list shows it as before
        assertEquals(emptyList<String>(), shelf.completeOnly().albums.map { it.id })
        assertEquals(emptySet<String>(), shelf.completeOnly().artistIds)
        // The album page arrives (the server answers): the full entry says 1 of 3, still not complete.
        CatalogueCollector(store, { pages[0] }).collect(listOf(track), emptyList()) { true }
        val full = catalogue.shelf(setOf("s-1"))
        assertEquals(OfflineCatalogue.Count(1, 3), full.counts["al-1"])
        assertEquals(emptyList<String>(), full.completeOnly().albums.map { it.id })
    }

    @Test fun aMinimalAlbumBuiltOverSeveralRunsIsInDiscAndTrackOrder() = runBlocking {
        // Tracks cached one at a time (disc 2 first, then disc 1 track 2, then track 1) arrive in that order.
        store = LibraryStore(File(tmp.root, "inc.db"), ::build)
        fun track(id: String, track: Int, disc: Int) =
            CachedTrack(id, "$id.orig", StreamFormat.RAW, 10, 0, meta = TrackMeta("T $id", "Nova Lights", "Unseen", "al-9", "al-9", 100, track, disc))
        val runs = listOf(track("s-21", 1, 2), track("s-12", 2, 1), track("s-11", 1, 1))
        for (i in runs.indices) CatalogueCollector(store, { error("no server") }).collect(runs.take(i + 1), emptyList()) { false }
        val ids = runs.mapTo(HashSet()) { it.songId }
        assertEquals(listOf("s-11", "s-12", "s-21"), OfflineCatalogue(store).album("al-9", ids)!!.first.songs.map { it.id })
    }

    // OF-1c-c

    @Test fun searchFindsOnlyWhatIsOnThePhone() = runBlocking {
        fill()
        val catalogue = OfflineCatalogue(store)
        val shelf = catalogue.shelf(onPhone)
        // "song" matches every title: only the songs here.
        assertEquals(setOf("s-1", "s-3", "s-4", "s-6"), catalogue.search("song", shelf, onPhone).songs.map { it.id }.toSet())
        // al-4 "Dust" has nothing here.
        assertTrue(catalogue.search("dust", shelf, onPhone).albums.isEmpty())
        assertEquals(listOf("al-1"), catalogue.search("bri", shelf, onPhone).albums.map { it.id })
        // Zed has al-3 here; with only s-1 on the phone neither Zed nor al-3 are found.
        assertEquals(listOf("ar-2"), catalogue.search("zed", shelf, onPhone).artists.map { it.id })
        val one = setOf("s-1")
        val small = catalogue.search("zed", catalogue.shelf(one), one)
        assertTrue(small.artists.isEmpty() && small.albums.isEmpty() && small.songs.isEmpty())
        // Nova: an album artist here.
        assertEquals(listOf("ar-1"), catalogue.search("nova", catalogue.shelf(one), one).artists.map { it.id })
        assertEquals(dev.coda.core.subsonic.SearchResults(), catalogue.search("  ", shelf, onPhone))
    }

    @Test fun searchFindsATrackOnlyArtistHere() = runBlocking {
        fill()
        // Only s-6 (Nova on Zed's al-3) is here: Nova has no album here but a track, Zed owns al-3.
        val one = setOf("s-6")
        val catalogue = OfflineCatalogue(store)
        val shelf = catalogue.shelf(one)
        assertEquals(setOf("ar-1", "ar-2"), shelf.artistIds)
        assertEquals(listOf("ar-1"), catalogue.search("nova", shelf, one).artists.map { it.id })
    }

    /**
     * A large library and a small cache. 500 albums, 1000 songs and 500 artists all match "love"; 600 songs
     * are in the catalogue and only the last ones by title are here. Every match here is found (not the first 400 matches, then filtered).
     */
    @Test fun searchInALargeLibraryFindsWhatIsHere() = runBlocking {
        store = LibraryStore(File(tmp.root, "big.db"), ::build)
        val json = CatalogueCollector.json
        val bands = (0 until 500).map { Artist("b-%03d".format(it), "Love Band %03d".format(it)) }
        val guest = ArtistReference("guest", "Love Zz Guest")
        val big = bands.map { band ->
            val n = band.id.removePrefix("b-")
            val album = Album("al-$n", "Love Album $n", artist = band.name, artistId = band.id, songCount = 2)
            AlbumPage(album, (1..2).map { k ->
                val credit = if (n == "498" && k == 2) guest else ArtistReference(band.id, band.name)
                Song("s-$n-$k", "Love Song $n-$k", album = album.name, artist = credit.name, albumId = album.id, artistId = credit.id,
                    track = k, discNumber = 1, artists = listOf(credit))
            })
        }
        for (page in big) store.mirrorPage("album/${page.album.id}", json.encodeToString(AlbumSnapshot.serializer(), AlbumSnapshot(page.album, page.songs)))
        store.mirrorPage("artists", json.encodeToString(ArtistsSnapshot.serializer(), ArtistsSnapshot(bands)))
        store.awaitWrites()
        val all = big.flatMap { it.songs }.associateBy { it.id }
        // The catalogue run saw 600 cached songs (300 albums); the searches below take a small and a large cache from them.
        val many = (200 until 500).flatMap { n -> (1..2).map { "s-%03d-$it".format(n) } }.toSet()
        CatalogueCollector(store, { error("no server") }).collect(many.map { cached(all.getValue(it)) }, emptyList()) { false }
        val here = setOf("s-499-1", "s-499-2", "s-498-2")
        val catalogue = OfflineCatalogue(store)
        val shelf = catalogue.shelf(here)
        val found = catalogue.search("love", shelf, here)
        assertEquals(listOf("s-498-2", "s-499-1", "s-499-2"), found.songs.map { it.id })
        assertEquals(listOf("al-498", "al-499"), found.albums.map { it.id })
        // b-498 and b-499 own an album here; the guest has only a track here.
        assertEquals(listOf("b-498", "b-499", "guest"), found.artists.map { it.id })

        // More ids than one IN list holds (chunks): the first 40 by title among 600 cached songs.
        val wide = catalogue.search("love", catalogue.shelf(many), many)
        assertEquals(many.sortedBy { all.getValue(it).title }.take(40), wide.songs.map { it.id })
        assertEquals(24, wide.albums.size)
        assertEquals("al-200", wide.albums.first().id)
    }

    @Test fun recentlyPlayedUsesThePhonesPlayDates() = runBlocking {
        fill()
        val shelf = OfflineCatalogue(store).shelf(onPhone, mapOf("s-1" to 10L, "s-3" to 30L, "s-6" to 20L, "s-2" to 99L))
        // s-2 is not here: al-1's latest play here is s-3's. al-2 was never played on the phone.
        assertEquals(mapOf("al-1" to 30L, "al-3" to 20L), shelf.played)
        assertEquals(listOf("al-1", "al-3"), shelf.recentlyPlayed(20).map { it.id })
        assertEquals(listOf("al-1"), shelf.recentlyPlayed(1).map { it.id })
    }

    /** Offline, the Albums tab's Recently Played sort and its sections follow the phone's play dates, not the server's. */
    @Test fun theRecentlyPlayedSortUsesThePhonesPlayDates() = runBlocking {
        fill()
        val day = 86_400_000L
        val now = java.time.Instant.parse("2026-11-01T12:00:00Z").toEpochMilli()
        // The server says al-2 was played last and al-1 long ago; on the phone al-1 was played today, al-3 two days ago, al-2 never.
        val server = listOf(a1.copy(played = "2020-01-01T00:00:00Z"), a2.copy(played = "2026-11-01T11:00:00Z"), a3.copy(played = "2026-10-31T00:00:00Z"))
        val shelf = OfflineCatalogue.Shelf(server, emptyMap(), played = mapOf("al-1" to now, "al-3" to now - 2 * day))
        val ordered = shelf.ordered(AlbumListType.RECENTLY_PLAYED, locale = Locale.ROOT)
        assertEquals(listOf("al-1", "al-3"), ordered.map { it.id })
        // The sections are computed from the same list, so they read the phone's dates too.
        assertEquals(listOf(java.time.Instant.ofEpochMilli(now).toString(), java.time.Instant.ofEpochMilli(now - 2 * day).toString()),
            ordered.map { it.played })
    }

    @Test fun anAlbumPageShowsOnlyItsOwnTrackList() = runBlocking {
        fill()
        // A song the server dropped from al-1 since (still in the songs table, not in the album's list) adds no row.
        store.use(Unit) { db -> db.catalogue().upsertSongs(listOf(db.catalogue().song("s-1")!!.copy(id = "s-gone", track = 9))) }
        val (page, count) = OfflineCatalogue(store).album("al-1", onPhone)!!
        assertEquals(listOf("s-1", "s-2", "s-3"), page.songs.map { it.id })
        assertEquals(page.songs.size, count.total)
    }

    @Test fun aPlaylistsRowsAndCountFollowTheKeptMembers() = runBlocking {
        val all = pages.flatMap { it.songs }.associateBy { it.id }
        val members = listOf("s-1", "s-2", "s-6")
        val set = KeptItem("playlist/pl-1", members.map { KeptTrack.from(all.getValue(it)) }, "Road trip")
        fill(sets = listOf(set))
        // The page saved later (the server's list changed: s-4 added) before the next catalogue run: the rows still follow
        // the kept set's members, like the Playlists tab's count.
        store.mirrorPage("playlist/pl-1", CatalogueCollector.json.encodeToString(PlaylistSnapshot.serializer(),
            PlaylistSnapshot(Playlist("pl-1", "Road trip"), listOf("s-1", "s-4", "s-2", "s-6").map { all.getValue(it) })))
        store.awaitWrites()
        val catalogue = OfflineCatalogue(store)
        val (page, count) = catalogue.playlist("pl-1", onPhone)!!
        assertEquals(members, page.songs.map { it.id })
        assertEquals(catalogue.playlists(onPhone).second["pl-1"], count)
        assertEquals("2 of 3", count.label)
    }

    @Test fun thePlaylistRowSaysOneOfTwoTracks() {
        store = LibraryStore(File(tmp.root, "n.db"), ::build)
        val line = PlaylistOrder.metadata(Playlist("pl-1", "Mix", songCount = 2, duration = 600), PlaylistSort.NAME, locale = Locale.ROOT, tracks = "1 of 2 tracks")
        assertEquals("1 of 2 tracks · 10 min", line)
    }
}
