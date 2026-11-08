package dev.coda.android.playback

import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController

internal const val SUCCESSOR_ENTRY_ID = "dev.coda.playback.successorEntryId"

/** The adapter publishes controller policy by occurrence ID, safe across duplicate songs and edits. */
internal fun MediaController.resolvedSuccessor(): MediaItem? {
    val id = sessionExtras.getString(SUCCESSOR_ENTRY_ID) ?: return null
    return (0 until mediaItemCount).asSequence().map(::getMediaItemAt)
        .firstOrNull { it.mediaMetadata.extras?.getString("queueEntryId") == id }
}
