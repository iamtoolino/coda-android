package dev.coda.core.playback

import kotlin.math.log10
import kotlin.math.min

/** API adapters treat malformed optional fields as absent. Policy also rejects non-finite values. */
data class ReplayGainMetadata(
    val trackGain: Double? = null,
    val albumGain: Double? = null,
    val trackPeak: Double? = null,
    val albumPeak: Double? = null,
)

data class ReplayGainDecision(val source: Source, val requestedGainDb: Double, val peak: Double?) {
    enum class Source { OFF, TRACK, ALBUM, FALLBACK }

    private val peakLimitedGainDb: Double
        get() = if (peak != null && peak.isFinite() && peak > 0) {
            min(requestedGainDb, -20 * log10(peak))
        } else requestedGainDb
    val isPeakLimited: Boolean get() = peakLimitedGainDb < requestedGainDb
    val gainDb: Double get() = peakLimitedGainDb.coerceIn(-150.0, 150.0)
}

/** Port of ReplayGain.swift; continuation hints do not grant album gain to a singleton. */
object ReplayGainPolicy {
    const val fallbackDb = -9.0

    fun gainDb(enabled: Boolean, context: AlbumSequenceContext?, metadata: ReplayGainMetadata?): Double =
        decision(enabled, context, metadata).gainDb

    fun decision(enabled: Boolean, context: AlbumSequenceContext?, metadata: ReplayGainMetadata?): ReplayGainDecision {
        if (!enabled) return ReplayGainDecision(ReplayGainDecision.Source.OFF, 0.0, null)
        val usesAlbum = context?.isAlbumSequence == true && metadata?.albumGain?.isFinite() == true
        val gain = if (usesAlbum) metadata.albumGain else metadata?.trackGain
        val peak = if (usesAlbum) metadata.albumPeak else metadata?.trackPeak
        if (gain == null || !gain.isFinite()) {
            return ReplayGainDecision(ReplayGainDecision.Source.FALLBACK, fallbackDb, null)
        }
        return ReplayGainDecision(
            if (usesAlbum) ReplayGainDecision.Source.ALBUM else ReplayGainDecision.Source.TRACK,
            gain, peak,
        )
    }
}
