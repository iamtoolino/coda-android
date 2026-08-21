package io.github.iamtoolino.coda.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class AlbumLayoutVariant(val wireName: String) {
    BELOW_ARTWORK("below_artwork"),
    METADATA_FIRST("metadata_first"),
    ARTWORK_OVERLAY("artwork_overlay"),
    MACOS_STACK("macos_stack"),
    ;

    companion object {
        fun fromWireName(value: String?): AlbumLayoutVariant? = entries.firstOrNull {
            it.wireName == value
        }
    }
}

internal data class AlbumLayoutTuning(
    val enabled: Boolean = false,
    val variant: AlbumLayoutVariant = AlbumLayoutVariant.BELOW_ARTWORK,
    val buttonsOffsetDp: Float = 0f,
    val ratingOffsetDp: Float = 0f,
) {
    fun sanitized(): AlbumLayoutTuning = copy(
        buttonsOffsetDp = buttonsOffsetDp.coerceIn(MIN_OFFSET_DP, MAX_OFFSET_DP),
        ratingOffsetDp = ratingOffsetDp.coerceIn(MIN_OFFSET_DP, MAX_OFFSET_DP),
    )

    companion object {
        const val MIN_OFFSET_DP = -96f
        const val MAX_OFFSET_DP = 96f
    }
}

internal object AlbumLayoutTuningStore {
    var current by mutableStateOf(AlbumLayoutTuning())
        private set

    fun publish(tuning: AlbumLayoutTuning) {
        current = tuning.sanitized()
    }

    fun reset() {
        current = AlbumLayoutTuning()
    }
}
