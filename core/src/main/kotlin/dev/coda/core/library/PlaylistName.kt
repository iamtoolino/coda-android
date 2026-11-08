package dev.coda.core.library

import dev.coda.core.subsonic.Playlist

/**
 * Navidrome smart playlists are named "[Auto] Recently Added" by convention, and the PO's own set lists
 * "[Setlist] Friday". The app shows the name without the marker and flags the playlist as [auto] or [setlist]; the server
 * name is never changed (rename, Android Auto and search use the full one).
 */
data class PlaylistTitle(val display: String, val auto: Boolean, val setlist: Boolean = false)

object PlaylistName {
    private const val AUTO = "[Auto]"
    private const val SETLIST = "[Setlist]"

    /**
     * A marker counts only at the very start (after leading blanks), in any case, with or without a space after it; only the
     * first one is removed. A name that merely contains a marker later ("My [Auto] mix") or that is nothing but the marker is
     * left exactly as it is.
     */
    fun parse(name: String): PlaylistTitle {
        val start = name.trimStart()
        val auto = start.startsWith(AUTO, ignoreCase = true)
        val setlist = !auto && start.startsWith(SETLIST, ignoreCase = true)
        if (!auto && !setlist) return PlaylistTitle(name, false)
        val rest = start.substring(if (auto) AUTO.length else SETLIST.length).trim()
        return if (rest.isEmpty()) PlaylistTitle(name, false) else PlaylistTitle(rest, auto, setlist)
    }

    fun parse(playlist: Playlist) = parse(playlist.name)
}
