package dev.coda.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recent servers: resolved URLs only, newest first, at most five, one per server. */
class RecentServersTest {
    private fun urls(list: List<RecentServer>) = list.map { it.url }

    @Test fun newestFirstAndCappedAtFive() {
        var list = emptyList<RecentServer>()
        (1..7).forEach { list = RecentServers.added(list, "https://s$it.example.org", it.toLong()) }
        assertEquals(RecentServers.LIMIT, list.size)
        assertEquals((7 downTo 3).map { "https://s$it.example.org" }, urls(list))
    }

    @Test fun signingInAgainMovesTheServerToTheTopWithoutADuplicate() {
        var list = emptyList<RecentServer>()
        list = RecentServers.added(list, "https://music.example.org", 1)
        list = RecentServers.added(list, "http://home.example.net:4533", 2)
        // Same resolved URL, written differently (host case, trailing slash).
        list = RecentServers.added(list, "https://Music.Example.org/", 3)
        assertEquals(listOf("https://Music.Example.org/", "http://home.example.net:4533"), urls(list))
        assertEquals(3L, list.first().usedAt)
    }

    @Test fun dedupeIsOnTheResolvedUrlSoSchemesAndPortsStayApart() {
        var list = emptyList<RecentServer>()
        list = RecentServers.added(list, "https://music.example.org", 1)
        list = RecentServers.added(list, "http://music.example.org", 2)
        list = RecentServers.added(list, "https://music.example.org:8443", 3)
        list = RecentServers.added(list, "https://music.example.org/navidrome", 4)
        assertEquals(4, list.size)
        assertTrue(RecentServers.same("HTTPS://music.example.org", "https://music.example.org/"))
        assertFalse(RecentServers.same("https://music.example.org", "http://music.example.org"))
    }

    @Test fun removeForgetsOneServer() {
        var list = emptyList<RecentServer>()
        listOf("https://a.example.org", "https://b.example.org", "https://c.example.org").forEachIndexed { i, url ->
            list = RecentServers.added(list, url, i.toLong())
        }
        list = RecentServers.removed(list, "https://B.example.org")
        assertEquals(listOf("https://c.example.org", "https://a.example.org"), urls(list))
        assertEquals(list, RecentServers.removed(list, "https://unknown.example.org"))
    }

    /** Review 3: recent servers never hold user info, a query or a fragment. */
    @Test fun neverUserInfoQueryOrFragment() {
        assertEquals(emptyList<RecentServer>(), RecentServers.added(emptyList(), "https://synthetic-user:synthetic-pass@music.example.org", 1))
        assertEquals(listOf("https://music.example.org/x"), urls(RecentServers.added(emptyList(), "https://music.example.org/x?u=a#f", 1)))
        assertEquals(emptyList<RecentServer>(), RecentServers.decode("""[{"url":"https://synthetic-user:synthetic-pass@music.example.org","usedAt":1}]"""))
    }

    @Test fun storedAsUrlAndTimeOnly() {
        val list = listOf(RecentServer("https://music.example.org", 42), RecentServer("http://home.example.net", 7))
        val encoded = RecentServers.encode(list)
        val array = org.json.JSONArray(encoded)
        (0 until array.length()).forEach { assertEquals(setOf("url", "usedAt"), array.getJSONObject(it).keys().asSequence().toSet()) }
        assertEquals(list, RecentServers.decode(encoded))
        assertEquals(emptyList<RecentServer>(), RecentServers.decode("not json"))
        assertEquals(emptyList<RecentServer>(), RecentServers.decode(null))
    }
}
