package dev.coda.core.cache

/**
 * A file that may be deleted: a cached track, or a legacy file ([songId] null). [removable] is false for files
 * that are pinned by the read-ahead window, leased by the player, being written or protected by the request.
 */
data class Evictable(
    val name: String,
    val bytes: Long,
    /** Wall-clock ms; [Long.MAX_VALUE] for no date. */
    val keepUntil: Long,
    /** max(addedAt, lastPlayedAt): least recently used first among equal keep-until dates. */
    val lastUsed: Long,
    val songId: String? = null,
    val lastPlayedAt: Long? = null,
    /** Album id, disc, track number, file name: the last tie-break, so the order is deterministic. */
    val order: String = name,
    val removable: Boolean = true,
)

/**
 * the one pure planner: preview, maintenance, `makeRoom` and `planAdd` all use it,
 * so a preview equals what the following maintenance pass deletes on the same state.
 */
object CachePlanner {
    data class KeptSet(val key: String, val title: String?, val keptAt: Long, val trackIds: Set<String>)

    /** Kept sets that leave together, and the files only they hold. Shared bytes count once. */
    data class KeptUnit(val sets: List<KeptSet>, val files: List<Evictable>) {
        val bytes get() = files.sumOf { it.bytes }
    }

    data class Plan(
        /** Past their keep-until (maintenance only). */
        val expired: List<Evictable>,
        /** Music not kept, earliest keep-until first, for space. */
        val notKept: List<Evictable>,
        /** Whole kept sets for space, in order. */
        val units: List<KeptUnit>,
        /** Kept sets whose own keep date ran out and that have no cached track left after this plan. */
        val deadSets: List<KeptSet>,
        /** Bytes still missing to reach the line (or the request) after everything planned. */
        val missingBytes: Long,
        /** Song ids held by a kept set (for the sheet's split into "Not kept" and "Kept"). */
        val keptIds: Set<String>,
    ) {
        val evict: List<Evictable> get() = expired + notKept + units.flatMap { it.files }
        val bytes: Long get() = evict.sumOf { it.bytes }
        val removedSets: List<KeptSet> get() = units.flatMap { it.sets }
        val enough get() = missingBytes <= 0
        /** Files of music not kept offline (also expired ones). */
        val notKeptFiles: List<Evictable> get() = evict.filter { it.songId == null || it.songId !in keptIds }
        val keptFiles: List<Evictable> get() = evict.filter { it.songId != null && it.songId in keptIds }
        /** Kept sets this plan removes, by space or because their time ran out. */
        val keptSetsGone: List<KeptSet> get() = (removedSets + deadSets).distinctBy { it.key }
        val isEmpty get() = evict.isEmpty() && deadSets.isEmpty()
    }

    private val order = compareBy<Evictable>({ it.keepUntil }, { it.lastUsed }, { it.order }, { it.name })

    /**
     * [files] are every cached file with its keep-until under the policy in question. [needBytes] is what must be freed
     * for space (≤ 0: nothing). With [expire], files past their keep-until go first and count towards [needBytes].
     * Kept music goes only with [keptToo], as whole sets ([holder]: the requesting add, a holder that is never chosen).
     * [busy] are the songs being downloaded now: a set holding one is not chosen, even if the song has no file yet.
     */
    fun plan(files: List<Evictable>, sets: List<KeptSet>, now: Long, policy: CachePolicy, needBytes: Long,
        keptToo: Boolean, expire: Boolean, holder: Set<String> = emptySet(), busy: Set<String> = emptySet()): Plan {
        val bySong = files.filter { it.songId != null }.associateBy { it.songId!! }
        val keptIds = sets.flatMapTo(HashSet()) { it.trackIds }
        val gone = HashSet<String>()
        val expired = if (!expire) emptyList() else files.filter { it.removable && it.keepUntil <= now }
        expired.forEach { gone += it.name }
        var need = needBytes - expired.sumOf { it.bytes }
        val notKept = mutableListOf<Evictable>()
        if (need > 0) for (file in files.filter { it.removable && it.name !in gone &&
                (it.songId == null || (it.songId !in keptIds && it.songId !in holder)) }.sortedWith(order)) {
            if (need <= 0) break
            notKept += file; gone += file.name; need -= file.bytes
        }
        val units = mutableListOf<KeptUnit>()
        val remaining = sets.toMutableList()
        if (need > 0 && keptToo) while (need > 0) {
            val unit = remaining.mapNotNull { unit(it, remaining, holder, bySong, gone, busy) }
                .minWithOrNull(compareBy<Pair<KeptUnit, Triple<Long, Long, String>>>({ it.second.first }, { it.second.second },
                    { it.second.third }))?.first ?: break
            units += unit
            remaining.removeAll(unit.sets.toSet())
            unit.files.forEach { gone += it.name }
            need -= unit.bytes
        }
        val dead = remaining.filter { set -> policy.setUntil(set.keptAt) <= now &&
            set.trackIds.none { id -> bySong[id]?.let { it.name !in gone } == true } }
        return Plan(expired, notKept, units, dead, need.coerceAtLeast(0), keptIds)
    }

    /**
     * The unit [set] would leave in, with its sort key (latest keep-until, last use, key), or null when it can't leave
     * now: it has no own files even together with the sets sharing its tracks, or one of its own files is protected.
     */
    private fun unit(set: KeptSet, remaining: List<KeptSet>, holder: Set<String>, bySong: Map<String, Evictable>,
        gone: Set<String>, busy: Set<String>): Pair<KeptUnit, Triple<Long, Long, String>>? {
        fun cached(s: KeptSet) = s.trackIds.mapNotNull { id -> bySong[id]?.takeIf { it.name !in gone } }
        fun own(group: List<KeptSet>): List<Evictable> {
            val outside = remaining.filter { it !in group }.flatMapTo(HashSet()) { it.trackIds } + holder
            return group.flatMap(::cached).distinctBy { it.name }.filter { it.songId !in outside }
        }
        val mine = cached(set)
        if (mine.isEmpty()) return null
        var group = listOf(set)
        var files = own(group)
        if (files.isEmpty()) {
            // Overlap (r2 item 1): every cached track is also in another kept set; they leave together or not at all.
            val shared = mine.mapTo(HashSet()) { it.songId }
            group = listOf(set) + remaining.filter { it != set && it.trackIds.any { id -> id in shared } }
            files = own(group)
            if (files.isEmpty()) return null
        }
        if (files.any { !it.removable }) return null
        // A member being downloaded right now has no record yet, but its set is not cut (§3.2).
        if (group.any { member -> member.trackIds.any { it in busy } }) return null
        val all = group.flatMap(::cached)
        val until = all.maxOf { it.keepUntil }
        val used = maxOf(group.maxOf { it.keptAt }, all.mapNotNull { it.lastPlayedAt }.maxOrNull() ?: Long.MIN_VALUE)
        return KeptUnit(group.sortedBy { it.key }, files) to Triple(until, used, group.minOf { it.key })
    }
}
