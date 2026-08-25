package io.github.iamtoolino.coda.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class NowPlayingPrototype(val wireName: String) {
    BASELINE("baseline"),
    INSTRUMENT_RAIL("instrument-rail"),
    QUIET_DOCK("quiet-dock"),
    IMMERSIVE_UTILITIES("immersive-utilities"),
    ;

    companion object {
        fun fromWireName(value: String?): NowPlayingPrototype? = entries.firstOrNull {
            it.wireName == value
        }
    }
}

internal object NowPlayingPrototypeStore {
    private val mutablePrototype = MutableStateFlow(NowPlayingPrototype.BASELINE)
    val prototype = mutablePrototype.asStateFlow()

    fun publish(prototype: NowPlayingPrototype) {
        mutablePrototype.value = prototype
    }
}
