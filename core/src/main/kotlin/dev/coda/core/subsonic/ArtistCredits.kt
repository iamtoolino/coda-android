package dev.coda.core.subsonic

/**
 * A track's display artist ("A feat. B", "A & B") cut into its artists and the text between them, so Now Playing
 * can make each artist tappable and keep the text and separators exactly as the server wrote them.
 */
object ArtistCredits {
    /** A piece of the display text; [artistId] is set for an artist's name, null for text between them. */
    data class Segment(val text: String, val artistId: String? = null)

    /**
     * Cuts [display] around the names of [artists] (the OpenSubsonic `artists` array of the song, in credit order). Null
     * unless there are at least two distinct artists with ids and every name is found in [display], in that order: the
     * caller then keeps today's behaviour (the whole text opens the first artist).
     */
    fun segments(display: String, artists: List<ArtistReference>?): List<Segment>? {
        val distinct = artists.orEmpty().filter { it.id.isNotEmpty() && it.name.isNotEmpty() }.distinctBy { it.id }
        if (distinct.size < 2) return null
        val result = ArrayList<Segment>()
        var at = 0
        for (artist in distinct) {
            val found = display.indexOf(artist.name, at, ignoreCase = true)
            if (found < 0) return null
            if (found > at) result += Segment(display.substring(at, found))
            result += Segment(display.substring(found, found + artist.name.length), artist.id)
            at = found + artist.name.length
        }
        if (at < display.length) result += Segment(display.substring(at))
        return result
    }
}
