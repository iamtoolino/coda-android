package dev.coda.core.cache

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One account's offline storage on the phone; a verified sign-in as another account deletes the rest. */
class AccountStorageTest {
    private val root = Files.createTempDirectory("of1e").toFile()
    private val noBackup = File(root, "no_backup")
    private val cache = File(root, "cache")
    private val shared = File(root, "shared_prefs")
    private val files = File(root, "files")
    private val a = TrackCache.serverKey("https://a.example", "synthetic")
    private val b = TrackCache.serverKey("https://b.example", "synthetic")
    private val c = TrackCache.serverKey("https://a.example", "other")
    private fun keep(key: String) = AccountStorage.Keep(key, "5c0b${key.take(4)}")
    private fun storage() = AccountStorage(noBackup, cache, Dispatchers.Unconfined)

    @After fun cleanUp() { root.deleteRecursively() }

    /** Every account-bound store of [key], with a byte in each file. */
    private fun account(key: String): List<File> = listOf(
        File(noBackup, "music/$key/index.json"), File(noBackup, "music/$key/abc.opus"), File(noBackup, "music/$key/abc.opus.part"),
        File(noBackup, "music/$key/sets/kept-albums.json"), File(noBackup, "music/$key/waveforms/abc.waveform"),
        File(noBackup, "library/$key/library.db"), File(noBackup, "library/$key/library.db-wal"), File(noBackup, "library/$key/library.db-shm"),
        File(noBackup, "queue/$key.json"), File(noBackup, "queue/$key.json.bak"),
    ).onEach { it.parentFile.mkdirs(); it.writeText("x") }

    /** What is never account-bound: settings, recent servers, https history, consents, switches, the login, install key. */
    private fun settings(): List<File> = listOf(
        File(shared, "settings.xml"), File(shared, "library.xml"), File(shared, "track-cache.xml"), File(shared, "developer.xml"),
        File(files, "https-history"), File(noBackup, "plain-http-consent"), File(noBackup, "credentials.bin"), File(noBackup, "artwork-key"),
        File(noBackup, "music/README-not-a-key"), File(cache, "other-cache/x"),
    ).onEach { it.parentFile.mkdirs(); it.writeText("x") }

    private fun pictures() = File(cache, "artwork/0123abcd").also { it.parentFile.mkdirs(); it.writeText("x") }

    @Test fun sameAccountKeepsEveryStore() = runBlocking {
        val mine = account(a); val kept = settings(); val picture = pictures()
        storage().signedIn(keep(a))
        val again = storage().signedIn(keep(a))
        assertFalse(again.accountChanged)
        assertTrue((mine + kept + picture).all { it.isFile })
    }

    @Test fun differentAccountDeletesEveryAccountBoundStoreAndKeepsSettings() = runBlocking {
        val old = account(a); val kept = settings(); val picture = pictures()
        storage().signedIn(keep(a))
        val cleared = mutableListOf<AccountStorage.Keep>()
        val result = storage().signedIn(keep(b)) { cleared += it }
        assertTrue(result.accountChanged); assertTrue(result.complete)
        assertTrue("A's files are gone", old.none { it.exists() })
        assertFalse("A's folders are gone", File(noBackup, "music/$a").exists() || File(noBackup, "library/$a").exists())
        assertFalse("the picture cache is account-bound", picture.exists())
        assertTrue("settings stay", kept.all { it.isFile })
        assertEquals("in-process stores keep only B", listOf(keep(b)), cleared)
        assertFalse(storage().pending())
    }

    @Test fun signOutKeyIsRememberedSoTheNextAccountStillClearsThePictureCache() = runBlocking {
        storage().signedIn(keep(a))
        val picture = pictures() // A had no offline music, only pictures
        assertTrue(storage().signedIn(keep(b)).accountChanged)
        assertFalse(picture.exists())
    }

    @Test fun noCallMeansNothingIsDeleted() = runBlocking {
        // A failed or cancelled sign-in and a sign-out never call the pass; a restart without a marker resumes nothing.
        val old = account(a); val picture = pictures()
        assertNull(storage().resume(a))
        assertTrue((old + picture).all { it.isFile })
    }

    @Test fun interruptedWipeResumesAtTheNextStart() = runBlocking {
        val old = account(a)
        storage().signedIn(keep(a))
        account(b)
        // B signed in; the process died after the marker was written, before anything was deleted.
        File(noBackup, AccountStorage.MARKER).writeText("$b\n${keep(b).scrobbleKey}\n")
        assertTrue(storage().pending())
        val cleared = mutableListOf<AccountStorage.Keep>()
        val result = storage().resume(b) { cleared += it }!!
        assertTrue(result.complete)
        assertTrue(old.none { it.exists() })
        assertTrue(File(noBackup, "music/$b/index.json").isFile)
        assertEquals(listOf(keep(b)), cleared)
        assertFalse(storage().pending())
        assertNull("done once", storage().resume(b))
    }

    @Test fun unreadableMarkerDeletesNothing() = runBlocking {
        val old = account(a)
        File(noBackup, AccountStorage.MARKER).apply { parentFile.mkdirs(); writeText("garbage") }
        val result = storage().resume(a)!!
        assertFalse(result.accountChanged)
        assertTrue(old.all { it.isFile })
        assertFalse(storage().pending())
    }

    @Test fun leftoversOfAThirdKeyAreRemoved() = runBlocking {
        // Today's phones: A and C left folders behind, nothing remembered which account came last.
        val first = account(a); val third = account(c); val mine = account(b)
        val result = storage().signedIn(keep(b))
        assertTrue(result.accountChanged)
        assertTrue((first + third).none { it.exists() })
        assertTrue(mine.all { it.isFile })
    }

    @Test fun leftoversGoAlsoWhenTheSameAccountSignsInAgain() = runBlocking {
        storage().signedIn(keep(b))
        val third = account(c); val mine = account(b)
        storage().signedIn(keep(b))
        assertTrue(third.none { it.exists() })
        assertTrue(mine.all { it.isFile })
    }

    @Test fun inProcessFailureKeepsTheMarkerForTheNextStart() = runBlocking {
        val old = account(a)
        storage().signedIn(keep(a))
        val result = storage().signedIn(keep(b)) { error("store busy") }
        assertFalse(result.complete)
        assertTrue("files still go", old.none { it.exists() })
        assertTrue(storage().pending())
        assertTrue(storage().resume(b)!!.complete)
        assertFalse(storage().pending())
    }

    @Test fun staleMarkerNeverDeletesTheSignedInAccount() = runBlocking {
        // B signs in (pass interrupted), B signs out, A signs in, cold start: A's data stays.
        account(a)
        storage().signedIn(keep(a))
        val theirs = account(b)
        storage().signedIn(keep(b)) { error("store busy") }
        assertTrue(storage().pending())
        theirs.forEach { it.delete() }; File(noBackup, "music/$b").deleteRecursively()
        File(noBackup, "library/$b").deleteRecursively()
        val mine = account(a)
        assertFalse(storage().signedIn(keep(a)).accountChanged)
        assertFalse("the stale marker is gone", storage().pending())
        assertNull(storage().resume(a))
        assertTrue(mine.all { it.isFile })
    }

    @Test fun staleMarkerIsDroppedAtColdStartWithoutDeleting() = runBlocking {
        val mine = account(a)
        File(noBackup, AccountStorage.MARKER).writeText("$b\n${keep(b).scrobbleKey}\n")
        assertTrue(storage().resume(a)!!.deleted.isEmpty())
        assertTrue(mine.all { it.isFile })
        assertFalse(storage().pending())
        File(noBackup, AccountStorage.MARKER).writeText("$b\n${keep(b).scrobbleKey}\n")
        storage().resume(null)
        assertTrue("no stored login: nothing deleted", mine.all { it.isFile })
        assertFalse(storage().pending())
    }

    @Test fun sameAccountWithNoChangeClearsAStaleMarker() = runBlocking {
        storage().signedIn(keep(a))
        File(noBackup, AccountStorage.MARKER).writeText("$b\n${keep(b).scrobbleKey}\n")
        storage().signedIn(keep(a))
        assertFalse(storage().pending())
    }

    @Test fun ownFoldersUnderAnOldSpellingAreNeverDeleted() = runBlocking {
        val old = account(c)
        val mine = account(a)
        storage().signedIn(AccountStorage.Keep(a, "5c0b${a.take(4)}", sameAs = setOf(c)))
        assertTrue(old.all { it.isFile })
        assertTrue(mine.all { it.isFile })
    }

    @Test fun theSameUserInAnotherLetterCaseIsTheSameAccount() = runBlocking {
        val upper = TrackCache.serverKey("https://a.example", "Synthetic")
        val folded = TrackCache.serverKey("https://a.example", "synthetic")
        val first = account(upper)
        val picture = pictures()
        storage().signedIn(AccountStorage.Keep(upper, "5c0b1", setOf(folded), folded))
        // Signs in again as "synthetic": the earlier "Synthetic" folders and the picture cache stay.
        val result = storage().signedIn(AccountStorage.Keep(folded, "5c0b1", emptySet(), folded))
        assertFalse(result.accountChanged)
        assertTrue(first.all { it.isFile })
        assertTrue(picture.isFile)
    }

    /** The keep CodaApp builds for a login (the folded user name counts as this account). */
    private fun login(server: String, user: String): AccountStorage.Keep {
        val key = TrackCache.serverKey(server, user)
        val folded = TrackCache.serverKey(server, user.lowercase())
        return AccountStorage.Keep(key, "5c0b${folded.take(4)}", setOf(folded) - key, folded)
    }

    @Test fun repeatedReconnectsInAnotherLetterCaseNeverDeleteKeptMusic() = runBlocking {
        // Sol P1: Synthetic keeps music, then synthetic reconnects twice; the second must not target the first folder.
        val first = account(TrackCache.serverKey("https://a.example", "Synthetic"))
        storage().signedIn(login("https://a.example", "Synthetic"))
        for (round in 1..2) {
            val result = storage().signedIn(login("https://a.example", "synthetic"))
            assertFalse("reconnect $round", result.accountChanged)
            assertTrue("reconnect $round keeps Synthetic's music", first.all { it.isFile })
        }
    }

    @Test fun threeCaseVariantReconnectsKeepEveryFolderAndAnotherAccountStillWipesThemAll() = runBlocking {
        val upper = account(TrackCache.serverKey("https://a.example", "Synthetic"))
        storage().signedIn(login("https://a.example", "Synthetic"))
        val lower = account(TrackCache.serverKey("https://a.example", "synthetic"))
        storage().signedIn(login("https://a.example", "synthetic"))
        val shout = account(TrackCache.serverKey("https://a.example", "SYNTHETIC"))
        storage().signedIn(login("https://a.example", "SYNTHETIC"))
        storage().signedIn(login("https://a.example", "synthetic"))
        storage().signedIn(login("https://a.example", "Synthetic"))
        assertTrue("every spelling of the one account stays", (upper + lower + shout).all { it.isFile })
        val other = account(TrackCache.serverKey("https://a.example", "other"))
        val result = storage().signedIn(login("https://a.example", "other"))
        assertTrue(result.accountChanged); assertTrue(result.complete)
        assertTrue("a real other account still deletes them all", (upper + lower + shout).none { it.exists() })
        assertTrue(other.all { it.isFile })
    }

    @Test fun differentUserOnTheSameServerIsWiped() = runBlocking {
        val old = account(TrackCache.serverKey("https://a.example", "synthetic"))
        storage().signedIn(login("https://a.example", "synthetic"))
        val result = storage().signedIn(login("https://a.example", "other"))
        assertTrue(result.accountChanged)
        assertTrue(old.none { it.exists() })
    }

    @Test fun anotherServerAddressIsANewAccount() = runBlocking {
        // "keep the rule": another address or port counts as another account and wipes.
        val old = account(TrackCache.serverKey("https://a.example", "synthetic"))
        storage().signedIn(login("https://a.example", "synthetic"))
        assertTrue(storage().signedIn(login("https://a.example:8443", "synthetic")).accountChanged)
        assertTrue(old.none { it.exists() })
    }

    @Test fun anInterruptedPassKeepsEveryRememberedKeyOfTheAccount() = runBlocking {
        val upper = account(TrackCache.serverKey("https://a.example", "Synthetic"))
        storage().signedIn(login("https://a.example", "Synthetic"))
        storage().signedIn(login("https://a.example", "synthetic"))
        account(c) // a leftover makes the next reconnect run a pass, interrupted
        val next = login("https://a.example", "synthetic")
        storage().signedIn(next) { error("store busy") }
        assertTrue(storage().resume(next.key)!!.complete)
        assertFalse(File(noBackup, "music/$c").exists())
        storage().signedIn(next)
        assertTrue(upper.all { it.isFile })
    }

    @Test fun pictureCacheStaysWhenOnlyLeftoversAreFound() = runBlocking {
        val picture = pictures()
        account(c)
        storage().signedIn(keep(a)) // nothing remembered: leftovers only
        assertTrue(picture.isFile)
        assertTrue(File(noBackup, "music/$c").exists().not())
        account(b)
        storage().signedIn(keep(b)) // a real account change
        assertFalse(picture.exists())
    }
}
