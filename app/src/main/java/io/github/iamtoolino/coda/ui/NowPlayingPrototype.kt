package io.github.iamtoolino.coda.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class NowPlayingPrototype(val wireName: String) {
    BASELINE("baseline"),
    INSTRUMENT_RAIL("instrument-rail"),
    QUIET_DOCK("quiet-dock"),
    IMMERSIVE_UTILITIES("immersive-utilities"),
    QUEUE_DECK("queue-deck"),
    SESSION_BUTTON("session-button"),
    QUIET_DOCK_WIDE("quiet-dock-wide"),
    ;

    companion object {
        fun fromWireName(value: String?): NowPlayingPrototype? = entries.firstOrNull {
            it.wireName == value
        }
    }
}

internal enum class NowPlayingTitleStress(val wireName: String) {
    ACTUAL("actual"),
    TWO_LINES("two-lines"),
    THREE_LINES("three-lines"),
    ;

    companion object {
        fun fromWireName(value: String?): NowPlayingTitleStress? = entries.firstOrNull {
            it.wireName == value
        }
    }
}

internal object NowPlayingPrototypeStore {
    private val mutablePrototype = MutableStateFlow(NowPlayingPrototype.BASELINE)
    private val mutableTitleStress = MutableStateFlow(NowPlayingTitleStress.ACTUAL)
    val prototype = mutablePrototype.asStateFlow()
    val titleStress = mutableTitleStress.asStateFlow()

    fun publish(prototype: NowPlayingPrototype) {
        mutablePrototype.value = prototype
    }

    fun publishTitleStress(titleStress: NowPlayingTitleStress) {
        mutableTitleStress.value = titleStress
    }
}
