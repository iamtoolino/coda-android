package io.github.iamtoolino.coda.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.iamtoolino.coda.ui.NowPlayingPrototype
import io.github.iamtoolino.coda.ui.NowPlayingPrototypeStore

class NowPlayingPrototypeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        NowPlayingPrototype.fromWireName(intent.getStringExtra(EXTRA_PROTOTYPE))?.let(
            NowPlayingPrototypeStore::publish,
        )
    }

    companion object {
        const val ACTION = "io.github.iamtoolino.coda.debug.NOW_PLAYING_PROTOTYPE"
        const val EXTRA_PROTOTYPE = "prototype"
    }
}
