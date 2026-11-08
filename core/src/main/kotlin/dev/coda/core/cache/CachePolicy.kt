package dev.coda.core.cache

/**
 * lifetimes. Stored by [key], so wording can change without a migration; [hours] null is
 * "As long as possible" (no date: the music stays until Keep free space needs the room). The unit is hours since the
 * 12 h step; a new stored key, so no migration.
 */
enum class Lifetime(val key: String, val hours: Int?, val label: String) {
    HALF_DAY("12h", 12, "12 hours"),
    DAY("1d", 24, "1 day"),
    THREE_DAYS("3d", 3 * 24, "3 days"),
    WEEK("1w", 7 * 24, "1 week"),
    TWO_WEEKS("2w", 14 * 24, "2 weeks"),
    MONTH("1m", 30 * 24, "1 month"),
    THREE_MONTHS("3m", 90 * 24, "3 months"),
    SIX_MONTHS("6m", 180 * 24, "6 months"),
    YEAR("1y", 365 * 24, "1 year"),
    MAX("max", null, "As long as possible");

    val ms: Long? get() = hours?.let { it * 3_600_000L }

    companion object {
        fun fromKey(key: String?): Lifetime? = entries.firstOrNull { it.key == key }
    }
}

/** [at] + [ms], saturating at [Long.MAX_VALUE]; null [ms] ("As long as possible") is [Long.MAX_VALUE]. */
internal fun plusSaturating(at: Long, ms: Long?): Long = when {
    ms == null -> Long.MAX_VALUE
    at > Long.MAX_VALUE - ms -> Long.MAX_VALUE
    else -> at + ms
}

/** The three custom times, for [pushOrdered] and [CachePolicy.moved]. */
enum class TimeSlot { KEPT, HEARD_AGAIN, HEARD_ONCE }

/**
 * The lifetime [moved] is dragged to [to] (an index into [Lifetime.entries]); the other two follow so that
 * once <= again <= kept always holds. The moved one always lands on [to]; a neighbour moves only when it would be crossed.
 * Returns (kept, again, once).
 */
fun pushOrdered(kept: Int, again: Int, once: Int, moved: TimeSlot, to: Int): Triple<Int, Int, Int> = when (moved) {
    TimeSlot.KEPT -> { val a = minOf(again, to); Triple(to, a, minOf(once, a)) }
    TimeSlot.HEARD_AGAIN -> Triple(maxOf(kept, to), to, minOf(once, to))
    TimeSlot.HEARD_ONCE -> { val a = maxOf(again, to); Triple(maxOf(kept, a), a, to) }
}

/** This policy with [slot] dragged to [to] and the other times pushed along. */
fun CachePolicy.moved(slot: TimeSlot, to: Lifetime): CachePolicy {
    val (k, a, o) = pushOrdered(kept.ordinal, heardAgain.ordinal, heardOnce.ordinal, slot, to.ordinal)
    return copy(kept = Lifetime.entries[k], heardAgain = Lifetime.entries[a], heardOnce = Lifetime.entries[o])
}

/** The three presets of "What stays on this phone" (§5.2), in the order Kept / Heard once / Heard again. */
enum class StoragePreset(val title: String, val kept: Lifetime, val heardOnce: Lifetime, val heardAgain: Lifetime, val explanation: String) {
    LESS_SPACE("Use less space", Lifetime.MAX, Lifetime.HALF_DAY, Lifetime.HALF_DAY,
        "Keeps albums and playlists set to Prefer offline. Everything else leaves soon after you play it, so most music streams again."),
    BALANCED("Balanced", Lifetime.MAX, Lifetime.THREE_DAYS, Lifetime.TWO_WEEKS,
        "Keeps what you set to Prefer offline and what you played recently: songs you come back to for 2 weeks, others for 3 days."),
    STREAM_LESS("Stream less", Lifetime.MAX, Lifetime.MAX, Lifetime.MAX,
        "Keeps everything you play until Coda needs the space. Uses the most space and streams the least."),
}

/**
 * Music storage settings: T1 [kept] (Kept offline), T2 [heardOnce] (also music that arrived and wasn't played
 * yet), T3 [heardAgain] and the Keep free space line in decimal bytes.
 */
data class CachePolicy(
    val kept: Lifetime,
    val heardOnce: Lifetime,
    val heardAgain: Lifetime,
    val keepFreeBytes: Long = DEFAULT_KEEP_FREE_GB * GB,
) {
    /** T2 ≤ T3 ≤ T1 (§1.3). Only raises, so a bad stored combination can never delete music. */
    fun normalized(): CachePolicy {
        val again = maxOf(heardAgain, heardOnce)
        return copy(heardAgain = again, kept = maxOf(kept, again))
    }

    /** The matching preset, or null for Custom. The preset is derived, never stored. */
    fun preset(): StoragePreset? = StoragePreset.entries.firstOrNull {
        it.kept == kept && it.heardOnce == heardOnce && it.heardAgain == heardAgain
    }

    /**
     * The preset shown as selected, or null for Custom: Custom was chosen ([customChosen], stored), or the times match no
     * preset. A Custom set whose times equal a preset's stays Custom until a preset is picked.
     */
    fun selection(customChosen: Boolean): StoragePreset? = if (customChosen) null else preset()

    fun with(preset: StoragePreset) = copy(kept = preset.kept, heardOnce = preset.heardOnce, heardAgain = preset.heardAgain)

    /**
     * The keep-until of a cached track (§1.1:04): the max of every term that applies, so a play never shortens a
     * life. [keptAt] is the newest keep date of the kept sets holding it, or null when no kept set holds it. Wall-clock ms;
     * [Long.MAX_VALUE] means no date.
     */
    fun keepUntil(addedAt: Long, lastPlayedAt: Long?, playCount: Int, keptAt: Long?): Long {
        val plays = maxOf(playCount, if (lastPlayedAt != null) 1 else 0) // = CachedTrack.plays
        var until = plusSaturating(addedAt, heardOnce.ms)
        if (lastPlayedAt != null && plays >= 1) until = maxOf(until, plusSaturating(lastPlayedAt, heardOnce.ms))
        if (lastPlayedAt != null && plays >= 2) until = maxOf(until, plusSaturating(lastPlayedAt, heardAgain.ms))
        if (keptAt != null) until = maxOf(until, plusSaturating(keptAt, kept.ms))
        return until
    }

    fun keepUntil(track: CachedTrack, keptAt: Long?) = keepUntil(track.addedAt, track.lastPlayedAt, track.playCount, keptAt)

    /** When a kept set's own keep date runs out (the dead-set rule); [Long.MAX_VALUE] for "As long as possible". */
    fun setUntil(keptAt: Long) = plusSaturating(keptAt, kept.ms)

    companion object {
        const val GB = 1_000_000_000L
        const val DEFAULT_KEEP_FREE_GB = 5
        /** Keep free space steps in GB (§1.2). */
        val KEEP_FREE_GB = listOf(1, 2, 5, 10, 15, 20, 30, 50)
        val DEFAULT = CachePolicy(Lifetime.MAX, Lifetime.THREE_DAYS, Lifetime.TWO_WEEKS)

        /**
         * Reads stored keys (§1.2): a missing or unknown key reads as the default, an unknown free-space value too; the
         * result is [normalized].
         */
        fun fromKeys(kept: String?, heardOnce: String?, heardAgain: String?, keepFreeGb: Int?): CachePolicy = CachePolicy(
            Lifetime.fromKey(kept) ?: DEFAULT.kept,
            Lifetime.fromKey(heardOnce) ?: DEFAULT.heardOnce,
            Lifetime.fromKey(heardAgain) ?: DEFAULT.heardAgain,
            (keepFreeGb?.takeIf { it in KEEP_FREE_GB } ?: DEFAULT_KEEP_FREE_GB) * GB,
        ).normalized()
    }
}
