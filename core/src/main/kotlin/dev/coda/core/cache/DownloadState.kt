package dev.coda.core.cache

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The one shared state of the background downloads (kept sets and "Download whole queue"). The notification,
 * the Keep offline line of the album and playlist sheets and the queue header all read it, so they never disagree.
 *
 * [done] counts the tracks stored since this run of work began (pending went from none to some); [pending] is what
 * is still to download now, without tracks the server said it doesn't have. A run ends when nothing is pending, and
 * the counter starts again with the next one.
 */
data class DownloadState(
    val done: Int = 0,
    val pending: Int = 0,
    /** The set whose tracks download now (or would, when Wi-Fi is back), and its display name. */
    val currentKey: String? = null,
    val currentTitle: String? = null,
    /** Kept sets that still have tracks to download (the current one included). */
    val pendingKeys: Set<String> = emptySet(),
    /** Pending work waits for proper Wi-Fi (mobile data, a hotspot or offline now). */
    val waitingForWifi: Boolean = false,
    /** The last pass could not store anything although work is pending (retry back-off, no room). */
    val stalled: Boolean = false,
    /** The running "Download whole queue", if any. */
    val queue: QueueDownload? = null,
    /**
     * Work is pending on proper Wi-Fi but transfers are held: the app is in the background and the transfer job does not
     * run (it stopped or could not be scheduled). Pending work is kept; it continues when the app is visible or the job runs.
     */
    val paused: Boolean = false,
    /**
     * No audio is pending, but the kept covers of this pass are still being stored; the transfer job
     * keeps running until they are (bounded), so a keep finishing in the background never ends before its covers.
     * Not part of [active]: covers never hold up "ready offline".
     */
    val coversPending: Boolean = false,
    /**
     * Kept covers of this sign-in are still missing and untried, whether or not transfers are allowed right now
     * (a stopped job, Wi-Fi lost). The job is rescheduled for them (bounded: each copy is tried once per sign-in). Not
     * part of [active] either.
     */
    val coversDue: Boolean = false,
) {
    val total get() = done + pending
    val active get() = pending > 0

    fun phase(key: String): SetPhase = when {
        key !in pendingKeys -> SetPhase.IDLE
        waitingForWifi -> SetPhase.WAITING_FOR_WIFI
        key == currentKey -> SetPhase.DOWNLOADING
        else -> SetPhase.QUEUED
    }
}

/** "Download whole queue": the queue's distinct tracks at the tap, and how many are on the phone now. */
data class QueueDownload(val total: Int, val onPhone: Int)

enum class SetPhase { IDLE, DOWNLOADING, QUEUED, WAITING_FOR_WIFI }

/** The wording of every download line (DS-10b offline-toggle table, DS-10c queue states with PF-10c "ready offline" wording, DL-1 notification). */
object DownloadLines {
    /** The key of the one-shot queue download in [DownloadState.currentKey]. */
    const val QUEUE_KEY = "download/queue"

    /**
     * Under a day away, a time ("20:00", or "tomorrow 08:00" on the next date); otherwise "tomorrow", a
     * weekday within 6 days ("Sat"), else "28 Nov", in the phone's language and time zone, relative to [nowMs]. The
     * 12 h step makes "today" too coarse.
     */
    fun day(wallMs: Long, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault(),
        nowMs: Long = System.currentTimeMillis()): String {
        val date = Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDate()
        val days = java.time.temporal.ChronoUnit.DAYS.between(Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate(), date)
        val time = { DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(wallMs).atZone(zone)) }
        val underADay = wallMs - nowMs < 24 * 3_600_000L
        return when (days) {
            0L -> time()
            1L -> if (underADay) "tomorrow ${time()}" else "tomorrow"
            in 2L..6L -> DateTimeFormatter.ofPattern("EEE", locale).format(date)
            else -> DateTimeFormatter.ofPattern("d MMM", locale).format(date)
        }
    }

    /**
     * The Keep offline line of the album and playlist sheets: origin is not shown. A set that does not look kept
     * ([ItemStatus.looksKept]: not kept, or tracks missing with nothing downloading) has none; kept for as long as possible: none;
     * kept with a time limit: only "Preferred until <date>" (was "Kept until") ([until] is the formatted [ItemStatus.availableUntil]); while the user's own
     * download runs, its progress.
     */
    fun keepSheet(status: ItemStatus, phase: SetPhase, until: String?): String? {
        if (!status.looksKept) return null
        val of = "${status.cachedTracks} of ${status.totalTracks}"
        return when {
            status.pendingTracks > 0 && phase == SetPhase.WAITING_FOR_WIFI -> "Waiting for Wi-Fi · $of"
            status.pendingTracks > 0 && phase == SetPhase.QUEUED -> "Queued · $of"
            status.pendingTracks > 0 -> "Downloading $of"
            else -> until?.let { "Preferred until $it" }
        }
    }

    /** The queue ⋮ entry for "Cache queue ahead" (was "Load for offline"): its label, the line under it, and whether it can be tapped. */
    data class QueueEntry(val label: String, val line: String, val enabled: Boolean, val running: Boolean)

    /**
     * [queueTracks] are the queue's distinct tracks now, [onPhone] those of them ready offline. While a queue load
     * runs, its own count (the queue at the tap) is shown; tracks added later count once it is done. [until] is how long
     * the queue's tracks stay: the earliest keep-until as [day] wording, or "space runs low" for no date; null if unknown.
     */
    fun queueEntry(queueTracks: Int, onPhone: Int, state: DownloadState, until: String? = null, missingTracks: Int = queueTracks - onPhone): QueueEntry {
        val running = state.queue
        if (running != null) return if (state.waitingForWifi)
            QueueEntry(WAITING_FOR_NETWORK, "${running.onPhone} of ${running.total} ready offline", enabled = true, running = true)
            else QueueEntry("Caching queue ahead… ${running.onPhone} of ${running.total}", "", enabled = true, running = true)
        val missing = queueTracks - onPhone
        return if (missingTracks <= 0) QueueEntry(QUEUE_LABEL, "All $queueTracks ready offline" + (until?.let { " until $it" } ?: ""), enabled = false, running = false)
            else QueueEntry(QUEUE_LABEL, "$missing of $queueTracks still stream", enabled = true, running = false)
    }

    /** The queue load's waiting state (queue ⋮ entry, header, notification, queue sheet hint); it waits only with no network. */
    const val WAITING_FOR_NETWORK = "Waiting for a network"

    /** The queue ⋮ action. */
    const val QUEUE_LABEL = "Cache queue ahead"

    /** The queue header's line after "40 tracks · 2 h 31 min · " (while a load runs, the length is left out: "40 tracks · Caching 26 of 40"). [onPhoneEntries] is the count shown while some tracks still stream. */
    fun queueHeader(queueTracks: Int, onPhone: Int, state: DownloadState, onPhoneEntries: Int = onPhone): String {
        val running = state.queue
        return when {
            running != null && state.waitingForWifi -> "$WAITING_FOR_NETWORK · ${running.onPhone} of ${running.total}"
            running != null -> "Caching ${running.onPhone} of ${running.total}"
            queueTracks == 0 -> ""
            onPhone >= queueTracks -> "all ready offline"
            else -> "$onPhoneEntries ready offline"
        }
    }

    /**
     * The snackbar after the tap, once (PF-12b "With stay time": short enough for the Short duration): [missing] tracks load;
     * [stays] is the Heard once time ("3 days"), null for "until space runs low".
     */
    fun queueLoadMessage(missing: Int, stays: String?): String =
        "Caching $missing ${if (missing == 1) "track" else "tracks"} · offline ${stays?.let { "for $it" } ?: "until space runs low"}"

    /**
     * The notification title, e.g. "Downloading 23 of 120" (the 23rd is on its way); its text is [DownloadState.currentTitle].
     * A job with only kept covers left (no audio pending) says so, with no count ([COVERS_ONLY]).
     */
    fun notification(state: DownloadState): String = when {
        !state.active -> COVERS_ONLY
        // The queue load runs on any network, so while it runs, waiting means no network at all. Prefer offline stays Wi-Fi only.
        state.waitingForWifi -> "${if (state.queue != null) WAITING_FOR_NETWORK else "Waiting for Wi-Fi"} · ${state.done} of ${state.total}"
        else -> "Downloading ${(state.done + 1).coerceAtMost(state.total)} of ${state.total}"
    }

    /** The notification title while only kept covers are stored ("artwork", as in Settings › Storage). */
    const val COVERS_ONLY = "Saving artwork for offline"
}
