package dev.coda.android.playback

import android.os.Parcel
import androidx.media3.common.MediaMetadata

/**
 * Keeps everything handed to Android Auto and the system UI small and local:
 * - no http(s) artwork URI, in `artworkUri` or in extras: the server (possibly only reachable over Tailscale)
 *   can't be fetched by hosts, and its URL would carry credentials. Only our `content://` provider URIs remain;
 * - free text is truncated, so a server-supplied 1 MB title can't fill the Binder buffer;
 * - the parcelled size (cover bytes plus all other fields) stays under [MAX_PARCEL_BYTES]; the cover is the
 *   last thing dropped, after text is cut, and only when it alone would break the limit.
 */
internal object MetadataGuard {
    const val MAX_TEXT = 512
    /** The Binder buffer is 1 MiB per process, shared by all transactions in flight. */
    const val MAX_PARCEL_BYTES = 768 * 1024

    fun isLocal(uri: android.net.Uri?): Boolean = uri == null || uri.scheme == "content"

    /** Fields without the cover, parcelled, plus the cover bytes: does not depend on how Media3 bundles the cover. */
    fun parcelSize(metadata: MediaMetadata): Int {
        val cover = metadata.artworkData?.size ?: 0
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(metadata.buildUpon().setArtworkData(null, null).build().toBundle())
            parcel.dataSize() + cover
        } finally { parcel.recycle() }
    }

    fun guard(metadata: MediaMetadata): MediaMetadata {
        val builder = metadata.buildUpon()
        fun cut(text: CharSequence?) = text?.takeIf { it.length > MAX_TEXT }?.let { it.subSequence(0, MAX_TEXT) } ?: text
        builder.setTitle(cut(metadata.title)).setSubtitle(cut(metadata.subtitle)).setArtist(cut(metadata.artist))
            .setAlbumTitle(cut(metadata.albumTitle)).setAlbumArtist(cut(metadata.albumArtist))
            .setDisplayTitle(cut(metadata.displayTitle)).setDescription(cut(metadata.description))
        if (!isLocal(metadata.artworkUri)) builder.setArtworkUri(null)
        metadata.extras?.let { extras ->
            val clean = android.os.Bundle(extras)
            for (key in extras.keySet()) {
                val value = extras.get(key)
                if (value is String && (value.startsWith("http://", true) || value.startsWith("https://", true))) clean.remove(key)
                else if (value is String && value.length > MAX_TEXT) clean.putString(key, value.substring(0, MAX_TEXT))
                else if (value is android.net.Uri && !isLocal(value)) clean.remove(key)
            }
            builder.setExtras(clean)
        }
        val result = builder.build()
        if (result.artworkData == null) return result
        return if (parcelSize(result) > MAX_PARCEL_BYTES) result.buildUpon().setArtworkData(null, null).build() else result
    }

    /** True when no URI-looking field of [metadata] points at a web server. */
    fun hasNoWebUri(metadata: MediaMetadata): Boolean {
        if (!isLocal(metadata.artworkUri)) return false
        val extras = metadata.extras ?: return true
        return extras.keySet().none { key ->
            when (val v = extras.get(key)) {
                is String -> v.startsWith("http://", true) || v.startsWith("https://", true)
                is android.net.Uri -> !isLocal(v)
                else -> false
            }
        }
    }
}
