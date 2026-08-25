package io.github.iamtoolino.coda.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.iamtoolino.coda.ui.NowPlayingPrototype
import io.github.iamtoolino.coda.ui.NowPlayingPrototypeStore
import io.github.iamtoolino.coda.ui.NowPlayingTitleStress

class NowPlayingPrototypeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        NowPlayingPrototype.fromWireName(intent.getStringExtra(EXTRA_PROTOTYPE))
            ?.let(NowPlayingPrototypeStore::publish)
        NowPlayingTitleStress.fromWireName(intent.getStringExtra(EXTRA_TITLE_STRESS))
            ?.let(NowPlayingPrototypeStore::publishTitleStress)
    }

    companion object {
        const val ACTION = "io.github.iamtoolino.coda.debug.NOW_PLAYING_PROTOTYPE"
        const val EXTRA_PROTOTYPE = "prototype"
        const val EXTRA_TITLE_STRESS = "title_stress"
    }
}
