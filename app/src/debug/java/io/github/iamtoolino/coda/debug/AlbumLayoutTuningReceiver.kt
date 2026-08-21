package io.github.iamtoolino.coda.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.iamtoolino.coda.ui.AlbumLayoutTuning
import io.github.iamtoolino.coda.ui.AlbumLayoutTuningStore
import io.github.iamtoolino.coda.ui.AlbumLayoutVariant

class AlbumLayoutTuningReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val variant = AlbumLayoutVariant.fromWireName(intent.getStringExtra(EXTRA_LAYOUT)) ?: return
        AlbumLayoutTuningStore.publish(
            AlbumLayoutTuning(
                enabled = true,
                variant = variant,
                buttonsOffsetDp = intent.getFloatExtra(EXTRA_BUTTONS_OFFSET_DP, 0f),
                ratingOffsetDp = intent.getFloatExtra(EXTRA_RATING_OFFSET_DP, 0f),
            ),
        )
    }

    companion object {
        const val ACTION = "io.github.iamtoolino.coda.debug.ALBUM_LAYOUT_TUNING"
        const val EXTRA_LAYOUT = "layout"
        const val EXTRA_BUTTONS_OFFSET_DP = "buttons_offset_dp"
        const val EXTRA_RATING_OFFSET_DP = "rating_offset_dp"
    }
}
