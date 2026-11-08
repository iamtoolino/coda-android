package dev.coda.android.playback

import android.app.SearchManager
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.session.SessionError

/**
 * Phone voice ("Hey Google, play X on Coda" without the car) arrives as [MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH].
 * It becomes the same request Media3's legacy bridge makes from the car's playFromSearch (one item, empty id, the query
 * and extras as request metadata), so [AutoLibraryCallback.onSetMediaItems] resolves both alike.
 */
object PhoneVoice {
    sealed interface Outcome {
        data object OpenApp : Outcome
        data class Toast(val message: String) : Outcome
    }

    /** Test seam: the toast's text (its accessibility event does not reach UiAutomation on the test emulators). */
    @androidx.annotation.VisibleForTesting @Volatile var shown: ((String) -> Unit)? = null

    fun toast(context: android.content.Context, message: String) {
        android.widget.Toast.makeText(context.applicationContext, message, android.widget.Toast.LENGTH_SHORT).show()
        shown?.invoke(message)
    }

    /** Request extra: the session plays once the resolved queue is installed (the car's bridge plays by itself). */
    const val EXTRA_PLAY = "dev.coda.android.voice.PLAY"
    /** Custom command the session sends the requesting controller once that queue plays. */
    const val STARTED = "dev.coda.android.voice.STARTED"

    /** A spoken request is short; a longer one (from any app, the activity is exported) is cut. */
    const val MAX_QUERY = 200

    fun handles(action: String?) = action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH

    /** Only the query and the string extras (focus, artist, album, title, ...) cross: nothing else from the caller is read. */
    fun request(extras: Bundle?): MediaItem {
        val strings = Bundle()
        // A caller's foreign Parcelable fails to unparcel: then there is no query (the start chain), never a crash.
        runCatching { extras?.keySet()?.toList() }.getOrNull()?.forEach { key ->
            runCatching { extras?.getString(key) }.getOrNull()?.let { strings.putString(key, it) }
        }
        strings.putBoolean(EXTRA_PLAY, true)
        return MediaItem.Builder().setRequestMetadata(MediaItem.RequestMetadata.Builder()
            .setSearchQuery(strings.getString(SearchManager.QUERY).orEmpty().take(MAX_QUERY)).setExtras(strings).build()).build()
    }

    /** The car's error messages (`AutoLibraryCallback.told`) as a toast; a sign-in error opens the app on its login screen. */
    fun outcome(code: Int, message: String?): Outcome = when {
        code == SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED -> Outcome.OpenApp
        message.isNullOrBlank() -> Outcome.Toast("Nothing to play right now")
        else -> Outcome.Toast(message)
    }
}
