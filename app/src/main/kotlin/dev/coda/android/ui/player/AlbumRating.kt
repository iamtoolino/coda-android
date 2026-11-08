package dev.coda.android.ui.player

import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import dev.coda.android.ui.library.LocalLibrarySession
import dev.coda.android.ui.library.RatingControl
import kotlinx.coroutines.CancellationException

/**
 * The stars on Now Playing are the album's rating. The queue item carries it when the album was known
 * where the queue was built; otherwise (search, playlists) the album is asked from the server once.
 */
@Composable
internal fun AlbumRatingControl(extras: Bundle?, tint: Color) {
    val albumId = extras?.getString("albumId")?.takeIf { it.isNotEmpty() } ?: return
    val known = extras.takeIf { it.containsKey("albumRating") }?.getInt("albumRating")
    val session = LocalLibrarySession.current
    val fetched by produceState<Int?>(null, albumId, known) {
        value = null
        if (known == null && session != null) value = try { session.client.getAlbum(albumId).album.userRating ?: 0 }
            catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    Box(Modifier.testTag("npsRating")) { RatingControl(albumId, known ?: fetched ?: 0, "Album rating", tint) }
}
