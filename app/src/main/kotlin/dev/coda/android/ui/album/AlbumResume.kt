package dev.coda.android.ui.album

/**
 * The one rule for offering Resume album, on the album page's card and in the album ⋮ sheet. Returns the
 * position of [resumeSongId] in [songIds], or -1 when there is nothing to resume: no resume point, the album is the
 * one playing now ([currentMediaId] is one of its tracks), or offline ([here] non-null) the resume track isn't on the phone.
 */
internal fun albumResumeIndex(songIds: List<String>, resumeSongId: String?, currentMediaId: String?, here: ((String) -> Boolean)?): Int {
    if (resumeSongId == null) return -1
    if (currentMediaId != null && songIds.any { "song/$it" == currentMediaId }) return -1
    if (here != null && !here(resumeSongId)) return -1
    return songIds.indexOf(resumeSongId)
}
