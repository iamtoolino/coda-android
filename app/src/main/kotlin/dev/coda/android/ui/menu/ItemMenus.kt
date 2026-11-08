package dev.coda.android.ui.menu

/**
 * One action list per item type (menus stay common per item category, in code too). Every
 * album, track, playlist and artist opens the same sheet with the same entries, names, order and icons from every
 * screen; a screen only passes its context, which can hide an entry or add one at the end.
 *
 * Shared order: album rating (stars row, albums only) → play actions → Add to playlist / Keep offline → Go to album /
 * artist → edit, destructive entries last. "Sheet: Grouped buttons": the first play actions (at most three, only ones
 * that start or queue playback) form the button row; the first is the filled main action.
 */
enum class MenuAction(val label: String, val tag: String) {
    PLAY("Play", "menuPlay"),
    BEST_OF("Play best", "menuBestOf"),
    NEWEST("Play newest", "menuNewest"),
    SHUFFLE("Shuffle", "menuShuffle"),
    PLAY_NEXT("Play next", "menuPlayNext"),
    ADD_TO_QUEUE("Add to queue", "menuAddToQueue"),
    ADD_TO_PLAYLIST("Add to playlist…", "menuAddToPlaylist"),
    KEEP_OFFLINE("Prefer offline", "menuKeepOffline"),
    GO_TO_ALBUM("Go to album", "menuGoToAlbum"),
    GO_TO_ARTIST("Go to artist", "menuGoToArtist"),
    RENAME("Rename", "menuRename"),
    DELETE("Delete", "menuDelete"),
    // A resumable album's first group (see [ItemMenus.album]).
    RESUME("Resume album", "menuResume"),
    APPEND_REMAINING("Add remaining to queue", "menuAppendRemaining"),
    // Context extras: at most one, at the end.
    REMOVE_FROM_PLAYLIST("Remove from playlist", "menuRemoveFromPlaylist"),
}

/** A sheet's content: the stars row (albums only), the button row, then the list in hairline groups. */
data class MenuSpec(val rating: Boolean, val buttons: List<MenuAction>, val groups: List<List<MenuAction>>) {
    val all get() = buttons + groups.flatten()
}

/** What a screen may say about an album: hide Go to artist (its own artist page, a compilation). */
data class AlbumMenuContext(val onArtistPage: Boolean = false, val compilation: Boolean = false)

/** A track: hide Go to album on its own album page; add Remove from playlist on a playlist you can edit. */
data class TrackMenuContext(val onAlbumPage: Boolean = false, val hasArtist: Boolean = true, val removable: Boolean = false)

/** A playlist: Rename and Delete only for a playlist you can edit; Shuffle only where the page offers it (two or more shuffle units). */
data class PlaylistMenuContext(val editable: Boolean = true, val shuffle: Boolean = true)

/**
 * Hid Keep offline for 1.0; RC-1a shows it again as "Prefer offline" in the album and playlist
 * menus (one switch for both). Internal names (KEEP_OFFLINE, KeptItem, …) stay.
 */
internal const val KEEP_OFFLINE_IN_MENUS = true

object ItemMenus {
    /**
     * Album: no Shuffle, one Prefer offline toggle, no Refresh. A [resumable] album (the sheet
     * decides, from any screen: [dev.coda.android.ui.album.albumResumeIndex]) gets Resume album and Add remaining to queue
     * as the first group under the buttons; otherwise the sheet is unchanged.
     */
    fun album(context: AlbumMenuContext = AlbumMenuContext(), resumable: Boolean = false) = MenuSpec(rating = true,
        buttons = listOf(MenuAction.PLAY, MenuAction.PLAY_NEXT, MenuAction.ADD_TO_QUEUE),
        groups = listOf(
            if (resumable) listOf(MenuAction.RESUME, MenuAction.APPEND_REMAINING) else emptyList(),
            listOfNotNull(MenuAction.ADD_TO_PLAYLIST, MenuAction.KEEP_OFFLINE.takeIf { KEEP_OFFLINE_IN_MENUS }),
            listOfNotNull(MenuAction.GO_TO_ARTIST.takeUnless { context.onArtistPage || context.compilation }),
        ).filter { it.isNotEmpty() })

    /** Track: no rating (Coda neither rates nor shows track ratings). */
    fun track(context: TrackMenuContext = TrackMenuContext()) = MenuSpec(rating = false,
        buttons = listOf(MenuAction.PLAY_NEXT, MenuAction.ADD_TO_QUEUE, MenuAction.ADD_TO_PLAYLIST),
        groups = listOf(
            listOfNotNull(MenuAction.GO_TO_ALBUM.takeUnless { context.onAlbumPage }, MenuAction.GO_TO_ARTIST.takeIf { context.hasArtist }),
            listOfNotNull(MenuAction.REMOVE_FROM_PLAYLIST.takeIf { context.removable }),
        ).filter { it.isNotEmpty() })

    /** Playlist: Shuffle kept (a mix with no album order to protect). */
    fun playlist(context: PlaylistMenuContext = PlaylistMenuContext()) = MenuSpec(rating = false,
        buttons = listOfNotNull(MenuAction.PLAY, MenuAction.SHUFFLE.takeIf { context.shuffle }, MenuAction.ADD_TO_QUEUE),
        groups = listOf(
            listOfNotNull(MenuAction.PLAY_NEXT, MenuAction.KEEP_OFFLINE.takeIf { KEEP_OFFLINE_IN_MENUS }),
            if (context.editable) listOf(MenuAction.RENAME, MenuAction.DELETE) else emptyList(),
        ).filter { it.isNotEmpty() })

    /**
     * Artist: the three ways to start it in the row, Add to queue (oldest release first, like Play) below. No Go to entry:
     * the sheet is the artist. The same layout for every artist (muscle memory): see [artistEnabled].
     */
    fun artist() = MenuSpec(rating = false,
        buttons = listOf(MenuAction.PLAY, MenuAction.NEWEST, MenuAction.BEST_OF),
        groups = listOf(listOf(MenuAction.ADD_TO_QUEUE)))

    /** With one release, Play newest and Play best would only repeat Play: shown disabled, so nothing moves. */
    fun artistEnabled(action: MenuAction, releases: Int, bestOf: Boolean): Boolean = when (action) {
        MenuAction.NEWEST -> releases > 1
        MenuAction.BEST_OF -> releases > 1 && bestOf
        else -> releases > 0
    }
}
